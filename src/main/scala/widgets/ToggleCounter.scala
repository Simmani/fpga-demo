package dessert
package widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets._

class ToggleCounterWidgetIO(size: Int)(implicit p: Parameters) extends WidgetIO {
  val counters = Flipped(Decoupled(Vec(size, UInt(32.W))))
  val tReset = Flipped(Decoupled(Bool()))
}

class ToggleCounterWidget(size: Int)(implicit p: Parameters) extends Widget {
  val io = IO(new ToggleCounterWidgetIO(size))

  val sRun :: sBaud :: Nil = Enum(2)
  val state = RegInit(sRun)
  val baudRate = RegInit(1000000.U(24.W))
  val baudRateAddr = attach(baudRate, "baud_rate", WriteOnly)
  val count = RegInit(((1 << 24) - 1).U(24.W))
  val baud = count === baudRate
  val baudAddr = attach(RegNext(state === sBaud), "buad", ReadOnly)
  val read = Pulsify(RegInit(false.B), 1)
  val readAddr = attach(read, "read", WriteOnly)
  val tokenPushed = io.tReset.valid && io.counters.valid
  val fire = tokenPushed && state === sRun && !baud
  val tReset = io.tReset.bits
  val (pipes, cntrAddrs) = (io.counters.bits.zipWithIndex map { case (counter, i) =>
    val pipe = Module(new Queue(chiselTypeOf(counter), 1, pipe=true))
    val reg = RegEnable(counter, fire)
    pipe suggestName s"ToggleCntrQueue_$i"
    reg suggestName s"toggle_cntr_reg_$i"
    pipe.reset := reset.toBool || fire && tReset
    pipe.io.enq.bits := Mux(read, reg, counter)
    pipe.io.enq.valid := tokenPushed && state === sRun && baud || read
    (pipe.io.enq.ready, attachDecoupledSource(pipe.io.deq, s"toggle_cntr_$i"))
  }).unzip

  io.tReset.ready := fire
  io.counters.ready := fire

  switch(state) {
    is(sRun) {
      when(fire && !tReset) {
        count := count + 1.U
      }.elsewhen(tokenPushed && baud) {
        state := sBaud
      }
    }
    is(sBaud) {
      when((pipes foldLeft true.B)(_ && _)) {
        count := 0.U
        state := sRun
      }
    }
  }

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    sb.append(genComment("Counter Widget"))
    sb.append(genMacro("ENABLE_COUNTERS"))
    sb.append(genMacro("COUNTER_READ", UInt32(base + readAddr)))
    sb.append(genMacro("COUNTER_BAUD", UInt32(base + baudAddr)))
    sb.append(genMacro("COUNTER_BAUD_RATE", UInt32(base + baudRateAddr)))
    sb.append(genMacro("NUM_TOGGLE_COUNTERS", UInt32(cntrAddrs.size)))
    sb.append(genArray("TOGGLE_COUNTERS", cntrAddrs map (x => UInt32(base + x))))
  }
}
