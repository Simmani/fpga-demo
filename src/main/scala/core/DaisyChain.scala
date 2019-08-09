// See LICENSE for license details.

package strober
package core

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}

case object DaisyWidth extends Field[Int]
case object DataWidth extends Field[Int]
case object MemWidth extends Field[Int]
case object MemDepth extends Field[Int]
case object MemNum extends Field[Int]
case object SeqRead extends Field[Boolean]

object ChainType extends Enumeration { val Regs, Mems, RegFile, SRAM, Trace = Value }

// Declare daisy pins
class DaisyData(val daisywidth: Int) extends Bundle {
  val in = Flipped(Decoupled(UInt(daisywidth.W)))
  val out = Decoupled(UInt(daisywidth.W))
  val load = Input(Bool())
  val copy = Input(Bool())
}

class DaisyBundle(val daisyWidth: Int) extends Bundle {
  val regs    = new DaisyData(daisyWidth)
  val mems    = new DaisyData(daisyWidth)
  val regfile = new DaisyData(daisyWidth)
  val sram    = new DaisyData(daisyWidth)
  val trace   = new DaisyData(daisyWidth)
  def apply(t: ChainType.Value) = t match {
    case ChainType.Regs    => regs
    case ChainType.Mems    => mems
    case ChainType.RegFile => regfile
    case ChainType.SRAM    => sram
    case ChainType.Trace   => trace
  }
}

class DaisyBox(implicit p: Parameters) extends Module {
  val io = IO(new DaisyBundle(p(DaisyWidth)))
  io := DontCare
}

// Common structures for daisy chains
trait DaisyChainParams {
  implicit val p: Parameters
  val dataWidth = p(DataWidth)
  val daisyWidth = p(DaisyWidth)
  val daisyLen = (dataWidth-1)/daisyWidth + 1
}

abstract class DaisyChainBundle(implicit val p: Parameters) 
    extends Bundle with DaisyChainParams

class DataIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val in   = Flipped(Decoupled(Input(UInt(daisyWidth.W))))
  val out  = Decoupled(Input(UInt(daisyWidth.W)))
  val data = Vec(daisyLen, Input(UInt(daisyWidth.W)))
  val load = Valid(Vec(daisyLen, UInt(daisyWidth.W)))
}

abstract class DaisyChainModule(implicit val p: Parameters) extends Module with DaisyChainParams

// Define state daisy chains
class RegChainIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val stall = Input(Bool())
  val load  = Input(Bool())
  val copy  = Input(Bool())
  val dataIo = new DataIO
}

class RegChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new RegChainIO)
  val regs = Reg(Vec(daisyLen, UInt(daisyWidth.W)))

  (0 until daisyLen).reverse foreach { i =>
    when(io.copy) {
      regs(i) := io.dataIo.data(i)
    }.elsewhen(io.dataIo.out.fire()) {
      regs(i) := (if (i == 0) io.dataIo.in.bits else regs(i-1))
    }
  }

  io.dataIo.out.bits := regs.last
  io.dataIo.out.valid := true.B
  io.dataIo.in.ready := io.dataIo.out.ready
  io.dataIo.load.bits <> regs
  io.dataIo.load.valid := io.load
}

// Define sram daisy chains
trait SRAMChainParameters {
  implicit val p: Parameters
  val seqRead = p(SeqRead)
  val n = p(MemNum)
  val w = log2Ceil(p(MemDepth)) max 1
}

class AddrIO(implicit val p: Parameters)
    extends Bundle with SRAMChainParameters {
  val in = Flipped(Valid(UInt(w.W)))
  val out = Valid(UInt(w.W))
}

class SRAMChainIO(implicit p: Parameters)
    extends RegChainIO with SRAMChainParameters {
  val addrIo = Vec(n, new AddrIO)
}

class SRAMChain(implicit p: Parameters)
    extends DaisyChainModule with SRAMChainParameters {
  val io = IO(new SRAMChainIO)

  val s_INIT :: s_ADDRGEN :: s_MEMREAD :: Nil = Enum(3)
  val addrState = RegInit(s_INIT)
  val addrOut = RegInit(0.U(w.W))
  val addrIns = Seq.fill(n)(Reg(UInt(w.W)))
  (io.addrIo zip addrIns) foreach { case (addrIo, addrIn) =>
    if (seqRead) {
      addrIo.out.valid := addrState =/= s_INIT
      addrIo.out.bits  := Mux(addrState === s_ADDRGEN || io.load, addrOut, addrIn)
      when(addrIo.in.valid) { addrIn := addrIo.in.bits }
    } else {
      addrIo.out.valid := addrState === s_MEMREAD
      addrIo.out.bits  := addrOut
    }
  }

  // SRAM control
  when(!io.stall) {
    addrState := s_INIT
    addrOut   := 0.U
  }.otherwise {
    switch(addrState) {
      is(s_INIT) {
        when(io.copy) {
          addrState := (if (seqRead) s_ADDRGEN else s_MEMREAD)
        }
      }
      is(s_ADDRGEN) {
        addrState := s_MEMREAD
      }
      is(s_MEMREAD) {
        addrState := s_INIT
        addrOut   := addrOut + 1.U
      }
    }
  }

  // SRAM datapath
  val regs = Reg(Vec(daisyLen, UInt(daisyWidth.W)))

  (0 until daisyLen).reverse foreach { i =>
    when(addrState === s_MEMREAD) {
      regs(i) := io.dataIo.data(i)
    }.elsewhen(io.dataIo.out.fire()) {
      regs(i) := (if (i == 0) io.dataIo.in.bits else regs(i-1))
    }
  }

  io.dataIo.out.bits := regs.last
  io.dataIo.out.valid := addrState === s_INIT
  io.dataIo.in.ready := io.dataIo.out.ready
  io.dataIo.load.bits <> regs
  io.dataIo.load.valid := io.load
}
