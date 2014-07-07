/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package semper
package silicon
package interfaces.reporting

import interfaces.state.{Heap, Store, State}

trait Context[C <: Context[C, ST, H, S], ST <: Store[ST], H <: Heap[H], S <: State[ST, H, S]]
    extends BranchKeeper[C, ST, H, S]
