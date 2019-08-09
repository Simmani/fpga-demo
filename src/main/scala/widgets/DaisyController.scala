// See LICENSE for license details.

package strober
package widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import strober.core.{DaisyBundle, DaisyData, ChainType}

class DaisyControllerIO(daisyIO: DaisyBundle)(implicit p: Parameters) extends WidgetIO()(p){
  val daisy = Flipped(daisyIO.cloneType)
  override def cloneType: this.type =
    new DaisyControllerIO(daisyIO).asInstanceOf[this.type]
}

class DaisyController(daisyIF: DaisyBundle)(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new DaisyControllerIO(daisyIF))

  def bindDaisyChain(daisy: DaisyData, name: String) = {
    val outAddr = attachDecoupledSource(daisy.out, s"${name}_OUT")
    val inWire = Wire(chiselTypeOf(daisy.in))
    val inAddr = attachDecoupledSink(inWire, s"${name}_IN")
    daisy.in <> Queue(inWire)
    val copyReg = RegInit(false.B)
    val copyAddr = attach(copyReg, s"${name}_COPY", WriteOnly)
    daisy.copy := Pulsify(copyReg, 1)
    val loadReg = RegInit(false.B)
    val loadAddr = attach(loadReg, s"${name}_LOAD", WriteOnly)
    daisy.load := Pulsify(loadReg, 1)
    (outAddr, inAddr, copyAddr, loadAddr)
  }
  val chains = ChainType.values.toList
  val names = (chains map { t => t -> t.toString.toUpperCase }).toMap
  val addrs = (chains map { t => t -> bindDaisyChain(io.daisy(t), names(t)) }).toMap

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    headerComment(sb)
    sb.append(genMacro("ENABLE_SNAPSHOT"))
    sb.append(genMacro("DAISY_WIDTH", UInt32(daisyIF.daisyWidth)))
    sb.append(genEnum("CHAIN_TYPE", (chains map (t => s"${names(t)}_CHAIN")) :+ "CHAIN_NUM"))
    sb.append(genArray("CHAIN_IN_ADDR",   chains map (t => UInt32(base + addrs(t)._2))))
    sb.append(genArray("CHAIN_OUT_ADDR",  chains map (t => UInt32(base + addrs(t)._1))))
    sb.append(genArray("CHAIN_COPY_ADDR", chains map (t => UInt32(base + addrs(t)._3))))
    sb.append(genArray("CHAIN_LOAD_ADDR", chains map (t => UInt32(base + addrs(t)._4))))
  }

  genCRFile()
}
