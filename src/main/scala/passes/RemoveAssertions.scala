package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._

object RemoveAssertions extends firrtl.passes.Pass {
  def onStmt(s: Statement): Statement =
    s map onStmt match {
      case s: Stop => EmptyStmt
      case s: Print if s.args.isEmpty => EmptyStmt
      case s => s
    }

  def run(c: Circuit) =
    c.copy(modules = c.modules map (_ map onStmt))
}
