package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.{kind, zero, one, module_type, BoolType}
import strober.passes.StroberMetaData

import java.io.File
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap, LinkedHashMap}
import scala.util.matching.Regex

class SVFReader {
  private type ModuleMap = HashMap[String, String]
  private type SplitSigs = HashSet[String]
  private type ConstRegMap = HashMap[String, Int]
  private type MergedRegMap = HashMap[String, String]
  private val _instanceMap = HashMap[String, ModuleMap]()
  private val _uniquifyMap = HashMap[String, ModuleMap]()
  private val _splitSigs = HashMap[String, SplitSigs]()
  private val _constSigs = HashMap[String, ConstRegMap]()
  private val _mergedRegs = HashMap[String, MergedRegMap]()

  def instanceMap(design: String) =
     _instanceMap getOrElseUpdate (design, new ModuleMap)
  def uniquifyMap(design: String) =
     _uniquifyMap getOrElseUpdate (design, new ModuleMap)
  def splitSigs(design: String) = 
     _splitSigs getOrElseUpdate (design, new SplitSigs)
  def constSigs(design: String) =
     _constSigs getOrElseUpdate (design, new ConstRegMap)
  def mergedRegs(design: String) =
     _mergedRegs getOrElseUpdate (design, new MergedRegMap)

  private val sigRegex  = """([\w.\$]+)_(\d+)_""".r
  private val sigRegex0 = """([\w.\$]+)""".r

  private def getPathAndIdx(sig: String) = sig match {
    case sigRegex(path, idx) => path -> idx.toInt
    case sigRegex0(path)     => path -> 0
  }

  private def getName(x: (String, Int)) = s"${x._1}_${x._2}_"

  def read(svf: File) {
    io.Source.fromFile(svf).getLines foreach { line =>
      val tokens = line split " "
      (tokens.head.toInt: @unchecked) match {
        case 0 => instanceMap(tokens(1))(tokens(2)) = tokens(3)
        case 1 => uniquifyMap(tokens(1))(tokens(2)) = tokens(3)
        case 2 =>
          val reg = getPathAndIdx(tokens(2))
          splitSigs(tokens(1)) += reg._1
          constSigs(tokens(1))(getName(reg)) = tokens(3).toInt
        case 3 => // skip: inverted regs
        case 4 =>
          val from = getPathAndIdx(tokens(2))
          val to   = getPathAndIdx(tokens(3))
          splitSigs(tokens(1)) ++= Seq(from._1, to._1)
          mergedRegs(tokens(1))(getName(from)) = getName(to)
      }
    }
  }
}

object SVFReader {
  def apply(svf: File) = {
    val reader = new SVFReader
    reader read svf
    reader
  }
}

case class SVFBackAnnotation(svf: File) extends NoTargetAnnotation

class SVFBackAnnotationPass extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  private type Statements = ArrayBuffer[Statement]
  private val modules = LinkedHashMap[String, DefModule]()
  private val moduleTypes = HashMap[String, Type]()

  def uniquifyStmt(name: String, module: String)(s: Statement): Statement =
    s map uniquifyStmt(name, module) match {
      case s: WDefInstance if s.name == name =>
        s.copy(module = module)
      case s => s
    }

  def uniquify(
      mod: String,
      path: String,
      meta: StroberMetaData, 
      instMap: HashMap[String, String]) {
    meta.childInsts(mod) foreach { child =>
      val childPath = if (path.isEmpty) child else s"${path}.${child}"
      val childMod = meta.instModMap(child -> mod)
      instMap get childPath match {
        case Some(newChildMod) if !(moduleTypes contains newChildMod) ||
            moduleTypes(newChildMod) == moduleTypes(childMod) =>
          modules(mod) = modules(mod) map uniquifyStmt(child, newChildMod)
          modules(newChildMod) = modules(childMod) match {
            case m: Module    => m.copy(name = newChildMod)
            case m: ExtModule => m.copy(name = newChildMod)
          }
          moduleTypes(newChildMod) = module_type(modules(newChildMod))
          meta.childInsts(newChildMod) = meta.childInsts(childMod).clone
          meta.childMods(newChildMod) = meta.childMods(childMod).clone
          meta.instModMap(child -> mod) = newChildMod
          meta.childInsts(newChildMod) foreach { inst =>
            meta.instModMap(inst -> newChildMod) = meta.instModMap(inst -> childMod)
          }
          uniquify(newChildMod, childPath, meta, instMap)
        case Some(newChildMod) =>
          Console.err.println(s"Module type not match: $childMod != $newChildMod")
          uniquify(childMod, childPath, meta, instMap)
        case None =>
          uniquify(childMod, childPath, meta, instMap)
      }
    }

    instMap get path
  }

  private def doCat(args: Seq[Expression]): Expression = args.size match {
    case 0 => EmptyExpression
    case 1 => args.head
    case n => DoPrim(PrimOps.Cat, Seq(doCat(args take n/2), doCat(args drop n/2)), Nil, UnknownType)
  }

  def replacePortsOnExpr(ports: Map[String, String])(e: Expression): Expression =
    e map replacePortsOnExpr(ports) match {
      case e: WRef =>
        e.copy(name = ports getOrElse (e.name, e.name))
      case e => e
    }

  def replacePortsOnStmt(ports: Map[String, String])(s: Statement): Statement =
    s map replacePortsOnExpr(ports) map replacePortsOnStmt(ports)

  def splitPorts(svf: SVFReader)(m: DefModule) = {
    val sigs = svf.splitSigs(m.name)
    val (ports, stmts) = (m.ports foldLeft (Map[String, String](), Seq[Statement]())){
      case ((ps, ss), p) if sigs(p.name) => p.direction match {
        case Input =>
          val width = bitWidth(p.tpe).toInt
          val portRef = WRef(p.name, p.tpe, PortKind, FEMALE)
          val bits = (0 until width) map (i => DefNode(p.info, s"${p.name}_${i}_",
            DoPrim(PrimOps.Bits, Seq(portRef), Seq(i, i), BoolType)))
          val value = doCat(bits.reverse map (bit => WRef(bit.name)))
          val node = DefNode(p.info, s"${p.name}_node", p.tpe match {
            case ClockType   => DoPrim(PrimOps.AsClock, Seq(value), Nil, ClockType)
            case t: SIntType => DoPrim(PrimOps.AsSInt,  Seq(value), Nil, t)
            case t: UIntType => value
          })
          (ps + (p.name -> node.name), ss ++ bits ++ Seq(node))
        case Output => (ps, ss) // TODO?
      }
      case ((ps, ss), _) => (ps, ss)
    }
    m map replacePortsOnStmt(ports) match {
      case m: ExtModule => m
      case m: Module => m.copy(body = Block(stmts :+ m.body))
    }
  }

  def splitSigs(sigs: HashSet[String])(s: Statement): Statement =
    s map splitSigs(sigs) match {
      case s: DefRegister if sigs(s.name) =>
        val width = bitWidth(s.tpe).toInt
        val bus = DefWire(s.info, s"${s.name}", s.tpe)
        val bits = (0 until width) map (i => s.copy(
          name = s"${s.name}_${i}_",
          tpe  = BoolType,
          init = DoPrim(PrimOps.Bits, Seq(s.init), Seq(i, i), BoolType)))
        val value = doCat(bits.reverse map (bit => WRef(bit.name)))
        val connect = Connect(s.info, WRef(s.name), s.tpe match {
          case t: SIntType => DoPrim(PrimOps.AsSInt, Seq(value), Nil, t)
          case t: UIntType => value
        })
        Block(Seq(bus) ++ bits ++ Seq(connect))
      case s: Connect => (s.loc, kind(s.loc)) match {
        case (e: WRef, RegKind | PortKind) if sigs(e.name) =>
          val width = bitWidth(s.expr.tpe).toInt
          Block((0 until bitWidth(e.tpe).toInt) map (i =>
            if (i < width) Connect(s.info,
              e.copy(name = s"${e.name}_${i}_", tpe = BoolType),
              DoPrim(PrimOps.Bits, Seq(s.expr), Seq(i, i), BoolType))
            else EmptyStmt
          ))
        case _ => s
      }
      case s => s
    }

  def mergeRegs(
      regs: HashMap[String, String],
      stmts: Statements)
     (s: Statement): Statement = {
    var hasReg = false
    def mergeRegsOnExp(e: Expression): Expression =
      e map mergeRegsOnExp match {
        case e: WRef => regs get e.name match {
          case Some(reg) =>
            hasReg = true
            e.copy(name = reg)
          case None => e
        }
        case e => e
      }

    s map mergeRegs(regs, stmts) match {
      case s: DefRegister if regs contains s.name =>
        EmptyStmt
      case s =>
        val sx = s map mergeRegsOnExp
        if (hasReg) {
          stmts += sx
          EmptyStmt
        } else sx
    }
  }

  def constProp(sigs: HashMap[String, Int])(s: Statement): Statement =
    s map constProp(sigs) match {
      case s: DefRegister => ((sigs get s.name): @unchecked) match {
        case None => s
        case Some(0) => DefNode(s.info, s.name, zero)
        case Some(1) => DefNode(s.info, s.name, one)
      }
      case s: DefNode => ((sigs get s.name): @unchecked) match {
        case None => s
        case Some(0) => s.copy(value = zero)
        case Some(1) => s.copy(value = one)
      }
      case s: Connect => (s.loc, kind(s.loc)) match {
        case (e: WRef, RegKind | PortKind) if sigs contains e.name =>
          EmptyStmt
        case _ => s
      }
      case s => s
    }

  @scala.annotation.tailrec
  private def loop(m: DefModule, svf: SVFReader): DefModule = {
    val stmts = new Statements
    val mx = (m map mergeRegs(svf.mergedRegs(m.name), stmts)
                map constProp(svf.constSigs(m.name)))
    mx match {
      case m: ExtModule => m
      case m: Module if stmts.isEmpty => m
      case m: Module =>
        loop(m.copy(body = Block(m.body +: stmts.toSeq)), svf)
    }
  }

  def transform(svf: SVFReader)(m: DefModule) = {
    loop(m map splitSigs(svf.splitSigs(m.name)), svf)
  }

  def run(c: Circuit, svf: SVFReader, meta: StroberMetaData) = {
    modules ++= (c.modules map (m => m.name -> m))
    moduleTypes ++= (c.modules map (m => m.name -> module_type(m)))
    uniquify(c.main, "", meta, svf.uniquifyMap(c.main))
    c.copy(modules = modules.values.toSeq map splitPorts(svf) map transform(svf))
  }

  private object Optimizations extends SeqTransform {
    def inputForm = LowForm
    def outputForm = LowForm
    def transforms = Seq(
      firrtl.passes.ResolveKinds,
      firrtl.passes.ResolveGenders,
      firrtl.passes.CheckGenders,
      firrtl.passes.InferTypes,
      firrtl.passes.CheckTypes,
      firrtl.passes.InferWidths,
      firrtl.passes.CheckWidths,
      RemoveAssertions,
      // new firrtl.transforms.ConstantPropagation,
      new dessert.passes.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination)
  }

  def execute(state: CircuitState) = {
    val meta = StroberMetaData(state)
    (state.annotations foldLeft state){
      case (state, SVFBackAnnotation(svf)) =>
        val reader = SVFReader(svf)
        Optimizations execute state.copy(
          circuit = run(state.circuit, reader, meta))
      case (state, _) => state
    }
  }
}
