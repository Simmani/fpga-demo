// See LICENSE for license details.

package dessert
package widgets

import chisel3._
import chisel3.util._
import junctions._
import midas.widgets._
import freechips.rocketchip.config.{Parameters, Field}

case class LLCParams(nWays: Int = 8, nSets: Int = 4096, bBytes: Int = 128)
case object LLCModelKey extends Field[Option[LLCParams]]

class LLCModelConfigBundle(key: LLCParams) extends Bundle {
  val wayBits = UInt(log2Ceil(log2Ceil(key.nWays) + 1).W)
  val blkBits = UInt(log2Ceil(log2Ceil(key.bBytes) + 1).W)
  val setBits = UInt((log2Ceil(log2Ceil(key.nSets) + 1) + 1).W) // + overflow bit
  override def cloneType = new LLCModelConfigBundle(key).asInstanceOf[this.type]
}

class LLCModel(key: LLCParams)(implicit p: Parameters) extends NastiModule {
  import Chisel._
  val io = IO(new Bundle {
    val config = Input(new LLCModelConfigBundle(key))
    val rlen  = Flipped(Decoupled(UInt(nastiXLenBits.W)))
    val raddr = Flipped(Decoupled(UInt(nastiXAddrBits.W)))
    val waddr = Flipped(Decoupled(UInt(nastiXAddrBits.W)))
    val wlast = Flipped(Decoupled(Bool()))
    val resp = Decoupled(new Bundle {
      val addr = UInt(nastiXAddrBits.W)
      val hit  = Bool()
      val wr   = Bool()
      val wb   = Bool()
    })
    val idle = Output(Bool())
  })
  println("[Last Level Cache] ")
  println(s" - # Ways <= ${key.nWays}")
  println(s" - # Sets <= ${key.nSets}")
  println(s" - Block Size <= ${key.bBytes} B")
  println(s" -> Cache Size <= %d KiB".format(
    (key.nWays * key.nSets * key.bBytes) / 1024))

  val sIdle :: sTagRead :: sTagValid :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val rlenQueue  = Queue(io.rlen,  8, flow=true)
  val raddrQueue = Queue(io.raddr, 8, flow=true)
  val waddrQueue = Queue(io.waddr, flow=true)
  val wlastQueue = Queue(io.wlast, flow=true)

  val wayBits = io.config.wayBits
  val blkBits = io.config.blkBits
  val setBits = io.config.setBits
  val tagBits = nastiXAddrBits.U - (blkBits + setBits)
  val wayMask = Wire(UInt(log2Ceil(key.nWays).W))
  val idxMask = Wire(UInt(log2Ceil(key.nSets).W))
  val tagMask = Wire(UInt((nastiXAddrBits - 8).W))
  wayMask := (1.U(1.W) << wayBits) - 1.U
  idxMask := (1.U(1.W) << setBits) - 1.U
  tagMask := (1.U(1.W) << tagBits) - 1.U

  val wr = waddrQueue.valid && wlastQueue.valid
  val rd = raddrQueue.valid && rlenQueue.valid
  val addrValid = wr || rd
  val addr = Mux(wr, waddrQueue.bits, raddrQueue.bits)
  val idx = (addr >> blkBits) & idxMask
  val tag = (addr >> (blkBits + setBits)) & tagMask

  val ren = state === sIdle && addrValid
  val addrReg = RegEnable(addr, ren)
  val idxReg  = RegEnable(idx,  ren)
  val tagReg  = RegEnable(tag,  ren)
  val wrReg   = RegEnable(wr,   ren)

  val vset = Seq.fill(key.nWays)(
    RegInit(VecInit(Seq.fill(key.nSets >> 6)(0.U(64.W)))))
  val dset = Seq.fill(key.nWays)(
    RegInit(VecInit(Seq.fill(key.nSets >> 6)(0.U(64.W)))))
  val tags = Seq.fill(key.nWays)(
    SyncReadMem(key.nSets, UInt((nastiXAddrBits - 8).W)))
  val tagReads = tags map (_.read(idx, ren) & tagMask)
  val matches = tagReads map (_ === tagReg)
  val matchWay = VecInit(matches) indexWhere ((x: Bool) => x)
  val valids = vset.zipWithIndex map { case (v, way) =>
    (v(idxReg >> 6) >> idxReg(5, 0))(0) || (wayMask < way.U) }

  io.resp.bits.hit  := (matches reduce (_ || _)) && VecInit(valids)(matchWay)
  io.resp.bits.addr := addrReg
  io.resp.bits.wr   := wrReg
  io.resp.bits.wb   := false.B
  io.resp.valid     := state === sTagValid
  io.idle           := state === sIdle && !addrValid || io.resp.valid

  val rCntr = RegInit(0.U(nastiXLenBits.W))
  val rLast = rCntr === rlenQueue.bits
  when(state === sTagRead && !wrReg) {
    rCntr := Mux(rLast, 0.U, rCntr + 1.U)
  }

  val wen = !ren && io.resp.fire() && !io.resp.bits.hit && (wrReg || rLast)
  val replace = wen && (valids reduce (_ && _))
  val invalidWay = VecInit(valids) indexWhere ((x: Bool) => !x)
  val evictedWay = LFSR16(replace) & wayMask
  val writeWay = Mux(replace, evictedWay, invalidWay)

  when(wen) {
    (0 until key.nWays) foreach { way =>
      when(writeWay === way.U) {
        val v = vset(way)(idxReg >> 6)
        val d = dset(way)(idxReg >> 6)
        v := v | (1.U(1.W) << idxReg(5, 0))
        d := d | (wrReg << idxReg(5, 0))
        tags(way).write(idxReg, tagReg)
        io.resp.bits.wb := replace && (d >> idxReg(5, 0))(0)
      }
    }
  }

  rlenQueue.ready  := state === sTagRead && !wrReg && rLast
  raddrQueue.ready := state === sTagRead && !wrReg && rLast
  waddrQueue.ready := state === sTagRead && wrReg
  wlastQueue.ready := state === sTagRead && wrReg
  switch(state) {
    is(sIdle) {
      when(addrValid) {
        state := sTagRead
      }
    }
    is(sTagRead) {
      state := sTagValid
    }
    is(sTagValid) {
      when(io.resp.ready) {
        state := sIdle
      }
    }
  }
}

// DRAM Params
case object BankNumBits extends Field[Int]
case object BankBitOffset extends Field[Int]
case object RowNumBits extends Field[Int]
case object RowBitOffset extends Field[Int]

class SimpleLatencyPipe(implicit val p: Parameters) extends NastiWidgetBase {
  // Timing Model
  val rCycles = Module(new Queue(UInt(64.W), 64))
  val wCycles = Module(new Queue(UInt(64.W), 8))
  val rCycleValid = Wire(Bool())
  val wCycleValid = Wire(Bool())
  val rCycleReady = Wire(Bool())
  val wCycleReady = Wire(Bool())
  val llcIdle = Wire(Bool())

  // Control Registers
  val memLatency = RegInit(32.U(32.W))
  val llcLatency = RegInit(8.U(32.W))
  // LLC Size: 256 KiB by default
  val wayBits = RegInit(2.U(32.W)) // # Ways = 4
  val setBits = RegInit(10.U(32.W)) // # Sets = 1024
  val blkBits = RegInit(6.U(32.W)) // # blockSize = 64 Bytes

  // Statistics
  val isLLCRead = Wire(Bool())
  val isLLCWrite = Wire(Bool())
  val reads = RegInit(0.U(48.W))
  val writes = RegInit(0.U(48.W))
  when(isLLCRead) {
    reads := reads + 1.U
  }
  when(isLLCWrite) {
    writes := writes + 1.U
  }

  val isMiss = Wire(Bool())
  val misses = RegInit(0.U(48.W))
  when(isMiss) {
    misses := misses + 1.U
  }

  // For DRAM Power
  val bankNumBits   = p(BankNumBits)
  val bankBitOffset = p(BankBitOffset)
  val rowNumBits    = p(RowNumBits)
  val rowBitOffset  = p(RowBitOffset)
  val addrBits = Wire(chiselTypeOf(tNasti.ar.bits.addr))
  val isWr = Wire(Bool())
  val bankNum = addrBits(bankNumBits+bankBitOffset-1, bankBitOffset)
  val rowNum  = addrBits(rowNumBits+rowBitOffset-1, rowBitOffset)
  val rowNumArray = RegInit(VecInit(
    Seq.fill(1 << bankNumBits)(((BigInt(1) << rowNumBits) - 1).U(rowNumBits.W))))
  val rdSameRowCntr = RegInit(0.U(48.W))
  val rdDiffRowCntr = RegInit(0.U(48.W))
  val wrSameRowCntr = RegInit(0.U(48.W))
  val wrDiffRowCntr = RegInit(0.U(48.W))

  when(isMiss) {
    rowNumArray(bankNum) := rowNum
    when(!isWr) {
      when(rowNumArray(bankNum) === rowNum) {
        rdSameRowCntr := rdSameRowCntr + 1.U
      }.otherwise {
        rdDiffRowCntr := rdDiffRowCntr + 1.U
      }
    }.otherwise {
      when(rowNumArray(bankNum) === rowNum) {
        wrSameRowCntr := wrSameRowCntr + 1.U
      }.otherwise {
        wrDiffRowCntr := wrDiffRowCntr + 1.U
      }
    }
  }

  val stall = (rCycleValid && !rBuf.io.deq.valid) ||
              (wCycleValid && !bBuf.io.deq.valid) || !llcIdle
  val (fire, cycles, targetReset) = elaborate(
    stall, rCycleValid, wCycleValid, rCycleReady, wCycleReady)
  val fireNext = RegNext(fire)
  val arFire = tNasti.ar.fire()
  val awFire = tNasti.aw.fire()
  val wLast = tNasti.w.fire() && tNasti.w.bits.last
  val latency = p(LLCModelKey) match {
    case None =>
      rCycleReady := rCycles.io.enq.ready
      wCycleReady := wCycles.io.enq.ready
      rCycles.io.enq.valid := arFire && fire
      wCycles.io.enq.valid := wLast && fire
      rCycles.io.deq.ready := tNasti.r.fire() && tNasti.r.bits.last && fire
      llcIdle := true.B
      isLLCRead  := fire && arFire
      isLLCWrite := fire && awFire
      isMiss     := isLLCRead || isLLCWrite
      isWr       := isLLCWrite
      addrBits   := Mux(awFire, tNasti.aw.bits.addr, tNasti.ar.bits.addr)
      memLatency
    case Some(key: LLCParams) =>
      val llc = Module(new LLCModel(key))
      llc.io.config.wayBits := wayBits
      llc.io.config.setBits := setBits
      llc.io.config.blkBits := blkBits
      llc.io.rlen.bits   := tNasti.ar.bits.len
      llc.io.rlen.valid  := arFire && fireNext // FIXME: Bad assumption as future prediction
      llc.io.raddr.bits  := tNasti.ar.bits.addr
      llc.io.raddr.valid := arFire && fireNext // FIXME: Bad assumption as future prediction
      llc.io.waddr.bits  := tNasti.aw.bits.addr
      llc.io.waddr.valid := awFire && fireNext // FIXME: Bad assumption as future prediction
      llc.io.wlast.bits  := true.B
      llc.io.wlast.valid := wLast && fireNext // FIXME: Bad assumption as future prediction
      llc.io.resp.ready  := fire
      llcIdle := llc.io.idle
      rCycleReady := rCycles.io.enq.ready && llc.io.raddr.ready && llc.io.rlen.ready
      wCycleReady := wCycles.io.enq.ready && llc.io.waddr.ready && llc.io.wlast.ready
      rCycles.io.enq.valid := llc.io.resp.fire() && !llc.io.resp.bits.wr
      wCycles.io.enq.valid := llc.io.resp.fire() && llc.io.resp.bits.wr
      rCycles.io.deq.ready := tNasti.r.fire() && fire
      isLLCRead  := rCycles.io.enq.fire()
      isLLCWrite := wCycles.io.enq.fire()
      isMiss     := llc.io.resp.fire() && !llc.io.resp.bits.hit
      isWr       := llc.io.resp.bits.wb
      addrBits   := llc.io.resp.bits.addr
      Mux(llc.io.resp.bits.hit, llcLatency, memLatency)
  }

  rCycles.reset := reset.toBool || targetReset
  wCycles.reset := reset.toBool || targetReset
  rCycleValid := rCycles.io.deq.valid && rCycles.io.deq.bits <= cycles
  wCycleValid := wCycles.io.deq.valid && wCycles.io.deq.bits <= cycles
  rCycles.io.enq.bits  := cycles + latency
  wCycles.io.enq.bits  := cycles + latency
  wCycles.io.deq.ready := tNasti.b.fire() && fire

  io.hostMem.aw <> awBuf.io.deq
  io.hostMem.ar <> arBuf.io.deq
  io.hostMem.w  <> wBuf.io.deq
  rBuf.io.enq <> io.hostMem.r
  bBuf.io.enq <> io.hostMem.b

  // Connect all programmable registers to the control interrconect
  attach(memLatency, "MEM_LATENCY", WriteOnly)
  if (p(LLCModelKey).isDefined) {
    attach(llcLatency, "LLC_LATENCY", WriteOnly)
    attach(wayBits, "LLC_WAY_BITS", WriteOnly)
    attach(setBits, "LLC_SET_BITS", WriteOnly)
    attach(blkBits, "LLC_BLOCK_BITS", WriteOnly)
  }
  attach(reads(31, 0), "LLC_READS_LOW", ReadOnly)
  attach(reads >> 32, "LLC_READS_HIGH", ReadOnly)
  attach(writes(31, 0), "LLC_WRITES_LOW", ReadOnly)
  attach(writes >> 32, "LLC_WRITES_HIGH", ReadOnly)
  attach(misses(31, 0), "MISSES_LOW", ReadOnly)
  attach(misses >> 32, "MISSES_HIGH", ReadOnly)
  attach(rdSameRowCntr(31, 0), "SAME_ROW_READS_LOW", ReadOnly)
  attach(rdSameRowCntr >> 32, "SAME_ROW_READS_HIGH", ReadOnly)
  attach(rdDiffRowCntr(31, 0), "DIFF_ROW_READS_LOW", ReadOnly)
  attach(rdDiffRowCntr >> 32, "DIFF_ROW_READS_HIGH", ReadOnly)
  attach(wrSameRowCntr(31, 0), "SAME_ROW_WRITES_LOW", ReadOnly)
  attach(wrSameRowCntr >> 32, "SAME_ROW_WRITES_HIGH", ReadOnly)
  attach(wrDiffRowCntr(31, 0), "DIFF_ROW_WRITES_LOW", ReadOnly)
  attach(wrDiffRowCntr >> 32, "DIFF_ROW_WRITES_HIGH", ReadOnly)
  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    crRegistry.genArrayHeader(getWName.toUpperCase, base, sb)
  }
}
