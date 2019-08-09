package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import midas.passes._
import mdf.macrolib.SRAMMacro

class MemInstance(
  info: Info,
  name: String,
  module: String,
  tpe: Type,
  val mem: Option[DefMemory] = None,
  val sram: Option[SRAMMacro] = None)
  extends WDefInstance(info, name, module, tpe)

case class MemModuleAnnotation(target: ModuleName, sram: SRAMMacro)
    extends SingleTargetAnnotation[ModuleName] {
  def duplicate(n: ModuleName) = this.copy(n)
}

class MacroToMemInstance extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def toMemInstance(memMods: Map[String, SRAMMacro])(s: Statement): Statement =
    s map toMemInstance(memMods) match {
      case s: MemInstance => s
      case s: WDefInstance => memMods get s.module match {
        case None => s
        case Some(sram) =>
          new MemInstance(s.info, s.name, s.module, s.tpe, sram = Some(sram))
      }
      case s => s
    }

  def run(c: Circuit, memMods: Map[String, SRAMMacro]) =
    c.copy(modules = c.modules map (_ map toMemInstance(memMods)))

  def execute(state: CircuitState) = {
    val memMods = (state.annotations collect {
      case MemModuleAnnotation(ModuleName(name, CircuitName(state.circuit.main)), mem) =>
        name -> mem
    }).toMap
    state.copy(circuit = run(state.circuit, memMods))
  }
}
