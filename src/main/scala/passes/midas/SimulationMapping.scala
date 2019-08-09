// See LICENSE for license details.

package midas
package passes

import java.io.{File, FileWriter, StringWriter}

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.passes.LowerTypes.loweredName
import firrtl.Utils.{splitRef, mergeRef, create_exps, gender, module_type}
import Utils._
import freechips.rocketchip.config.Parameters

import midas.core.SimWrapper
import dessert.passes.ToggleCounterWidgetAnnotation

private[passes] class SimulationMapping(
    io: Seq[chisel3.Data])
   (implicit param: Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  override def name = "[midas] Simulation Mapping"

  private def initStmt(target: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "target" && s.module == "TargetBox" => Block(Seq(
        s copy (module = target), // replace TargetBox with the actual target module
        IsInvalid(NoInfo, wref("target")) // FIXME: due to rocketchip
      ))
      case s => s map initStmt(target)
    }

  private def init(info: Info, target: String, main: String, tpe: Type, p: Parameters)(m: DefModule) = m match {
    case m: Module if m.name == main =>
      val body = initStmt(target)(m.body)
      val stmts = Seq(
        Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType))
      ) ++ (if (!p(EnableSnapshot)) Nil else {
        val ports = (m.ports map (p => p.name -> p)).toMap
        (create_exps(wsub(wref("target", tpe), "daisy")) map { e =>
          val io = WRef(loweredName(mergeRef(wref("io"), splitRef(e)._2)))
          ports(io.name).direction match {
            case Input  => Connect(NoInfo, e, io)
            case Output => Connect(NoInfo, io, e)
          }
        }) ++ Seq(
          Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))
        )
      }) ++ (if (!p(ToggleCounterSize).isDefined) Nil else {
        create_exps(wsub(wref("target", tpe), "toggleCntrs")).zipWithIndex map {
          case (e, i) => Connect(NoInfo, wsub(wref(s"CounterChannel_$i"), "io_enq_bits"), e)
        }
      })
      Some(m copy (info = info, body = Block(body +: stmts)))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def execute(state: CircuitState) = {
    val c = state.circuit
    val p = (state.annotations foldLeft param){
      case (p, ToggleCounterWidgetAnnotation(size)) =>
        p alterPartial { case ToggleCounterSize => Some(size) }
      case (p, _) => p
    }
    lazy val sim = new SimWrapper(io)(p)
    val c3circuit = chisel3.Driver.elaborate(() => sim)
    val chirrtl = Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = AnnotationSeq(c3circuit.annotations.toSeq map (_.toFirrtl))
    val circuit = renameMods((new LowFirrtlCompiler compile (
        CircuitState(chirrtl, ChirrtlForm, annos), Nil)).circuit, Namespace(c))
    val targetType = module_type((c.modules find (_.name == c.main)).get)
    val modules = c.modules ++ (circuit.modules flatMap
      init(c.info, c.main, circuit.main, targetType, p))
    state.copy(circuit = new WCircuit(circuit.info, modules, circuit.main, sim.io))
  }
}
