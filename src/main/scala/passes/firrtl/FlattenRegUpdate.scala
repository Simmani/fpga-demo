// See LICENSE for license details.

package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils._
import firrtl.WrappedExpression.weq

import scala.collection.mutable
import scala.collection.immutable

object FlattenRegUpdate {

  /** Mapping from references to the [[Expression]]s that drive them */
  type Netlist = mutable.HashMap[WrappedExpression, Expression]

  /** Set for mux conditions **/
  type Conditions = immutable.HashSet[WrappedExpression]

  /** Build a [[Netlist]] from a Module's connections and Nodes
    *
    * This assumes [[LowForm]]
    *
    * @param mod [[Module]] from which to build a [[Netlist]]
    * @return [[Netlist]] of the module's connections and nodes
    */
  def buildNetlist(mod: Module): Netlist = {
    val netlist = new Netlist()
    def onStmt(stmt: Statement): Statement = {
      stmt.map(onStmt) match {
        case Connect(_, lhs, rhs) =>
          netlist(lhs) = rhs
        case DefNode(_, nname, rhs) =>
          netlist(WRef(nname)) = rhs
        case _: IsInvalid => throwInternalError("Unexpected IsInvalid, should have been removed by now")
        case _ => // Do nothing
      }
      stmt
    }
    mod.map(onStmt)
    netlist
  }

  /** Flatten Register Updates
    *
    * Constructs nested mux trees (up to a certain arbitrary threshold) for register updates. This
    * can result in dead code that this function does NOT remove.
    *
    * @param mod [[Module]] to transform
    * @return [[Module]] with register updates flattened
    */
  def flattenReg(mod: Module): Module = {
    // We want to flatten Mux trees for reg updates into if-trees for
    // improved QoR for conditional updates.  However, unbounded recursion
    // would take exponential time, so don't redundantly flatten the same
    // Mux more than a bounded number of times, preserving linear runtime.
    // The threshold is empirical but ample.
    val flattenThreshold = 4
    val numTimesFlattened = mutable.HashMap[Mux, Int]()
    def canFlatten(m: Mux): Boolean = {
      val n = numTimesFlattened.getOrElse(m, 0)
      numTimesFlattened(m) = n + 1
      n < flattenThreshold
    }

    val regUpdates = mutable.ArrayBuffer.empty[Connect]
    val netlist = buildNetlist(mod)

    def constructRegUpdate(e: Expression, always: Conditions, unreachable: Conditions): Expression = {
      // Only walk netlist for nodes and wires, NOT registers or other state
      val expr = kind(e) match {
        case NodeKind | WireKind => netlist.getOrElse(e, e)
        case _ => e
      }
      expr match {
        case mux: Mux if canFlatten(mux) && always(mux.cond) =>
          // this conditions is always true by the previous muxes
          constructRegUpdate(mux.tval, always, unreachable)
        case mux: Mux if canFlatten(mux) && unreachable(mux.cond) =>
          // tval is unreachable by the previous muxes
          constructRegUpdate(mux.fval, always, unreachable)
        case mux: Mux if canFlatten(mux) =>
          val tvalx = constructRegUpdate(mux.tval, always + mux.cond, unreachable)
          val fvalx = constructRegUpdate(mux.fval, always, unreachable + mux.cond)
          if (weq(tvalx, fvalx)) tvalx // ConstProp?
          else mux.copy(tval = tvalx, fval = fvalx)
        // Return the original expression to end flattening
        case _ => e
      }
    }

    def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
      case reg @ DefRegister(_, rname, _,_, resetCond, _) =>
        assert(resetCond == Utils.zero, "Register reset should have already been made explicit!")
        val ref = WRef(reg)
        val update = Connect(NoInfo, ref,
          constructRegUpdate(netlist.getOrElse(ref, ref), new Conditions, new Conditions))
        regUpdates += update
        reg
      // Remove connections to Registers so we preserve LowFirrtl single-connection semantics
      case Connect(_, lhs, _) if kind(lhs) == RegKind => EmptyStmt
      case other => other
    }

    val bodyx = onStmt(mod.body)
    mod.copy(body = Block(bodyx +: regUpdates))
  }

}

/** Flatten register update
  *
  * This transform flattens register updates into a single expression on the rhs of connection to
  * the register
  */
// TODO Preserve source locators
class FlattenRegUpdate extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm

  def execute(state: CircuitState): CircuitState = {
    val modulesx = state.circuit.modules.map {
      case mod: Module => FlattenRegUpdate.flattenReg(mod)
      case ext: ExtModule => ext
    }
    state.copy(circuit = state.circuit.copy(modules = modulesx))
  }
}
