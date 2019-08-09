// See LICENSE for license details.

package midas

import core._
import widgets._
import platform._
import strober.core._
import dessert.widgets._
import junctions.{NastiKey, NastiParameters}
import freechips.rocketchip.config.{Parameters, Config, Field}

trait PlatformType
case object Zynq extends PlatformType
case object F1 extends PlatformType
case object Platform extends Field[PlatformType]
case object EnableSnapshot extends Field[Boolean]
case object HasDMAChannel extends Field[Boolean]
case object MemModelKey extends Field[Option[Parameters => MemModel]]
case object EndpointKey extends Field[EndpointMap]
case object ToggleCounterSize extends Field[Option[Int]]
case object ClockFrequency extends Field[Int]

class SimConfig extends Config((site, here, up) => {
  case TraceMaxLen       => 1024
  case ChannelLen        => 16
  case ChannelWidth      => 32
  case DaisyWidth        => 32
  case EnableSnapshot    => false
  case CtrlNastiKey      => NastiParameters(32, 32, 12)
  case MemNastiKey       => NastiParameters(64, 32, 6)
  case DMANastiKey       => NastiParameters(512, 64, 6)
  case EndpointKey       => EndpointMap(Seq(new SimNastiMemIO))
  case MemModelKey       => Some((p: Parameters) => new SimpleLatencyPipe()(p))
  case LLCModelKey       => None
  case FpgaMMIOSize      => BigInt(1) << 12 // 4 KB
  case AXIDebugPrint     => false
  case ToggleCounterSize => None
  // DRAM Counters
  case BankNumBits       => 3
  case BankBitOffset     => 0
  case RowNumBits        => 14
  case RowBitOffset      => 11
})

class ZynqConfig extends Config(new Config((site, here, up) => {
  case Platform       => Zynq
  case HasDMAChannel  => false
  case MasterNastiKey => site(CtrlNastiKey)
  case SlaveNastiKey  => site(MemNastiKey)
}) ++ new SimConfig)

class ZynqConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new ZynqConfig)

class F1Config extends Config(new Config((site, here, up) => {
  case Platform       => F1
  case HasDMAChannel  => true
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case MemNastiKey    => NastiParameters(64, 34, 16)
  case MasterNastiKey => site(CtrlNastiKey)
  case SlaveNastiKey  => site(MemNastiKey)
  case ClockFrequency => 75
}) ++ new SimConfig)

class F1ConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new F1Config)
