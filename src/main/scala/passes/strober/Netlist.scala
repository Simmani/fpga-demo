package strober
package passes

import firrtl.ir._
import firrtl.Mappers._

class Netlist {
  private val map = collection.mutable.HashMap[String, Expression]()
  def apply(key: String) = map(key)
  def get(key: String) = map get key
  def getOrElse(key: String, value: Expression) =
    map getOrElse (key, value)
  def update(key: String, value: Expression) {
    map(key) = value
  }
}

object Netlist {
  private def buildNetlist(netlist: Netlist)(s: Statement): Statement = {
    s match {
      case s: Conditionally =>
        s // skip
      case s: Connect =>
        netlist(s.loc.serialize) = s.expr
        s map buildNetlist(netlist)
      case s: DefNode =>
        netlist(s.name) = s.value
        s map buildNetlist(netlist)
      case s =>
        s map buildNetlist(netlist)
    }
  }

  def apply(m: DefModule) = {
    val netlist = new Netlist
    m map buildNetlist(netlist)
    netlist
  }
}
