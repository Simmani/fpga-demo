// See LICENSE for license details.

package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.create_exps
import firrtl.passes.memlib.ReplSeqMemAnnotation
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import strober.core.ChainType
import dessert.passes.MemInstance
import java.io.{File, FileWriter}

class DumpChains(
    dir: File,
    meta: StroberMetaData)
   (implicit param: freechips.rocketchip.config.Parameters) extends firrtl.passes.Pass {
  
  override def name = "[strober] Dump Daisy Chains"

  private def addPad(chainFile: FileWriter, cw: Int, dw: Int)(chainType: ChainType.Value) {
    (cw - dw) match {
      case 0 =>
      case pad => chainFile write s"${chainType.id} null ${pad} -1\n"
    }
  }

  private def loop(chainFile: FileWriter,
                   mod: String,
                   path: String)
                  (chainType: ChainType.Value)
                  (implicit daisyWidth: Int) {
    val id = chainType.id
    val dw = meta.chains(chainType) get mod match {
      case None => 0
      case Some(chain) => (chain foldLeft 0){
        case (totalWidth, s: MemInstance) => ((s.sram, s.mem): @unchecked) match {
          // Macros transformed by [[firrtl.passes.memlib.ReplSeqMem]]
          case (Some(sram), None) => totalWidth + ((chainType: @unchecked) match {
            case ChainType.SRAM =>
              chainFile write s"$id ${path}.${s.name}.ram ${sram.width} ${sram.depth}\n"
              sram.width
            case ChainType.Trace =>
              (sram.ports filter (_.output.nonEmpty) foldLeft 0){ (sum, p) =>
                chainFile write s"$id ${path}.${s.name}.${p.output.get.name} ${sram.width} -1\n"
                sum + sram.width
              }
          })
          // Multiport register files transformed by [[dessert.passes.ReplaceMultiPortRAMs]]
          case (None, Some(mem)) => totalWidth + ((chainType: @unchecked) match {
            case ChainType.RegFile =>
              val name = verilogRenameN(mem.name)
              val width = bitWidth(mem.dataType).toInt
              chainFile write s"$id $path.$name $width ${mem.depth}\n"
              width
          })
        }
        case (totalWidth, s: DefMemory) if s.readLatency > 0 =>
          // Synchronous reads
          val name = verilogRenameN(s.name)
          val width = bitWidth(s.dataType).toInt
          totalWidth + ((chainType: @unchecked) match {
            case ChainType.SRAM =>
              chainFile write s"$id ${path}.${name} ${width} ${s.depth}\n"
              width.toInt
            case ChainType.Trace =>
              s.readers foreach (r =>
                chainFile write s"$id ${path}.${name}_${r}_data ${width} -1\n")
              // Port names by [[firrtl.passes.memlib.VerilogMemDelays]]
              s.readwriters foreach (rw =>
                chainFile write s"$id $path.${name}_${rw}_r_0_data $width -1\n")
              (s.readers.size + s.readwriters.size) * width
          })
        case (totalWidth, s: DefMemory) =>
          // Combinational reads
          val name = verilogRenameN(s.name)
          val width = bitWidth(s.dataType).toInt
          totalWidth + (chainType match {
            case ChainType.Mems | ChainType.RegFile =>
              chainFile write s"$id $path.$name $width ${s.depth}\n"
              width
            case ChainType.Regs => (((0 until s.depth) foldLeft 0){ (sum, i) =>
              chainFile write s"$id $path.$name[$i] $width -1\n"
              sum + width
            })
          })
        case (totalWidth, s: DefRegister) =>
          val name = verilogRenameN(s.name)
          val width = bitWidth(s.tpe).toInt
          chainFile write s"$id $path.$name $width -1\n"
          totalWidth + width
      }
    }
    val cw = (Stream from 0 map (_ * daisyWidth) dropWhile (_ < dw)).head
    addPad(chainFile, cw, dw)(chainType)
    (meta.childInsts(mod)
      filterNot (child => meta.dontTouchInsts(child -> mod))
      foreach { child =>
        val childMod = meta.instModMap(child -> mod)
        if (!meta.dontTouchMods(childMod)) {
          loop(chainFile, childMod, s"${path}.${child}")(chainType)
        }
      })
  }

  def run(c: Circuit) = {
    implicit val daisyWidth = param(core.DaisyWidth)
    val chainFile = new FileWriter(new File(dir, s"${c.main}.chain"))
    ChainType.values.toList foreach loop(chainFile, c.main, c.main)
    chainFile.close
    c
  }
}
