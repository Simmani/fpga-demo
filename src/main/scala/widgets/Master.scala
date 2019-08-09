// See LICENSE for license details.

package midas
package widgets

// from rocketchip
import junctions._

import chisel3._
import chisel3.util.{Decoupled, Counter, log2Up}
import freechips.rocketchip.config.Parameters

class EmulationMasterIO(implicit p: Parameters) extends WidgetIO {
  val simReset = Output(Bool())
  val done = Input(Bool())
  val step = Decoupled(UInt(p(CtrlNastiKey).dataBits.W))
}

object Pulsify {
  def apply(in: Bool, pulseLength: Int): Bool = {
    require(pulseLength > 0)
    if (pulseLength > 1) {
      val count = Counter(pulseLength)
      when(in){count.inc()}
      when(count.value === (pulseLength-1).U) {
        in := false.B
        count.value := 0.U
      }
    } else {
      when(in) { in := false.B }
    }
    in
  }
}

class EmulationMaster(implicit p: Parameters) extends Widget {
  val io = IO(new EmulationMasterIO)
  genAndAttachQueue(io.step, "STEP")
  genRORegInit(io.done && ~io.simReset, "DONE", false.B)
  Pulsify(genWORegInit(io.simReset, "SIM_RESET", false.B), pulseLength = 4)

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    super.genHeader(base, sb)
    sb.append(genMacro("CHANNEL_SIZE", UInt32(log2Up(p(midas.core.ChannelWidth)/8))))
  }
}
