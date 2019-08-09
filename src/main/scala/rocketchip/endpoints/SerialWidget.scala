// See LICENSE for license details.

package dessert
package rocketchip
package endpoints

import midas.core._
import midas.widgets._

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf

import freechips.rocketchip.config.Parameters
import testchipip.SerialIO


class SimSerialIO extends Endpoint {
  // This endpoint will connect "SerialIO" in top-level IO
  // SerialIO is a channel for riscv-fesvr
  def matchType(data: Data) = data match {
    case channel: SerialIO =>
      directionOf(channel.out.valid) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = new SerialWidget()(p)
  override def widgetName = "SerialWidget"
}

class SerialWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val w = testchipip.SerialAdapter.SERIAL_IF_WIDTH
  val hPort = Flipped(HostPort(new SerialIO(w)))
  val dma = None
}
class SerialWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new SerialWidgetIO)

  // Buffer for target input
  val inBuf  = Module(new Queue(UInt(io.w.W), 8))
  // Buffer for target output
  // val outBuf = Module(new Queue(UInt(io.w.W), 2)

  val target = io.hPort.hBits
  // firing condition
  // 1. tokens from the target are presented (io.hPort.toHost.hValid)
  // 2. the target is ready to accept tokens (io.hPort.fromHost.hReady)
  // 3. target reset tokens are presented (io.tReset.valid)
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val exit  = RegInit(false.B)
  val ready = RegInit(false.B)
  val stall = target.out.valid && !ready && !exit
  val fire = tFire && !stall
  val targetReset = fire & io.tReset.bits
  // reset buffers with target reset
  inBuf.reset  := reset.toBool || targetReset

  // tokens from the target are consumed with firing condition
  io.hPort.toHost.hReady := fire
  // tokens toward the target are generated with firing condition
  io.hPort.fromHost.hValid := fire || RegNext(reset.toBool)
  // target reset tokens are consumed with firing condition
  io.tReset.ready := fire

  // Connect serial input to target.in
  target.in.bits  := inBuf.io.deq.bits
  target.in.valid := inBuf.io.deq.valid
  // the data should be consumed with firing condition
  inBuf.io.deq.ready := target.in.ready && fire

  // Connect target.out to serial output
  target.out.ready := ready
  when(fire) {
    ready := false.B
  }

  // Generate memory mapped registers for buffers
  genWOReg(inBuf.io.enq.bits, "in_bits")
  genROReg(inBuf.io.enq.ready, "in_ready")
  Pulsify(genWORegInit(inBuf.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(target.out.bits, "out_bits")
  genROReg(target.out.valid, "out_valid")
  attach(ready, "out_ready", WriteOnly)
  attach(exit, "exit", WriteOnly)

  // generate memory mapped registers for control signals
  // The endpoint is "done" when tokens from the target are not available any more
  genROReg(!fire, "done")

  genCRFile()
}
