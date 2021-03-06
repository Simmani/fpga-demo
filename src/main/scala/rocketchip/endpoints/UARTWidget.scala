// See LICENSE for license details.

package dessert
package rocketchip
package endpoints

import midas.core._
import midas.widgets._

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.subsystem.PeripheryBusKey
import sifive.blocks.devices.uart.UARTPortIO

case object UARTDiv extends Field[Int]

class SimUART extends Endpoint {
  // This endpoint will connect "UARTPortIO"
  // UART is used for console IO
  def matchType(data: Data) = data match {
    case channel: UARTPortIO =>
      directionOf(channel.txd) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = new UARTWidget(p(UARTDiv))(p)
  override def widgetName = "UARTWidget"
}

class UARTWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new UARTPortIO))
  val dma = None
}

class UARTWidget(div: Int)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new UARTWidgetIO)

  // Buffer for stdout
  val txfifo = Module(new Queue(UInt(8.W), 16))
  // Buffer for stdin
  val rxfifo = Module(new Queue(UInt(8.W), 16))

  val initToken = RegInit(true.B)

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val stall = !txfifo.io.enq.ready && !initToken
  // firing condition
  // 1. tokens from the target are presented (io.hPort.toHost.hValid)
  // 2. the target is ready to accept tokens (io.hPort.fromHost.hReady)
  // 3. target reset tokens are presented (io.tReset.valid)
  // 4. TX buffers does not overflow (!txfifo.io.enq.ready)
  val fire = tFire && !stall
  val targetReset = fire & io.tReset.bits
  // Reset buffers with target reset
  rxfifo.reset := reset.toBool || targetReset
  txfifo.reset := reset.toBool || targetReset

  // tokens from the target are consumed with firing condition
  io.hPort.toHost.hReady := fire || initToken
  // tokens toward the target are generated with firing condition
  // FIXME: avoid two init tokens for tx
  io.hPort.fromHost.hValid := fire || RegNext(reset.toBool) || initToken
  // target reset tokens are consumed with firing condition
  io.tReset.ready := fire

  // Consume init token
  when(io.hPort.toHost.hValid) {
    initToken := false.B
  }

  // Connect "io.hPort.hBits.txd" to the stdout buffer
  val sTxIdle :: sTxWait :: sTxData :: sTxBreak :: Nil = Enum(4)
  val txState = RegInit(sTxIdle)
  val txData = Reg(UInt(8.W))
  // iterate through bits in byte to deserialize
  val (txDataIdx, txDataWrap) = Counter(txState === sTxData && fire, 8)
  // iterate using div to convert clock rate to baud
  val (txBaudCount, txBaudWrap) = Counter(txState === sTxWait && fire, div)
  val (txSlackCount, txSlackWrap) = Counter(txState === sTxIdle && target.txd === 0.U && fire, 4)

  switch(txState) {
    is(sTxIdle) {
      when(txSlackWrap) {
        txData  := 0.U
        txState := sTxWait
      }
    }
    is(sTxWait) {
      when(txBaudWrap) {
        txState := sTxData
      }
    }
    is(sTxData) {
      when(fire) {
        txData := txData | (target.txd << txDataIdx)
      }
      when(txDataWrap) {
        txState := Mux(target.txd === 1.U, sTxIdle, sTxBreak)
      }.elsewhen(fire) {
        txState := sTxWait
      }
    }
    is(sTxBreak) {
      when(target.txd === 1.U && fire) {
        txState := sTxIdle
      }
    }
  }

  txfifo.io.enq.bits  := txData
  txfifo.io.enq.valid := txDataWrap

  // Connect the stdin buffer to "io.hPort.hBits.rxd"
  val sRxIdle :: sRxStart :: sRxData :: Nil = Enum(3)
  val rxState = RegInit(sRxIdle)
  // iterate using div to convert clock rate to baud
  val (rxBaudCount, rxBaudWrap) = Counter(fire, div)
  // iterate through bits in byte to deserialize
  val (rxDataIdx, rxDataWrap) = Counter(rxState === sRxData && fire && rxBaudWrap, 8)

  target.rxd := 1.U
  switch(rxState) {
    is(sRxIdle) {
      target.rxd := 1.U
      when (rxBaudWrap && rxfifo.io.deq.valid) {
        rxState := sRxStart
      }
    }
    is(sRxStart) {
      target.rxd := 0.U
      when(rxBaudWrap) {
        rxState := sRxData
      }
    }
    is(sRxData) {
      target.rxd := (rxfifo.io.deq.bits >> rxDataIdx)(0)
      when(rxDataWrap && rxBaudWrap) {
        rxState := sRxIdle
      }
    }
  }
  rxfifo.io.deq.ready := (rxState === sRxData) && rxDataWrap && rxBaudWrap && fire

  // Generate memory mapped registers for buffers
  genROReg(txfifo.io.deq.bits, "out_bits")
  genROReg(txfifo.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(txfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  genWOReg(rxfifo.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(rxfifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(rxfifo.io.enq.ready, "in_ready")

  // generate memory mapped registers for control signals
  // The endpoint is "done" when tokens from the target are not available any more
  genROReg(!tFire, "done")
  genROReg(stall, "stall")

  genCRFile()
}
