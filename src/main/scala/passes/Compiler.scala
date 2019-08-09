// See LICENSE for license details.

package dessert
package passes

import chisel3.Data
import firrtl.{Transform, CircuitState, AnnotationSeq}
import firrtl.ir.Circuit
import firrtl.annotations.Annotation
import firrtl.CompilerUtils.getLoweringTransforms
import java.io.{File, FileWriter, Writer}
import freechips.rocketchip.config.Parameters
import mdf.macrolib.SRAMMacro
import mdf.macrolib.Utils.readMDFFromString

// Common Optimizations
object CodeOptimizations extends firrtl.SeqTransform {
  def inputForm = firrtl.LowForm
  def outputForm = firrtl.LowForm
  def transforms = Seq(
    firrtl.passes.InferTypes,
    firrtl.passes.CheckTypes,
    firrtl.passes.InferWidths,
    firrtl.passes.CheckWidths,
    dessert.passes.RemoveValidIf, // FIXME
    // new firrtl.transforms.ConstantPropagation,
    new dessert.passes.ConstantPropagation, // FIXME
    firrtl.passes.SplitExpressions,
    firrtl.passes.CommonSubexpressionElimination,
    new firrtl.transforms.DeadCodeElimination)
}

// Compilers to emit proper verilog
class Emitter extends firrtl.VerilogEmitter {
  override def transforms = Seq(
    new firrtl.transforms.ReplaceTruncatingArithmetic,
    new dessert.passes.FlattenRegUpdate, // FIXME
    new firrtl.transforms.DeadCodeElimination,
    firrtl.passes.VerilogModulusCleanup,
    firrtl.passes.VerilogRename,
    firrtl.passes.VerilogPrep)
}

class VerilogCompiler extends firrtl.Compiler {
  def emitter = new Emitter
  def transforms =
    Seq(new firrtl.IRToWorkingIR,
        new firrtl.ResolveAndCheck,
        new firrtl.HighFirrtlToMiddleFirrtl) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++
    Seq(new firrtl.LowFirrtlOptimization)
}

object Compiler {
  def apply(
      chirrtl: Circuit,
      annos: Seq[Annotation],
      io: Seq[Data],
      dir: File,
      customTransforms: Seq[Transform])
     (implicit p: Parameters): CircuitState = {
    dir.mkdirs
    val xforms: Seq[Transform] = customTransforms ++ Seq(
      CodeOptimizations,
      new midas.passes.MidasTransforms(dir, io)
    )
    val state = (new firrtl.LowFirrtlCompiler).compile(CircuitState(
      chirrtl, firrtl.ChirrtlForm, AnnotationSeq(annos)), xforms)
    val result = (new VerilogCompiler).compileAndEmit(
      CircuitState(state.circuit, firrtl.HighForm))
    val verilog = new FileWriter(new File(dir, "FPGATop.v"))
    verilog.write(result.getEmittedCircuit.value)
    annos foreach {
      case barstools.macros.MacroCompilerAnnotation(_, params) =>
        // Generate verilog for macros
        val json = params.lib getOrElse params.mem
        val str = scala.io.Source.fromFile(json).mkString
        val srams = readMDFFromString(str) map (_ collect { case x: SRAMMacro => x })
        val mods = (result.circuit.modules map (_.name)).toSet
        (srams map (_ filter (x => mods(x.name)))
               foreach (_ foreach dessert.replay.MacroEmitter(verilog)))
      case _ =>
    }
    verilog.close

    val defines = new FileWriter(new File(dir, "defines.vh"))
    p(midas.Platform) match {
      case midas.Zynq => // Do nothing...
      case midas.F1 =>
        val clock = p(midas.ClockFrequency)
        if (!Set(190, 175, 160, 90, 85, 75)(clock))
          throw new RuntimeException(s"${clock} MHz is not supported")
        defines.write(s"`define SELECTED_FIRESIM_CLOCK ${clock}\n")
    }
    defines.close

    result
  }

  // Unlike above, elaborates the target locally, before constructing the target IO Record.
  def apply[T <: chisel3.core.UserModule](
      w: => T,
      dir: File,
      customTransforms: Seq[Transform] = Nil,
      annos: Seq[Annotation] = Nil)
     (implicit p: Parameters): CircuitState = {
    lazy val target = w
    val circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
    val io = target.getPorts map (_.id)
    apply(chirrtl, annos ++ (circuit.annotations.toSeq map (_.toFirrtl)), io, dir, customTransforms)
  }
}
