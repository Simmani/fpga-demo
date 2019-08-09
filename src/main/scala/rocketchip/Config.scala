//See LICENSE for license details.

package dessert
package rocketchip

import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.subsystem.PeripheryBusParams

class ZynqConfig extends Config(new WithRocketChipEndpoints ++ new midas.ZynqConfig)
class F1Config extends Config(new WithRocketChipEndpoints ++ new midas.F1Config)

class WithRocketChipEndpoints extends Config(new Config((site, here, up) => {
  case dessert.widgets.LLCModelKey =>
    Some(dessert.widgets.LLCParams())
  case midas.EndpointKey =>
    up(midas.EndpointKey) ++
    midas.core.EndpointMap(Seq(
     new endpoints.SimAXI4MemIO,
     new endpoints.SimSerialIO,
     new endpoints.SimUART
   ))
  case endpoints.UARTDiv =>
    (PeripheryBusParams(0, 0).frequency / 115200).toInt // FIXME: better way?
}))
