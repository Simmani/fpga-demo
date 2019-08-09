package dessert
package rocketchip

import freechips.rocketchip.subsystem.WithNBanksPerMemChannel
import freechips.rocketchip.config.{Parameters, Config}
import hwacha._

class HwachaTop(implicit p: Parameters) extends RocketTop

class ExampleHwachaConfig extends Config(
  new WithNLanes(1) ++
  new DefaultHwachaConfig ++
  new DefaultRocketConfig)
