/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon

import com.weiglewilczek.slf4s.Logging
import silver.verifier.errors.{IfFailed, InhaleFailed, LoopInvariantNotPreserved,
    LoopInvariantNotEstablished, WhileFailed, AssignmentFailed, ExhaleFailed, PreconditionInCallFalse, FoldFailed,
    UnfoldFailed, AssertFailed, PackageFailed, ApplyFailed, LetWandFailed}
import silver.verifier.reasons.{NegativePermission, ReceiverNull, AssertionFalse, NamedMagicWandChunkNotFound}
import interfaces.{Executor, Evaluator, Producer, Consumer, VerificationResult, Failure, Success}
import interfaces.decider.Decider
import interfaces.state.{Store, Heap, PathConditions, State, StateFactory, StateFormatter, HeapCompressor, Chunk}
import interfaces.state.factoryUtils.Ø
import state.terms._
import state.{MagicWandChunk, FieldChunkIdentifier, DirectFieldChunk, SymbolConvert, DirectChunk, DefaultContext}
import state.terms.perms.IsPositive
import silicon.supporters.{PredicateSupporter, MagicWandSupporter}

trait DefaultExecutor[ST <: Store[ST],
                      H <: Heap[H],
                      PC <: PathConditions[PC],
                      S <: State[ST, H, S]]
    extends Executor[ST, H, S, DefaultContext[H]]
    { this: Logging with Evaluator[ST, H, S, DefaultContext[H]]
                    with Consumer[Chunk, ST, H, S, DefaultContext[H]]
                    with Producer[ST, H, S, DefaultContext[H]]
                    with PredicateSupporter[ST, H, PC, S]
                    with Brancher[ST, H, S, DefaultContext[H]]
                    with MagicWandSupporter[ST, H, PC, S]
                    with LetHandler[ST, H, S, DefaultContext[H]] =>

  private type C = DefaultContext[H]

  protected implicit val manifestH: Manifest[H]

  protected val decider: Decider[ST, H, PC, S, C]
  import decider.{fresh, assume, inScope}

  protected val stateFactory: StateFactory[ST, H, S]
  import stateFactory._

  protected val symbolConverter: SymbolConvert
  import symbolConverter.toSort

  protected val stateUtils: StateUtils[ST, H, PC, S, C]
  import stateUtils.freshARP

  protected val heapCompressor: HeapCompressor[ST, H, S, C]
  protected val stateFormatter: StateFormatter[ST, H, S, String]
  protected val config: Config

  private def follow(σ: S, edge: ast.CFGEdge, c: C)
                    (Q: (S, C) => VerificationResult)
                    : VerificationResult = {

    edge match {
      case ce: silver.ast.ConditionalEdge =>
        eval(σ, ce.cond, IfFailed(ce.cond), c)((tCond, c1) =>
        /* TODO: Use FollowEdge instead of IfBranching */
          branch(σ, tCond, c1,
            (c2: C) => exec(σ, ce.dest, c2)(Q),
            (c2: C) => Success()))

      case ue: silver.ast.UnconditionalEdge => exec(σ, ue.dest, c)(Q)
    }
  }

  private def follows(σ: S, edges: Seq[ast.CFGEdge], c: C)
                     (Q: (S, C) => VerificationResult)
                     : VerificationResult = {

    if (edges.isEmpty) {
      Q(σ, c)
    } else
      follows2(σ, edges, c)(Q)
  }

  private def follows2(σ: S, edges: Seq[ast.CFGEdge], c: C)
                      (Q: (S, C) => VerificationResult)
                      : VerificationResult = {

    if (edges.isEmpty) {
      Success()
    } else {
      follow(σ, edges.head, c)(Q) && follows2(σ, edges.tail, c)(Q)
    }
  }

  private def leave(σ: S, block: ast.CFGBlock, c: C)
                   (Q: (S, C) => VerificationResult)
                   : VerificationResult = {

    follows(σ, block.succs, c)(Q)
  }

  def exec(σ: S, block: ast.CFGBlock, c: C)
          (Q: (S, C) => VerificationResult)
          : VerificationResult = {

    block match {
      case block @ silver.ast.StatementBlock(stmt, _) =>
        exec(σ, stmt, c)((σ1, c1) =>
          leave(σ1, block, c1)(Q))

      case lb: silver.ast.LoopBlock =>
        decider.prover.logComment(s"loop at ${lb.pos}")

        /* TODO: We should avoid roundtripping, i.e., parsing a SIL file into an AST,
         *       which is then converted into a CFG, from which we then compute an
         *       AST again.
         */
        val loopStmt = lb.toAst.asInstanceOf[ast.While]
        val inv = ast.utils.BigAnd(lb.invs, Predef.identity, lb.pos)
        val invAndGuard = ast.And(inv, lb.cond)(inv.pos, inv.info)
        val notGuard = ast.Not(lb.cond)(lb.cond.pos, lb.cond.info)
        val invAndNotGuard = ast.And(inv, notGuard)(inv.pos, inv.info)

        /* Havoc local variables that are assigned to in the loop body but
         * that have been declared outside of it, i.e. before the loop.
         */
        val wvs = lb.writtenVars filterNot (_.typ == ast.types.Wand)
          /* TODO: BUG: Variables declared by LetWand show up in this list, but shouldn't! */

        val γBody = Γ(wvs.foldLeft(σ.γ.values)((map, v) => map.updated(v, fresh(v))))
        val σBody = Σ(γBody, Ø, σ.g) /* Use the old-state of the surrounding block as the old-state of the loop. */

        (inScope {
          /* Verify loop body (including well-formedness check) */
          decider.prover.logComment("Verify loop body")
          produce(σBody, fresh,  FullPerm(), invAndGuard, WhileFailed(loopStmt), c)((σ1, c1) =>
          /* TODO: Detect potential contradictions between path conditions from loop guard and invariant.
           *       Should no longer be necessary once we have an on-demand handling of merging and
           *       false-checking.
           */
            if (decider.checkSmoke())
              Success() /* TODO: Mark branch as dead? */
            else
              exec(σ1, lb.body, c1)((σ2, c2) =>
                consumes(σ2,  FullPerm(), lb.invs, e => LoopInvariantNotPreserved(e), c2)((σ3, _, _, c3) =>
                  Success())))}
            &&
          inScope {
            /* Verify call-site */
            decider.prover.logComment("Establish loop invariant")
            consumes(σ,  FullPerm(), lb.invs, e => LoopInvariantNotEstablished(e), c)((σ1, _, _, c1) => {
              val σ2 = σ1 \ γBody
              decider.prover.logComment("Continue after loop")
              produce(σ2, fresh,  FullPerm(), invAndNotGuard, WhileFailed(loopStmt), c1)((σ3, c2) =>
              /* TODO: Detect potential contradictions between path conditions from loop guard and invariant.
               *       Should no longer be necessary once we have an on-demand handling of merging and
               *       false-checking.
               */
                if (decider.checkSmoke())
                  Success() /* TODO: Mark branch as dead? */
                else
                  leave(σ3, lb, c2)(Q))})})

        case frp @ silver.ast.ConstrainingBlock(vars, body, succ) =>
          val arps = vars map σ.γ.apply
          val c1 = c.setConstrainable(arps, true)
          exec(σ, body, c1)((σ1, c2) =>
            leave(σ1, frp, c2.setConstrainable(arps, false))(Q))
    }
  }

  def execs(σ: S, stmts: Seq[ast.Statement], c: C)
           (Q: (S, C) => VerificationResult)
           : VerificationResult =

    if(stmts.nonEmpty)
      exec(σ, stmts.head, c)((σ1, c1) =>
        execs(σ1, stmts.tail, c1)(Q))
    else
      Q(σ, c)

  def exec(σ: S, stmt: ast.Statement, c: C)
          (Q: (S, C) => VerificationResult)
          : VerificationResult = {

    /* For debugging-purposes only */
    stmt match {
      case _: silver.ast.Seqn =>
      case _ =>
        logger.debug(s"\nEXECUTE ${stmt.pos}: $stmt")
        logger.debug(stateFormatter.format(σ))
        decider.prover.logComment("[exec]")
        decider.prover.logComment(stmt.toString)
    }

    val executed = stmt match {
      case silver.ast.Seqn(stmts) =>
        execs(σ, stmts, c)(Q)

      case ass @ ast.Assignment(v, rhs) =>
        v.typ match {
          case ast.types.Wand =>
            assert(rhs.isInstanceOf[ast.MagicWand], s"Expected magic wand but found $rhs (${rhs.getClass.getName}})")
            val wand = rhs.asInstanceOf[ast.MagicWand]
            val pve = LetWandFailed(ass)
            magicWandSupporter.createChunk(σ, wand, pve, c)((chWand, c1) =>
              Q(σ \+ (v, MagicWandChunkTerm(chWand)), c))
          case _ =>
            eval(σ, rhs, AssignmentFailed(ass), c)((tRhs, c1) =>
              Q(σ \+ (v, tRhs), c1))
        }

      case ass @ ast.FieldWrite(fa @ ast.FieldAccess(eRcvr, field), rhs) =>
        val pve = AssignmentFailed(ass)
        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          decider.assert(σ, tRcvr !== Null()){
            case true =>
              eval(σ, rhs, pve, c1)((tRhs, c2) => {
                val id = FieldChunkIdentifier(tRcvr, field.name)
                decider.withChunk[DirectChunk](σ, σ.h, id, Some(FullPerm()), fa, pve, c2)(fc =>
                  Q(σ \- fc \+ DirectFieldChunk(tRcvr, field.name, tRhs, fc.perm), c2))})
            case false =>
              Failure[ST, H, S](pve dueTo ReceiverNull(fa))})

      case ast.New(v, fields) =>
        val t = fresh(v)
        assume(t !== Null())
        val newh = H(fields.map(f => DirectFieldChunk(t, f.name, fresh(f.name, toSort(f.typ)), FullPerm())))
        val σ1 = σ \+ (v, t) \+ newh
        val refs = state.utils.getDirectlyReachableReferencesState[ST, H, S](σ1) - t
        assume(And(refs map (_ !== t)))
        Q(σ1, c)

      case ast.Fresh(vars) =>
        val (arps, arpConstraints) =
          vars.map(v => (v, freshARP()))
              .map{case (variable, (value, constrain)) => ((variable, value), constrain)}
              .unzip
        val γ1 = Γ(σ.γ.values ++ arps)
          /* It is crucial that the (var -> term) mappings in arps override
           * already existing bindings for the same vars when they are added
           * (via ++).
           */
        assume(toSet(arpConstraints))
        Q(σ \ γ1, c)

      case inhale @ ast.Inhale(a) => a match {
        case _: ast.False =>
          /* We're done */
          Success()
        case _ =>
          produce(σ, fresh, FullPerm(), a, InhaleFailed(inhale), c)((σ1, c1) =>
            Q(σ1, c1))
      }

      case exhale @ ast.Exhale(a) =>
        val pve = ExhaleFailed(exhale)
        consume(σ, FullPerm(), a, pve, c)((σ1, _, _, c1) =>
          Q(σ1, c1))

      case assert @ ast.Assert(a) =>
        val pve = AssertFailed(assert)

        a match {
          /* "assert true" triggers a heap compression. */
          case _: ast.True =>
            heapCompressor.compress(σ, σ.h, c)
            Q(σ, c)

          /* "assert false" triggers a smoke check. If successful, we backtrack. */
          case _: ast.False =>
            decider.tryOrFail[(S, C)](σ, c)((σ1, QS, QF) => {
              if (decider.checkSmoke())
                QS(σ1, c)
              else
                QF(Failure[ST, H, S](pve dueTo AssertionFalse(a)))
            })(_ => Success())

          case _ =>
            if (config.disableSubsumption()) {
              val r =
                consume(σ, FullPerm(), a, pve, c)((σ1, _, _, c1) =>
                  Success())
              r && Q(σ, c)
            } else
              consume(σ, FullPerm(), a, pve, c)((σ1, _, _, c1) =>
                Q(σ, c1))
        }

      case call @ ast.Call(methodName, eArgs, lhs) =>
        val meth = c.program.findMethod(methodName)
        val pve = PreconditionInCallFalse(call)
          /* TODO: Used to be MethodCallFailed. Is also passed on to producing the postcondition, during which
           *       it is passed on to calls to eval, but it could also be thrown by produce itself (probably
           *       only while checking well-formedness).
           */

        evals(σ, eArgs, pve, c)((tArgs, c1) => {
          val insγ = Γ(meth.formalArgs.map(_.localVar).zip(tArgs))
          val pre = ast.utils.BigAnd(meth.pres)
          consume(σ \ insγ, FullPerm(), pre, pve, c1)((σ1, _, _, c3) => {
            val outs = meth.formalReturns.map(_.localVar)
            val outsγ = Γ(outs.map(v => (v, fresh(v))).toMap)
            val σ2 = σ1 \+ outsγ \ (g = σ.h)
            val post = ast.utils.BigAnd(meth.posts)
            produce(σ2, fresh, FullPerm(), post, pve, c3)((σ3, c4) => {
              val lhsγ = Γ(lhs.zip(outs)
                              .map(p => (p._1, σ3.γ(p._2))).toMap)
              Q(σ3 \ (g = σ.g, γ = σ.γ + lhsγ), c4)})})})

      case fold @ ast.Fold(ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm)) =>
        val predicate = c.program.findPredicate(predicateName)
        val pve = FoldFailed(fold)
        evals(σ, eArgs, pve, c)((tArgs, c1) =>
            eval(σ, ePerm, pve, c1)((tPerm, c2) =>
              decider.assert(σ, IsPositive(tPerm)){
                case true =>
                  predicateSupporter.fold(σ, predicate, tArgs, tPerm, pve, c2)(Q)
                case false =>
                  Failure[ST, H, S](pve dueTo NegativePermission(ePerm))}))

      case unfold @ ast.Unfold(ast.PredicateAccessPredicate(pa @ ast.PredicateAccess(eArgs, predicateName), ePerm)) =>
        val predicate = c.program.findPredicate(predicateName)
        val pve = UnfoldFailed(unfold)
        evals(σ, eArgs, pve, c)((tArgs, c1) =>
            eval(σ, ePerm, pve, c1)((tPerm, c2) =>
              decider.assert(σ, IsPositive(tPerm)){
                case true =>
                  predicateSupporter.unfold(σ, predicate, tArgs, tPerm, pve, c2, pa)(Q)
                case false =>
                  Failure[ST, H, S](pve dueTo NegativePermission(ePerm))}))

      case pckg @ ast.Package(wand) =>
        val pve = PackageFailed(pckg)
        val c0 = c.copy(reserveHeaps = H() :: σ.h :: Nil,
                        producedChunks = Nil,
                        consumedChunks = Nil :: Nil :: Nil)
        magicWandSupporter.packageWand(σ, wand, pve, c0)((chWand, c1) => {
          assert(c1.reserveHeaps.length == 3, s"Expected exactly 3 reserve heaps in the context, but found ${c1.reserveHeaps.length}")
          val h1 = c1.reserveHeaps(2)
          val c2 = c1.copy(exhaleExt = false,
                           reserveHeaps = Nil,
                           producedChunks = Nil,
                           consumedChunks = Stack(),
                           lhsHeap = None)
          Q(σ \ (h1 + chWand), c2)})

      case apply @ ast.Apply(e) =>
        /* TODO: Try to unify this code with that from DefaultConsumer/applying */

        val pve = ApplyFailed(apply)

        def QL(σ1: S, γ: ST, wand: ast.MagicWand, c1: C) = {
          /* The given heap is not σ.h, but rather the consumed portion only. However,
           * using σ.h should not be a problem as long as the heap that is used as
           * the given-heap while checking self-framingness of the wand is the heap
           * described by the left-hand side.
           */
          consume(σ1 \ γ, FullPerm(), wand.left, pve, c1)((σ2, _, _, c2) => {
            val c2a = c2.copy(lhsHeap = Some(σ1.h))
            produce(σ2, fresh, FullPerm(), wand.right, pve, c2a)((σ3, c3) => {
              val c4 = c3.copy(lhsHeap = None)
              Q(σ3 \ σ1.γ, c4)})})}

        e match {
          case wand: ast.MagicWand =>
            consume(σ, FullPerm(), wand, pve, c)((σ1, _, chs, c1) => {
              QL(σ1, σ1.γ, wand, c1)})

          case v: ast.LocalVariable =>
            val chWand = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            decider.getChunk[MagicWandChunk](σ, σ.h, chWand.id, c) match {
              case Some(ch) =>
                QL(σ \- ch, Γ(chWand.bindings), chWand.ghostFreeWand, c)
              case None =>
                Failure[ST, H, S](pve dueTo NamedMagicWandChunkNotFound(v))}

          case _ => sys.error(s"Expected a magic wand, but found node $e")}


      /* These cases should not occur when working with the CFG-representation of the program. */
      case   _: silver.ast.Goto
           | _: silver.ast.If
           | _: silver.ast.Label
           | _: silver.ast.Seqn
           | _: silver.ast.Constraining
           | _: silver.ast.While => sys.error(s"Unexpected statement (${stmt.getClass.getName}): $stmt")
    }

    executed
  }
}
