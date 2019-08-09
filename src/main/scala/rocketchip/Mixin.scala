package dessert
package rocketchip

import chisel3._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

/** Adds a port to the system intended to master an AXI4 DRAM controller. */
trait CanHaveMisalignedMasterAXI4MemPort { this: BaseSubsystem =>
  val module: CanHaveMisalignedMasterAXI4MemPortModuleImp

  private val memPortParamsOpt = p(ExtMem)
  private val portName = "axi4"
  private val device = new MemoryDevice
  val nMemoryChannels: Int

  val memAXI4Node = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { channel =>
    val params = memPortParamsOpt.get
    val base = AddressSet.misaligned(params.base, params.size)

    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = base,
        resources     = device.reg,
        regionType    = RegionType.UNCACHED,   // cacheable
        executable    = true,
        supportsWrite = TransferSizes(1, cacheBlockBytes),
        supportsRead  = TransferSizes(1, cacheBlockBytes),
        interleavedId = Some(0))),             // slave does not interleave read responses
      beatBytes = params.beatBytes)
  })

  memPortParamsOpt.foreach { params =>
    memBuses.map { m =>
       memAXI4Node := m.toDRAMController(Some(portName)) {
        (AXI4UserYanker() := AXI4IdIndexer(params.idBits) := TLToAXI4())
      }
    }
  }
}

/** Actually generates the corresponding IO in the concrete Module */
trait CanHaveMisalignedMasterAXI4MemPortModuleImp extends LazyModuleImp {
  val outer: CanHaveMisalignedMasterAXI4MemPort

  val mem_axi4 = IO(HeterogeneousBag.fromNode(outer.memAXI4Node.in))
  (mem_axi4 zip outer.memAXI4Node.in).foreach { case (io, (bundle, _)) => io <> bundle }

  def connectSimAXIMem() {
    (mem_axi4 zip outer.memAXI4Node.in).foreach { case (io, (_, edge)) =>
      val mem = LazyModule(new SimAXIMem(edge, size = p(ExtMem).get.size))
      Module(mem.module).io.axi4.head <> io
    }
  }
}
