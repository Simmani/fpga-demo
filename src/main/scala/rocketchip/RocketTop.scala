//See LICENSE for license details.

package dessert
package rocketchip

import freechips.rocketchip._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.config.{Parameters, Config}
import testchipip._
import sifive.blocks.devices.uart._

class RocketTop(implicit p: Parameters) extends RocketSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    // with CanHaveMasterAXI4MemPort
    with HasPeripheryBootROM
    // with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
{
  override lazy val module = new RocketTopModule(this)
}

class RocketTopModule[+L <: RocketTop](l: L) extends RocketSubsystemModuleImp(l)
    with HasRTCModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    // with CanHaveMasterAXI4MemPortModuleImp
    with HasPeripheryBootROMModuleImp
    // with HasExtInterruptsModuleImp
    with HasNoDebugModuleImp
    with HasPeripherySerialModuleImp
    with HasPeripheryUARTModuleImp

class DefaultRocketConfig extends Config(new Config((site, here, up) => {
    case PeripheryUARTKey => List(UARTParams(address = BigInt(0x54000000L)))
    case BootROMParams => up(BootROMParams, site).copy(
      contentFileName = s"designs/testchipip/bootrom/bootrom.rv${site(XLen)}.img")
    case RocketTilesKey => up(RocketTilesKey, site) map (tile => tile.copy(
      icache = tile.icache map (_.copy(
        nWays = 8, // 32KiB
        nTLBEntries = 32 // TLB reach = 32 * 4KB = 128KB
      )),
      dcache = tile.dcache map (_.copy(
        nWays = 8, // 32KiB
        nTLBEntries = 32 // TLB reach = 32 * 4KB = 128KB
      )),
      core = tile.core.copy(
        nPerfCounters = 29,
        nL2TLBEntries = 1024 // TLB reach = 1024 * 4KB = 4MB
      )
    ))
  }) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new freechips.rocketchip.system.DefaultConfig)
