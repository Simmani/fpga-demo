// See LICENSE for license details.

package midas
package passes

import midas.core._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import Utils._
import java.io.{File, FileWriter}

private class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO) extends Circuit(info, modules, main)

case class MidasMacroAnnotation(json: java.io.File) extends NoTargetAnnotation

class MidasTransforms(
    dir: File,
    io: Seq[chisel3.Data])
    (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  private class Transforms(main: String) extends SeqTransform {
    def inputForm = LowForm
    def outputForm = LowForm
    def transforms = Seq(
      new dessert.passes.SVFBackAnnotationPass,
      new dessert.passes.ReplaceMultiPortRAMs,
      new dessert.passes.MacroToMemInstance,
      new Fame1Transform,
      new strober.passes.StroberTransforms(dir),
      new dessert.passes.AddToggleCounters(dir, true),
      new SimulationMapping(io),
      new PlatformMapping(main, dir))
  }

  def execute(state: CircuitState) = {
    val annos: Seq[Annotation] =
      (state.annotations foldLeft Seq[Annotation]()){
        case (res, MidasMacroAnnotation(json)) =>
          val str = scala.io.Source.fromFile(json).mkString
          mdf.macrolib.Utils.readMDFFromString(str) match {
            case None => res
            case Some(macros) => res ++ (macros collect {
              case x: mdf.macrolib.SRAMMacro =>
                dessert.passes.MemModuleAnnotation(
                  ModuleName(x.name, CircuitName(state.circuit.main)), x)
            })
          }
        case (res, _) => res
      }

    new Transforms(state.circuit.main) execute state.copy(
      annotations=AnnotationSeq(state.annotations.toSeq ++ annos))
  }
}
