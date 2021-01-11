package rpi.learner

import rpi.{Names, Settings}
import rpi.inference._
import rpi.util.{Collections, Expressions, SetMap}
import viper.silver.ast

import java.util.concurrent.atomic.AtomicInteger

/**
  * A template for a specification that needs to be inferred.
  *
  * @param specification The specification for which this is the template.
  * @param body          The body representing the resources allowed by this template.
  */
case class Template(specification: Specification, body: TemplateExpression) {
  /**
    * Returns the name identifying the specification.
    *
    * @return The name.
    */
  def name: String = specification.name

  /**
    * Returns the parameters for the specifications.
    *
    * @return The parameters.
    */
  def parameters: Seq[ast.LocalVarDecl] = specification.parameters

  /**
    * Returns the atomic predicates that may be used for the specification.
    *
    * @return The atomic predicates.
    */
  def atoms: Seq[ast.Exp] = specification.atoms

  override def toString: String =
    s"$specification = $body"
}

/**
  * A template expression.
  */
sealed trait TemplateExpression

/**
  * A template expression representing a conjunction of some conjuncts.
  *
  * @param conjuncts The conjuncts.
  */
case class Conjunction(conjuncts: Seq[TemplateExpression]) extends TemplateExpression {
  override def toString: String = conjuncts.mkString("(", " * ", ")")
}

/**
  * A template expression representing a resource access guarded by some condition.
  *
  * @param guardId The id of the guard condition.
  * @param access The resource access.
  */
case class Resource(guardId: Int, access: ast.LocationAccess) extends TemplateExpression {
  override def toString: String = s"(phi_$guardId -> $access)"
}

/**
  * A template expression representing a choice.
  *
  * @param choiceId The id of the choice.
  * @param options  The available options.
  * @param body The template expression for which the choice has to be made.
  */
case class Choice(choiceId: Int, options: Seq[ast.Exp], body: TemplateExpression) extends TemplateExpression {
  override def toString: String = s"(choose t_$choiceId from {${options.mkString(", ")}} in $body)"
}

/**
  * A truncated template expression.
  *
  * @param condition The truncation condition.
  * @param body The truncated template expression.
  */
case class Truncation(condition: ast.Exp, body: TemplateExpression) extends TemplateExpression {
  override def toString: String = s"($condition -> $body)"
}

/**
  * A helper class used to compute templates.
  *
  * @param learner The pointer to the learner.
  */
class TemplateGenerator(learner: Learner) {
  /**
    * The pointer to the inference.
    */
  private val inference = learner.inference

  private val recursiveSpecification =
    if (Settings.useRecursion) {
      // TODO: Potentially exclude second parameter from atoms.
      val specification = {
        val names = if (Settings.useSegments) Seq("x", "y") else Seq("x")
        val parameters = names.map { name => ast.LocalVarDecl(name, ast.Ref)() }
        val variables = parameters.take(1).map { parameter => parameter.localVar }
        val atoms = inference.instantiateAtoms(variables)
        Specification(Names.recursive, parameters, atoms)
      }

      learner.addSpecification(specification)
      Some(specification)
    } else None

  /**
    * Computes templates for the given examples.
    *
    * @param examples The examples.
    * @return The templates.
    */
  def generate(examples: Seq[Example]): Map[String, Template] = {
    // used to generate unique ids for guards
    implicit val id: AtomicInteger = new AtomicInteger

    // map from specifications to accesses
    val map = {
      // collect records from examples
      val records = examples.flatMap {
        case PositiveExample(records) => records
        case NegativeExample(record) => Seq(record)
        case ImplicationExample(left, right) => left +: right
      }
      // build map
      records.foldLeft(Map.empty[String, Set[ast.LocationAccess]]) {
        case (result, record) =>
          val name = record.specification.name
          SetMap.addAll(result, name, record.locations)
      }
    }

    // compute templates for specifications and structure for recursive predicate
    val (templates, structure) = map.foldLeft((Map.empty[String, Template], bottom)) {
      case ((result, global), (name, accesses)) =>
        // compute local structure
        val (instances, local) = {
          val fields = accesses.collect { case field: ast.FieldAccess => field }
          computeStructure(fields)
        }
        // compute template
        val template = {
          val specification = inference.getSpecification(name)
          createTemplate(specification, accesses ++ instances)
        }
        // add template and update global structure
        (result.updated(name, template), global.join(local))
    }

    // compute template for recursive predicate
    val recursive = recursiveSpecification
      .map { specification =>
        // collect accesses
        val accesses: Set[ast.LocationAccess] = {
          // get fields and recursions
          val fields = structure.fields
          val recursions = structure.recursions
          // make sure there is a way to frame arguments of recursions
          val framed = recursions.flatMap { recursion =>
            recursion.args.collect { case field: ast.FieldAccess => field }
          }
          fields ++ framed ++ recursions
        }
        // create template
        createTemplate(specification, accesses)
      }

    // return templates
    recursive.fold(templates) { template =>
      templates.updated(template.name, template)
    }
  }

  /**
    * Creates a template corresponding to the given specification with the given resources.
    *
    * @param specification The specification.
    * @param resources     The resources.
    * @param id            The atomic integer used to generate unique ids.
    * @return The template.
    */
  def createTemplate(specification: Specification, resources: Set[ast.LocationAccess])(implicit id: AtomicInteger): Template = {
    val sequence = resources.toSeq

    // get all field accesses, then filter and sort them
    val fields = sequence
      .collect { case field: ast.FieldAccess =>
        val length = Expressions.length(field)
        (length, field)
      }
      .filter { case (length, _) => length <= Settings.maxLength }
      .sortWith { case ((first, _), (second, _)) => first < second }
      .map { case (_, field) =>
        val guardId = id.getAndIncrement()
        Resource(guardId, field)
      }

    // get all predicate accesses
    val predicates =
      if (Settings.useSegments) sequence
        // group by first argument
        .foldLeft(Map.empty[ast.Exp, Set[ast.Exp]]) {
          case (result, access) => access match {
            case ast.PredicateAccess(arguments, name) =>
              assert(name == Names.recursive)
              assert(arguments.length == 2)
              val Seq(first, second) = arguments
              SetMap.add(result, first, second)
            case _ => result
          }
        }
        .map { case (first, options) =>
          // TODO: Optimize if there is only one option.
          // create ids
          val guardId = id.getAndIncrement()
          val choiceId = id.getAndIncrement()
          // create predicate with choice placeholder
          val predicate = {
            val second = ast.LocalVar(s"t_$choiceId", ast.Ref)()
            val arguments = Seq(first, second)
            ast.PredicateAccess(arguments, Names.recursive)()
          }
          // create resource and choice
          val resource = Resource(guardId, predicate)
          Choice(choiceId, options.toSeq, resource)
        }
        .toSeq
      else sequence
        .collect { case predicate: ast.PredicateAccess =>
          // create resource
          val guardId = id.getAndIncrement()
          Resource(guardId, predicate)
        }

    // create template
    // TODO: Add truncation.
    val body = Conjunction(fields ++ predicates)
    Template(specification, body)
  }

  /**
    * Returns a structure of a recursive template that captures possible recursions that could describe the given field
    * accesses.
    *
    * @param accesses The field accesses.
    * @return The structure.
    */
  private def computeStructure(accesses: Set[ast.FieldAccess]): (Set[ast.PredicateAccess], Structure) = {
    if (Settings.useRecursion)
      accesses
        .groupBy { access => access.field }
        .flatMap { case (field, group) =>
          // the resource to add to the structure in case there is a potential recursion
          lazy val resource = {
            val variable = ast.LocalVar("x", ast.Ref)()
            ast.FieldAccess(variable, field)()
          }
          // iterate over all pairs of receivers in order to detect potential recursions
          val receivers = group.map { access => toSeq(access.rcv) }
          Collections
            .pairs(receivers)
            .flatMap { case (path1, path2) =>
              commonPrefix(path1, path2) match {
                case (prefix, suffix1, suffix2) if suffix1.isEmpty || suffix2.isEmpty =>
                  val instance = createInstance(prefix)
                  val recursion = createRecursion(suffix1 ++ suffix2)
                  val structure = Structure(Set(resource), Set(recursion))
                  Some(instance, structure)
                case _ => None
              }
            }
        }
        .foldLeft((Set.empty[ast.PredicateAccess], bottom)) {
          case ((instances, global), (instance, local)) =>
            (instances + instance, global.join(local))
        }
    else (Set.empty, bottom)
  }

  /**
    * Creates an instance of the recursive predicate starting at the given access path.
    *
    * @param path The access path.
    * @return The instance.
    */
  private def createInstance(path: Seq[String]): ast.PredicateAccess = {
    val arguments =
      if (Settings.useSegments) Seq(fromSeq(path), ast.NullLit()())
      else Seq(fromSeq(path))
    ast.PredicateAccess(arguments, Names.recursive)()
  }

  private def createRecursion(path: Seq[String]): ast.PredicateAccess = {
    val variable +: others = recursiveSpecification.get.variables
    val first = fromSeq(variable.name +: path)
    ast.PredicateAccess(first +: others, Names.recursive)()
  }

  private def toSeq(path: ast.Exp): Seq[String] =
    path match {
      case ast.LocalVar(name, _) => Seq(name)
      case ast.FieldAccess(receiver, field) => toSeq(receiver) :+ field.name
    }

  private def fromSeq(path: Seq[String]): ast.Exp = {
    val variable: ast.Exp = ast.LocalVar(path.head, ast.Ref)()
    path.tail.foldLeft(variable) {
      case (result, name) =>
        val field = ast.Field(name, ast.Ref)()
        ast.FieldAccess(result, field)()
    }
  }

  /**
    * Fins the common prefix of the two given paths.
    *
    * @param path1 The first path.
    * @param path2 The second path.
    * @return The common prefix and the left over suffixes.
    */
  private def commonPrefix(path1: Seq[String], path2: Seq[String]): (Seq[String], Seq[String], Seq[String]) =
    (path1, path2) match {
      case (head1 +: tail1, head2 +: tail2) if head1 == head2 =>
        val (prefix, suffix1, suffix2) = commonPrefix(tail1, tail2)
        (head1 +: prefix, suffix1, suffix2)
      case _ => (Seq.empty, path1, path2)
    }

  /**
    * Returns the empty structure.
    *
    * @return The empty structure.
    */
  private def bottom: Structure =
    Structure(Set.empty, Set.empty)

  /**
    * A helper class used to represent the structure of a recursive predicate.
    *
    * @param fields     The field accesses.
    * @param recursions The recursive accesses.
    */
  private case class Structure(fields: Set[ast.FieldAccess], recursions: Set[ast.PredicateAccess]) {
    /**
      * Returns a structure that approximates both, this structure and the given other structure.
      *
      * @param other The other structure.
      * @return The approximation of this and the given other structure.
      */
    def join(other: Structure): Structure =
      Structure(fields ++ other.fields, recursions ++ other.recursions)
  }

}