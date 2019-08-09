package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.{BoolType, zero, one, module_type, create_exps}
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.MemPortUtils.memType
import firrtl.analyses.InstanceGraph
import midas.passes._
import strober.passes.{StroberMetaData, Netlist, postorder}
import collection.immutable.ListMap
import java.io.File

class HDUnit(width: Int) extends chisel3.experimental.RawModule {
  import chisel3._
  import chisel3.util._
  val in = IO(Input(Vec(2, UInt(width.W))))
  val out = IO(Output(UInt(log2Ceil(width+1).W)))
  out := PopCount(in(0) ^ in(1))
}

class ToggleCounterFile(ports: Seq[(String, Int)]) extends chisel3.Module {
  import chisel3._
  import chisel3.util._
  class InputRecord extends Record {
    val elements = ListMap() ++ (ports map {
      case (name, width) => name -> UInt(width.W) })
    def cloneType = (new InputRecord).asInstanceOf[this.type]
  }
  val io = IO(new Bundle {
    val fire = Input(Bool()) // FIXME?
    val ins = Input(new InputRecord)
    val outs = Output(Vec(ports.size, UInt(32.W)))
  })

  (io.ins.getElements zip io.outs).zipWithIndex foreach {
    case ((in, out), i) =>
      val counter = Reg(UInt(32.W))
      counter suggestName s"counter_$i"
      out := counter
      when (io.fire) {
        counter := Mux(reset.toBool, 0.U, counter + in.asUInt)
      }
  }
}

case class AddToggleCountersAnnotation(model: File) extends NoTargetAnnotation
case class ToggleCounterWidgetAnnotation(size: Int) extends NoTargetAnnotation

class AddToggleCounters(dir: File, fame1: Boolean) extends Transform {
  override def name = "[dessert] add toggle counters"
  def inputForm = LowForm
  def outputForm = LowForm

  private val noClockMods = collection.mutable.HashMap[String, String]()
  type Statements = collection.mutable.ArrayBuffer[Statement]
  type SignalSet = collection.mutable.HashSet[String]
  type HammingDists = collection.mutable.ArrayBuffer[(String, Expression)]
  type PortMap = collection.mutable.LinkedHashMap[String, WRef]
  private val signalPaths = collection.mutable.ArrayBuffer[String]()
  private val signals = collection.mutable.HashMap[String, SignalSet]()
  private val portMaps = collection.mutable.HashMap[String, PortMap]()
  private val hdUnitCache = collection.mutable.HashMap[Int, Module]()


  def readModel(file: java.io.File, main: String, bboxes: Set[String]) {
    io.Source.fromFile(file).getLines foreach { line =>
      val tokens = line.split(",")
      tokens.head match {
         case "signals" => tokens.tail foreach { path =>
           val tokens = main +: (path.split('.'))
           val inst = tokens.init mkString "."
           if (!bboxes(inst)) {
             val lastToken = tokens.last
             val signal = if (lastToken.last == '$') lastToken.init else lastToken
             signalPaths += s"${inst}.${signal}"
             (signals getOrElseUpdate (inst, new SignalSet)) += signal
           } else {
             val (parent, signal) = tokens splitAt (tokens.size - 2)
             val parentInst = parent mkString "."
             val signalName = signal mkString "_"
             signalPaths += s"${parentInst}.${signalName}"
             (signals getOrElseUpdate (parentInst, new SignalSet)) += signalName
           }
         }
         case _ => // skip
      }
    }
  }

  def connectClocksStmt(clock: WRef)(s: Statement): Statement =
    s map connectClocksStmt(clock) match {
      case s: WDefInstance =>
        noClockMods get s.module match {
          case Some(p) =>
            val childClock = wsub(wref(s.name), p).copy(tpe = ClockType)
            Block(Seq(s, Connect(NoInfo, childClock, clock)))
          case None => s
        }
      case s => s
    }

  def connectClocks(m: DefModule) =
    m match {
      case m: ExtModule => Seq(m)
      case m: Module => m.ports find (_.tpe == ClockType) match {
        case Some(port) =>
          val clock = wref(port.name, port.tpe)
          Seq(m map connectClocksStmt(clock))
        case None =>
          val port = Port(NoInfo, "clock", Input, ClockType)
          val clock = wref(port.name, port.tpe)
          noClockMods(m.name) = port.name
          Seq(m.copy(ports= m.ports :+ port) map connectClocksStmt(clock))
      }
    }

  def generateHDUnit(width: Int) = {
    hdUnitCache getOrElseUpdate (width, {
      val chirrtl = Parser parse (chisel3.Driver emit (() => new HDUnit(width)))
      val state = new MiddleFirrtlCompiler compile (CircuitState(chirrtl, ChirrtlForm), Nil)
      state.circuit.modules.head match {
        case m: Module => m.copy(name = s"${m.name}_${width}")
      }
    })
  }

  def instantiateHDUnit(
      namespace: Namespace,
      name: String,
      args: Seq[Expression],
      tpe: Type,
      hammingDists: HammingDists,
      hdStmts: Statements) {
    val width = bitWidth(tpe).toInt
    val unit = generateHDUnit(width)
    val unitName = namespace newName s"${name}_hdunit"
    val unitType = module_type(unit)
    val unitRef = wref(unitName, unitType)
    val unitIn = wsub(unitRef, "in")
    val unitOut = wsub(unitRef, "out")

    val nodes = args.zipWithIndex map { case (arg, i) =>
      val argName = namespace newName s"${name}_arg_${i}"
      val argType = UIntType(getWidth(arg))
      wref(argName, argType)
    }

    hammingDists += (name -> unitOut)
    hdStmts ++= (
      ((nodes zip args) map { case (node, arg) =>
        DefNode(NoInfo, node.name, DoPrim(PrimOps.AsUInt, Seq(arg), Nil, node.tpe))
      }) ++ Seq(
        WDefInstance(NoInfo, unitName, unit.name, unitType),
        Connect(NoInfo, widx(unitIn, 0), nodes(0)),
        Connect(NoInfo, widx(unitIn, 1), nodes(1))
      )
    )
  }

  def targetFire = if (fame1) wref("targetFire", BoolType) else one

  def addHDUnitsPorts(
      signalSet: SignalSet,
      hammingDists: HammingDists,
      hdStmts: Statements,
      namespace: Namespace,
      clock: WRef)
     (ports: Seq[Port]): Seq[Statement] =
    ports flatMap { p =>
      if (!signalSet(p.name)) Nil
      else {
        val regName = namespace newName s"${p.name}_reg"
        val prev = wref(regName, p.tpe)
        val cur  = wref(p.name, p.tpe)
        instantiateHDUnit(namespace, p.name, Seq(cur, prev), p.tpe, hammingDists, hdStmts)
        Seq(DefRegister(NoInfo, regName, p.tpe, clock, zero, prev),
            Conditionally(NoInfo, targetFire, Connect(NoInfo, prev, cur), EmptyStmt))
      }
    }

  def addHDUnitsStmt(
      signalSet: SignalSet,
      hammingDists: HammingDists,
      hdStmts: Statements,
      namespace: Namespace,
      netlist: Netlist,
      clock: WRef)
     (s: Statement): Statement = {
    s map addHDUnitsStmt(signalSet, hammingDists, hdStmts, namespace, netlist, clock) match {
      case s: DefRegister if signalSet(s.name) =>
        val prev = wref(s.name, s.tpe)
        val cur  = netlist(s.name)
        assert(s.clock.serialize == clock.name)
        instantiateHDUnit(namespace, s.name, Seq(cur, prev), s.tpe, hammingDists, hdStmts)
        s

      case s: DefWire if signalSet(s.name) =>
        val regName = namespace newName s"${s.name}_reg"
        val prev = wref(regName, s.tpe)
        val cur  = wref(s.name, s.tpe)
        instantiateHDUnit(namespace, s.name, Seq(cur, prev), s.tpe, hammingDists, hdStmts)
        Block(Seq(s,
          DefRegister(NoInfo, regName, s.tpe, clock, zero, prev),
          Conditionally(NoInfo, targetFire, Connect(NoInfo, prev, cur), EmptyStmt)))

      case s: DefNode if signalSet(s.name) =>
        val regName = namespace newName s"${s.name}_reg"
        val prev = wref(regName, s.value.tpe)
        val cur  = wref(s.name, s.value.tpe)
        instantiateHDUnit(namespace, s.name, Seq(cur, prev), s.value.tpe, hammingDists, hdStmts)
        Block(Seq(s,
          DefRegister(NoInfo, regName, s.value.tpe, clock, zero, prev),
          Conditionally(NoInfo, targetFire, Connect(NoInfo, prev, cur), EmptyStmt)))
      case s: DefMemory =>
        Block(s +: (create_exps(s.name, memType(s)) flatMap { port =>
          val name = loweredName(port)
          val cur  = netlist getOrElse (port.serialize, port)
          if (!signalSet(name)) Nil else {
            val regName = namespace newName s"${name}_reg"
            val prev = wref(regName, cur.tpe)
            hdStmts += Conditionally(NoInfo, targetFire, Connect(NoInfo, prev, cur), EmptyStmt)
            instantiateHDUnit(namespace, name, Seq(cur, prev), cur.tpe, hammingDists, hdStmts)
            Seq(DefRegister(NoInfo, regName, cur.tpe, clock, zero, prev))
          }
        }))

      case s: WDefInstance if s.tpe != ut =>
        Block(s +: (create_exps(s.name, s.tpe) flatMap { cur =>
          val name = loweredName(cur)
          if (!signalSet(name)) Nil else {
            val regName = namespace newName s"${name}_reg"
            val prev = wref(regName, cur.tpe)
            instantiateHDUnit(namespace, name, Seq(cur, prev), cur.tpe, hammingDists, hdStmts)
            Seq(DefRegister(NoInfo, regName, cur.tpe, clock, zero, prev),
                Conditionally(NoInfo, targetFire, Connect(NoInfo, prev, cur), EmptyStmt))
          }
        }))
      case s => s
    }
  }

  def connectHDsToPorts(
      mname: String,
      meta: StroberMetaData,
      hammingDists: HammingDists,
      namespace: Namespace) = {
    portMaps(mname) = new PortMap
    (hammingDists map { case (name, e) =>
      val port = wref(namespace newName s"${name}_hd", e.tpe)
      portMaps(mname) += (name -> port)
      Connect(NoInfo, port, e)
    }) ++ (meta.childInsts(mname) flatMap { child =>
      portMaps getOrElse (meta.instModMap(child -> mname), Nil) map { case (name, e) =>
        val port = wref(namespace newName s"${child}_${e.serialize}", e.tpe)
        portMaps(mname) += (s"${child}.${name}" -> port)
        Connect(NoInfo, port, wsub(wref(child), e.name).copy(tpe = e.tpe))
      }
    })
  }

  def generateCntrFile(ports: Seq[(String, Int)]) = {
    val chirrtl = Parser parse (chisel3.Driver emit (() => new ToggleCounterFile(ports)))
    val state = new MiddleFirrtlCompiler compile (CircuitState(chirrtl, ChirrtlForm), Nil)
    state.circuit.modules.head
  }

  def connectHDsToCntrFile(
      modName: String,
      cntrFile: WRef,
      topOutPort: Port,
      ports: Seq[(String, Expression)]) = {
    Seq(
      WDefInstance(NoInfo, cntrFile.name, modName, cntrFile.tpe),
      Connect(NoInfo, wsub(cntrFile, "clock"), wref("clock")), // FIXME?
      Connect(NoInfo, wsub(cntrFile, "reset"), wref("reset")), // FIXME?
      Connect(NoInfo, wsub(wsub(cntrFile, "io"), "fire"), targetFire),
      Connect(NoInfo, wref(topOutPort.name, topOutPort.tpe), wsub(wsub(cntrFile, "io"), "outs"))
    ) ++ (ports map { case (name, exp) =>
      Connect(NoInfo, wsub(wsub(wsub(cntrFile, "io"), "ins"), name), exp)
    })
  }

  private def getInstancePaths(graph: InstanceGraph, mod: String) =
    graph findInstancesInHierarchy mod map (_ map (_.name) mkString ".")

  var portSize = 0 // FIXME:
  def addCounters(meta: StroberMetaData,
                  graph: InstanceGraph,
                  main: String)
                 (m: DefModule) = m match {
    case m: ExtModule => Seq(m)
    case m: Module =>
      val paths = getInstancePaths(graph, m.name)
      val signalSet = new SignalSet

      paths foreach { path =>
        signalSet ++= (signals getOrElse (path, Nil))
      }

      val hammingDists = new HammingDists
      val namespace = Namespace(m)
      val netlist = Netlist(m)
      val clockPort = (m.ports find (_.tpe == ClockType)).get // There must be!
      val clock = wref(clockPort.name, clockPort.tpe)
      val hdStmts = new Statements
      val stmts =
        (addHDUnitsPorts(signalSet, hammingDists, hdStmts, namespace, clock)(m.ports) :+
         addHDUnitsStmt(signalSet, hammingDists, hdStmts, namespace, netlist, clock)(m.body)) ++
        hdStmts.toSeq

      if (m.name == main) {
        val exps = signalPaths.toSeq map (
          (hammingDists map { case (name, e) => s"${main}.${name}" -> e }) ++
          (meta.childInsts(main) flatMap { child =>
            val childMod = meta.instModMap(child -> main)
            portMaps getOrElse (childMod, Nil) map { case (name, e) =>
              (s"${main}.${child}.${name}" ->
               wsub(wref(child), e.name).copy(tpe = e.tpe)) }
          })
        ).toMap
        val ports = (signalPaths.toSeq zip exps) map {
          case (p, e) => (p replace (".", "_")) -> bitWidth(e.tpe).toInt }
        val cntrFile = generateCntrFile(ports)
        val cntrFileRef = wref(namespace newName "cntrFile", module_type(cntrFile))
        val topOutPortName = namespace newName "toggleCntrs"
        val topOutPortType = VectorType(UIntType(IntWidth(32)), ports.size)
        val topOutPort = Port(NoInfo, topOutPortName, Output, topOutPortType)
        portSize = ports.size
        Seq(cntrFile, m.copy(
          ports = m.ports :+ topOutPort, 
          body = Block(stmts.toSeq ++ connectHDsToCntrFile(
            cntrFile.name, cntrFileRef, topOutPort, ports.unzip._1 zip exps))
        ))
      } else {
        Seq(m.copy(
          body = Block(stmts.toSeq ++ connectHDsToPorts(m.name, meta, hammingDists, namespace)),
          ports = m.ports ++ (portMaps(m.name) map {
            case (_, port) => Port(NoInfo, port.name, Output, port.tpe) })
        ))
      }
  }

  def execute(state: CircuitState) = {
    val graph = new InstanceGraph(state.circuit)
    state.annotations foreach {
      case AddToggleCountersAnnotation(model) =>
        val bboxes = (state.circuit.modules collect {
          case m: ExtModule => m } foldLeft Set[String]())(
          (res, m) => res ++ getInstancePaths(graph, m.name))
        readModel(model, state.circuit.main, bboxes)
      case _ =>
    }
    if (signals.isEmpty) state else {
      val meta = StroberMetaData(state)
      val modules1 = postorder(state.circuit, meta)(connectClocks)
      val modules2 = postorder(modules1, state.circuit.main, meta)(
        addCounters(meta, graph, state.circuit.main))
      val hdUnits = hdUnitCache.values.toSeq

      val widgetAnno = ToggleCounterWidgetAnnotation(portSize)
      state.copy(
        circuit = renameMods(state.circuit.copy(
          modules = hdUnits ++ modules2), Namespace()),
        annotations = AnnotationSeq(state.annotations.toSeq :+ widgetAnno))
    }
  }
}
