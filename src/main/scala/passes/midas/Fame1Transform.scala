// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import Utils._
import dessert.passes.{MemInstance, MemModuleAnnotation}

private[passes] class Fame1Transform extends firrtl.passes.Pass {
  override def name = "[midas] Fame1 Transforms"
  type Enables = collection.mutable.HashMap[String, Boolean]
  type Statements = collection.mutable.ArrayBuffer[Statement]

  private val targetFirePort = Port(NoInfo, "targetFire", Input, BoolType)
  private val targetFire = wref(targetFirePort.name, targetFirePort.tpe)
  private val memMods = collection.mutable.HashSet[String]()
  private val enables = collection.mutable.HashMap[String, Enables]()

  private def collect(ens: Enables)(s: Statement): Statement = {
    s match {
      case s: DefMemory => ens ++=
        ((s.readers ++ s.writers ++ s.readwriters) map (
          memPortField(s, _, "en").serialize -> false)) ++
        (s.readwriters map (
          memPortField(s, _, "wmode").serialize -> false))
      case s: MemInstance =>
        memMods += s.module
        ens ++= (((s.sram, s.mem): @unchecked) match {
          case (Some(sram), None) =>
            sram.ports flatMap (port =>
              (port.writeEnable ++ port.chipEnable) map (en =>
                wsub(wref(s.name), en.name).serialize -> inv(en.polarity)))
          case (None, Some(mem)) =>
            ((mem.readers ++ mem.writers ++ mem.readwriters) map (
              memPortField(mem, _, "en").serialize -> false)) ++
            (mem.readwriters map (
              memPortField(mem, _, "wmode").serialize -> false))
        })
      case _ =>
    }
    s map collect(ens)
  }

  private def connect(ens: Enables,
                      stmts: Statements)
                      (s: Statement): Statement = s match {
    case s: MemInstance => s
    case s: WDefInstance =>
      Block(Seq(s,
        Connect(NoInfo, wsub(wref(s.name), "targetFire"), targetFire)
      ))
    case s: DefRegister =>
      val regRef = wref(s.name, s.tpe)
      stmts += Conditionally(NoInfo, targetFire, EmptyStmt, Connect(NoInfo, regRef, regRef))
      s.copy(reset = and(s.reset, targetFire))
    case s: Print =>
      s.copy(en = and(s.en, targetFire))
    case s: Stop =>
      s.copy(en = and(s.en, targetFire))
    case s: Connect => s.loc match {
      case e: WSubField => ens get e.serialize match {
        case None => s
        case Some(false) =>
          s.copy(expr = and(s.expr, targetFire))
        case Some(true) => // inverted port
          s.copy(expr = or(s.expr, not(targetFire)))
      }
      case _ => s
    }
    case s => s map connect(ens, stmts)
  }

  private def collect(m: DefModule): DefModule = {
    enables(m.name) = new Enables
    m map collect(enables(m.name))
  }

  private def transform(m: DefModule): DefModule = {
    val stmts = new Statements
    if (memMods(m.name)) m
    else m map connect(enables(m.name), stmts) match {
      case m: Module =>
        m.copy(ports = m.ports :+ targetFirePort,
               body  = Block(m.body +: stmts))
      case m: ExtModule => m
    }
  }

  def run(c: Circuit) =
    c.copy(modules = c.modules map collect map transform)
}
