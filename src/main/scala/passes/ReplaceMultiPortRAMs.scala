package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.module_type
import collection.immutable.ListMap

case class MultiPortRAMParam(
    depth: Int,
    width: Int,
    readers: Seq[String],
    writers: Seq[String]) {
  override def toString = s"${depth}x${width}_r${readers.size}_w${writers.size}"
}
class MultiPortRAM(param: MultiPortRAMParam) extends chisel3.experimental.RawModule {
  import chisel3._
  import chisel3.util._
  import chisel3.experimental.withClockAndReset
  class ReadPortType extends Bundle {
    val clk  = Input(Clock()) // Not used
    val en   = Input(Bool())
    val addr = Input(UInt(log2Ceil(param.depth).W))
    val data = Output(UInt(param.width.W))
  }
  class WritePortType extends Bundle {
    val clk  = Input(Clock()) // Not used
    val en   = Input(Bool())
    val mask = Input(Bool())
    val addr = Input(UInt(log2Ceil(param.depth).W))
    val data = Input(UInt(param.width.W))
  }
  class MultiPortRAMIO extends Record {
    val elements = ListMap() ++
      (param.readers map (_ -> new ReadPortType)) ++
      (param.writers map (_ -> new WritePortType))
    def cloneType = (new MultiPortRAMIO).asInstanceOf[this.type]
  }

  assert(param.readers.size > 0)
  assert(param.writers.size > 0)
  assert(param.readers.size + param.writers.size > 2)

  val io = IO(new MultiPortRAMIO)
  val clock = io.elements(param.readers.head).asInstanceOf[ReadPortType].clk // is it Ok?

  val replica = Seq.fill(param.readers.size)(
                Seq.fill(param.writers.size)(
                Mem(param.depth, UInt(param.width.W))))
  val vector = withClockAndReset(clock, false.B) {
            Reg(Vec(param.depth, UInt(log2Ceil(param.writers.size).W)))
  }

  (param.readers zip replica) foreach { case (reader, mems) =>
    val read = io.elements(reader).asInstanceOf[ReadPortType]
    (param.writers zip mems).zipWithIndex map { case ((writer, mem), i) =>
      val write = io.elements(writer).asInstanceOf[WritePortType]
      withClockAndReset(write.clk, false.B) {
        val wen = write.en && write.mask
        wen suggestName s"replica_wen_$i"
        when(wen) {
          mem(write.addr) := write.data
        }
      }
    }
    withClockAndReset(read.clk, false.B) {
      read.data := (param.writers.size match {
        case 1 => mems.head(read.addr)
        case _ =>
          val raddrs = VecInit(mems map (_(read.addr)))
          raddrs suggestName s"raddrs"
          raddrs(vector(read.addr))
      })
    }
  }

  if (param.writers.size > 1) {
    param.writers.zipWithIndex map { case (writer, i) =>
      val write = io.elements(writer).asInstanceOf[WritePortType]
      withClockAndReset(write.clk, false.B) {
        val wen = write.en && write.mask
        wen suggestName s"vector_wen_$i"
        when(wen) {
          vector(write.addr) := i.U
        }
      }
    }
  }
}

class ReplaceMultiPortRAMs extends firrtl.passes.Pass {
  override def name = "[dessert] replace multi-port RAMs"
  override def inputForm = LowForm
  override def outputForm = LowForm
  
  private val mods = collection.mutable.ArrayBuffer[Module]()

  def renameIoPinsExp(e: Expression): Expression =
    e map renameIoPinsExp match {
      case e: WSubField => e.expr match {
        case ex: WRef if ex.name == "io" =>
          WRef(e.name, e.tpe, ex.kind, e.gender)
        case _ => e
      }
      case e => e
    }

  def renameIoPins(s: Statement): Statement =
    s map renameIoPins map renameIoPinsExp

  def transform(mname: String)(s: Statement): Statement = s map transform(mname) match {
    case s: DefMemory => s.dataType match {
      case tpe: UIntType if s.depth >= 32 && bitWidth(tpe) >= 32 && s.readLatency == 0 && 
                            (s.readers.size + s.writers.size) > 2 =>
        val param = MultiPortRAMParam(s.depth, bitWidth(tpe).toInt, s.readers, s.writers)
        val chirrtl = Parser parse (chisel3.Driver emit (() => new MultiPortRAM(param)))
        val state = new MiddleFirrtlCompiler compile (CircuitState(chirrtl, ChirrtlForm), Nil)
        val module = state.circuit.modules.head match { case m: Module => m.copy(
          name = s"${m.name}_${param}",
          body = (m.body map renameIoPins),
          ports = (m.ports.head match {
            case Port(info, "io", Output, BundleType(fs)) => fs map {
              case Field(name, Flip,    tpe) => Port(info, name, Input,  tpe) 
              case Field(name, Default, tpe) => Port(info, name, Output, tpe)
            }
          })
        )}
        println(s"infer multi-port RAM: ${mname}.${s.name} -> ${module.name}")
        mods += module
        new MemInstance(s.info, s.name, module.name, module_type(module), mem = Some(s))
      case _ => s
    }
    case s => s
  }

  def run(c: Circuit) =
    c.copy(modules = (c.modules map (m => m map transform(m.name))) ++ mods)
}
