//See LICENSE for license details.

package dessert
package rocketchip

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port}
import firrtl.annotations._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames}
import freechips.rocketchip.system.TestGeneration
import freechips.rocketchip.config.Parameters
import dessert.passes.CustomTransforms.{
  passes => customPasses, annos => customAnnos}

trait HasGenerator extends GeneratorApp {
  def getGenerator(targetNames: ParsedInputNames, params: Parameters) = {
    implicit val valName = ValName(targetNames.topModuleClass)
    LazyModule(Class.forName(targetNames.fullTopModuleClass)
      .getConstructor(classOf[Parameters])
      .newInstance(params)
      .asInstanceOf[LazyModule]).module.asInstanceOf[RawModule]
  }

  override lazy val names: ParsedInputNames = {
    require(args.size >= 7, "Usage: sbt> run [midas | strober | replay] Platform " +
      "TargetDir TopModuleProjectName TopModuleName ConfigProjectName ConfigName ExtraArgs")
    ParsedInputNames(
      targetDir = args(2),
      topModuleProject = args(3),
      topModuleClass = args(4),
      configProject = args(5),
      configs = args(6))
  }

  lazy val targetParams = getConfig(names.fullConfigClasses).toInstance
  lazy val targetGenerator = getGenerator(names, targetParams)
}

object Generator extends HasGenerator with HasTestSuites {
  val longName = names.topModuleProject
  val targetDir = new java.io.File(names.targetDir)

  lazy val target = targetGenerator
  val c3circuit = chisel3.Driver.elaborate(() => target)
  val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(c3circuit))
  val targetAnnos = c3circuit.annotations map (_.toFirrtl)
  val portList = target.getPorts flatMap {
    case Port(id: DebugIO, _) => None
    case Port(id: AutoBundle, _) => None // What the hell is AutoBundle?
    case otherPort => Some(otherPort.id)
  }
  lazy val namespace = firrtl.Namespace(chirrtl)

  val rocketPasses = Seq(
    passes.AsyncResetRegPass,
    passes.PlusArgReaderPass) ++
    customPasses

  val platformParams = args(1) match {
    case "zynq" => new ZynqConfig
    case "f1"   => new F1Config
  }
  val extraArgs = (args drop 7).toSeq

  def annos = targetAnnos ++
    customAnnos(chirrtl.main, targetDir, extraArgs) ++
    Seq(strober.passes.StroberDontTouchModuleAnnotation(
      ModuleName("TLDebugModule", CircuitName(chirrtl.main))))

  override def addTestSuites = super.addTestSuites(params)
  def generateTestSuiteMakefrags(filename: String, tests: String, ext: String) {
    writeOutputFile(td, filename, (TestGeneration.generateMakefrag
      replace("tests", tests)
      replace(s"riscv-${tests}", "riscv-tests")
      replace("vpd", ext)))
  }

  generateFirrtl
  args.head match {
    case "midas" =>
      dessert.passes.Compiler(chirrtl, annos, portList, targetDir, rocketPasses)(platformParams)
      // generateTestSuiteMakefrags
      addTestSuites
      generateTestSuiteMakefrags(s"$longName.d", "tests", "vpd")
    case "strober" =>
      dessert.passes.Compiler(chirrtl, annos, portList, targetDir, rocketPasses)(
        platformParams alterPartial ({ case midas.EnableSnapshot => true }))
      // generateTestSuiteMakefrags
      addTestSuites
      generateTestSuiteMakefrags(s"$longName.d", "tests", "vpd")
    case "replay" =>
      dessert.replay.Compiler(chirrtl, annos, portList, targetDir, rocketPasses)
  }
}
