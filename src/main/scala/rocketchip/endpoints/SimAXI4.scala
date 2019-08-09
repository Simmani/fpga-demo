package dessert
package rocketchip
package endpoints

import freechips.rocketchip.amba.axi4.AXI4Bundle

import chisel3._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf

import junctions.NastiParameters

class SimAXI4MemIO extends midas.core.SimMemIO {
  override protected def inferTargetAXI4Widths(channel: Data) = {
    channel match {
      case axi4: AXI4Bundle => NastiParameters(axi4.r.bits.data.getWidth,
                                               axi4.ar.bits.addr.getWidth,
                                               axi4.ar.bits.id.getWidth)
      case _ => super.inferTargetAXI4Widths(channel)
    }
  }

  def matchType(data: Data) = data match {
    case channel: AXI4Bundle =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}
