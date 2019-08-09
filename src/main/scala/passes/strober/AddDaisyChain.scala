// See LICENSE for license details.

package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils._
import firrtl.passes.MemPortUtils._
import WrappedExpression.weq
import midas.passes._
import strober.core._
import dessert.passes.MemInstance
import java.io.StringWriter

class AddDaisyChains(
    meta: StroberMetaData)
   (implicit param: freechips.rocketchip.config.Parameters) extends firrtl.passes.Pass {
  override def name = "[strober] Add Daisy Chains"

  implicit def expToString(e: Expression): String = e.serialize

  private def generateChain(chainGen: () => chisel3.Module,
                            namespace: Namespace,
                            chainMods: DefModules)
                           (implicit chainType: ChainType.Value) = {
    val chirrtl = Parser parse (chisel3.Driver emit chainGen)
    val circuit = renameMods((new MiddleFirrtlCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), Nil)).circuit, namespace)
    chainMods ++= circuit.modules
    Seq(WDefInstance(NoInfo, chainRef.name, circuit.main, ut),
        IsInvalid(NoInfo, chainRef))
  }
 
  private def chainRef(implicit chainType: ChainType.Value) =
    wref(s"${chainType.toString.toLowerCase}_chain", ut, InstanceKind)

  private def chainIo(implicit chainType: ChainType.Value) =
    wsub(chainRef, "io")

  private def chainDataIo(pin: String)(implicit chainType: ChainType.Value) =
    wsub(wsub(chainIo, "dataIo"), pin)

  private def daisyPort(pin: String)(implicit chainType: ChainType.Value) =
    wsub(wsub(wref("daisy"), s"${chainType.toString.toLowerCase}"), pin)

  private def childDaisyPort(child: String)(pin: String)(implicit chainType: ChainType.Value) =
    wsub(wsub(wsub(wref(child), "daisy"), s"${chainType.toString.toLowerCase}"), pin)

  type DefModules = collection.mutable.ArrayBuffer[DefModule]
  type Statements = collection.mutable.ArrayBuffer[Statement]
  type Readers = collection.mutable.HashMap[String, (Seq[String], Seq[String], Expression)]
  type ChainModSet = collection.mutable.HashSet[String]

  private def smallMem(s: DefMemory) =
    s.readLatency == 0 && 2 < s.depth && s.depth < 31
  private def bigRegFile(s: DefMemory) =
    s.readLatency == 0 && s.depth >= 31

  private def collect(
      chainType: ChainType.Value,
      chains: Statements,
      mname: String)
     (s: Statement): Statement = {
    chainType match {
      // TODO: do bfs from inputs
      case ChainType.Regs => s match {
        case s: DefRegister =>
          if (!meta.dontTouches(s.name)) chains += s
          else println(s"no scan: ${mname}.${s.name}")
        case s: DefMemory if s.readLatency == 0 && !smallMem(s) && !bigRegFile(s) =>
          if (!meta.dontTouches(s.name)) chains += s
          else println(s"no scan: ${mname}.${s.name}")
        case _ =>
      }
      case ChainType.Mems => s match {
        case s: DefMemory if smallMem(s) =>
          chains += s
        case _ =>
      }
      case ChainType.RegFile => s match {
        case s: MemInstance => s.mem match {
          case None =>
          case Some(mem) => chains += mem
        }
        case s: DefMemory if bigRegFile(s) =>
          chains += s
        case _ =>
      }
      case ChainType.SRAM => s match {
        case s: MemInstance if s.sram.isDefined =>
          chains += s
        case s: DefMemory if s.readLatency == 1 =>
          chains += s
        case s: DefMemory if s.readLatency > 0 =>
          error("${s.info}: This type of memory is not supported")
        case _ =>
      }
      case ChainType.Trace => s match {
        case s: MemInstance if s.sram.isDefined =>
          chains += s
        case s: DefMemory if s.readLatency == 1 =>
          chains += s
        case _ =>
      }
    }
    s map collect(chainType, chains, mname)
  }

  private def insertRegChains(m: Module,
                              namespace: Namespace,
                              netlist: Netlist,
                              readers: Readers,
                              chainMods: DefModules,
                              hasChain: ChainModSet)
                              (implicit chainType: ChainType.Value) = {
    def sumWidths(stmts: Statements): Int = (stmts foldLeft 0){
      case (sum, s: DefRegister) =>
        sum + bitWidth(s.tpe).toInt
      case (sum, s: DefMemory) if s.readLatency == 0 =>
        sum + bitWidth(s.dataType).toInt * s.depth
      case (sum, s: DefMemory) if s.readLatency == 1 =>
        sum + bitWidth(s.dataType).toInt * (s.readers.size + s.readwriters.size)
      case (sum, s: MemInstance) =>
        val sram = s.sram.get
        sum + sram.width * (sram.ports filter (_.output.nonEmpty)).size
    }
    def dataConnects(regs: Seq[Expression], daisyLen: Int, daisyWidth: Int) = {
      (((0 until daisyLen) foldRight (Seq[Connect](), 0, 0)){case (i, (stmts, index, offset)) =>
        def loop(total: Int, index: Int, offset: Int, wires: Seq[Expression]): (Int, Int, Seq[Expression]) = {
          (daisyWidth - total) match {
            case 0 => (index, offset, wires)
            case margin if index < regs.size =>
              val reg = regs(index)
              val width = bitWidth(reg.tpe).toInt - offset
              if (width <= margin) {
                loop(total + width, index + 1, 0, wires :+ bits(reg, width-1, 0))
              } else {
                loop(total + margin, index, offset + margin, wires :+ bits(reg, width-1, width-margin))
              }
            case margin =>
              loop(total + margin, index, offset, wires :+ UIntLiteral(0, IntWidth(margin)))
          }
        }
        val (idx, off, wires) = loop(0, index, offset, Nil)
        // "<daisy_chain>.io.dataIo.data[i] <- cat(wires)"
        (stmts :+ Connect(NoInfo, widx(chainDataIo("data"), i), cat(wires)), idx, off)
      })._1
    }
    val loadCond = wsub(chainDataIo("load"), "valid")
    def loadConnects(regs: Seq[Expression], daisyLen: Int, daisyWidth: Int) = {
      ((regs foldLeft (Seq[Statement](), daisyLen - 1, daisyWidth)){case ((stmts, idx, offset), reg) =>
        val regWidth = bitWidth(reg.tpe).toInt
        def loop(total: Int, i: Int, offset: Int, wires: Seq[Expression]): (Int, Int, Seq[Expression]) = {
          val port = widx(wsub(chainDataIo("load"), "bits"), i)
          (regWidth - total) match {
            case 0 => (i, offset, wires)
            case margin if margin < offset =>
              loop(total + margin, i, offset - margin, wires :+ bits(port, offset - 1, offset - margin))
            case margin =>
              loop(total + offset, i - 1, daisyWidth, wires :+ bits(port, offset - 1, 0))
          }
        }
        val (i, off, wires) = loop(0, idx, offset, Nil)
        val data = reg.tpe match {
          case t: UIntType => cat(wires)
          case t: SIntType => DoPrim(PrimOps.AsSInt, Seq(cat(wires)), Nil, t)
        }
        val stmt = kind(reg) match {
          case ExpKind =>
            Conditionally(NoInfo, loadCond, Connect(NoInfo, reg, data), EmptyStmt)
          case MemKind => chainType match {
            case ChainType.Regs => Connect(NoInfo, reg, data)
            case ChainType.Trace => EmptyStmt
          }
          case InstanceKind => EmptyStmt
        }
        (stmts :+ stmt, i, off)
      })._1
    }

    val chainElems = new Statements
    collect(chainType, chainElems, m.name)(m.body)
    meta.chains(chainType)(m.name) = chainElems
    if (chainElems.isEmpty) Nil
    else {
      lazy val chain = new RegChain()(param alterPartial ({
        case DataWidth => sumWidths(chainElems) }))
      val instStmts = generateChain(() => chain, namespace, chainMods)
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val portConnects = Seq(
        // <daisy_chain>.clock <- clock
        Connect(NoInfo, wsub(chainRef, "clock"), clock.get),
        // <daisy_chain>.reset <- daisyReset
        Connect(NoInfo, wsub(chainRef, "reset"), wref("daisyReset")),
        // <daisy_chain>.io.stall <- not(targetFire)
        Connect(NoInfo, wsub(chainIo, "stall"), not(wref("targetFire"))),
        // <daisy_chain>.io.copy <- <daisy_port>.copy
        Connect(NoInfo, wsub(chainIo, "copy"), daisyPort("copy")),
        // <daisy_chain>.io.load <- daisyPort.load
        Connect(NoInfo, wsub(chainIo, "load"), daisyPort("load")),
        // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
        Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
      )
      hasChain += m.name
      val stmts = new Statements
      val regs = meta.chains(chainType)(m.name) flatMap {
        case s: DefRegister => create_exps(s.name, s.tpe) map (x => (x, x))
        case s: DefMemory => chainType match {
          case ChainType.Regs =>
            val rs = (0 until s.depth) map (i => s"scan_$i")
            val ws = (0 until s.depth) map (i => s"load_$i")
            val mem = s.copy(readers = s.readers ++ rs, writers = s.writers ++ ws)
            val exps = (rs zip ws) map { case (r, w) =>
              create_exps(memPortField(mem, r, "data")) zip
              create_exps(memPortField(mem, w, "data")) }
            readers(s.name) = (rs, ws, loadCond)
            ((0 until exps.head.size) foldLeft Seq[(Expression, Expression)]())(
              (res, i) => res ++ (exps map (_(i))))
          case ChainType.Trace => (
            (s.readers flatMap (r =>
              create_exps(memPortField(s, r, "data")).reverse)) ++
            (s.readwriters flatMap (rw =>
              create_exps(memPortField(s, rw, "rdata")).reverse))
          ) map (x => (x, x))
        }
        case s: MemInstance =>
          val sram = s.sram.get
          val memref = wref(s.name, s.tpe, InstanceKind)
          val memPorts = (create_exps(memref) map (_.serialize)).toSet
          val macroPorts = sram.ports filter (_.output.nonEmpty)
          (macroPorts map (p => wsub(memref, p.output.get.name))
                      filter (memPorts contains _.serialize)
                      map (x => (x, x)))
      }
      stmts ++ instStmts ++ portConnects ++
      dataConnects(regs.unzip._1, chain.daisyLen, chain.daisyWidth) ++
      loadConnects(regs.unzip._2, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertSRAMChains(m: Module,
                               namespace: Namespace,
                               netlist: Netlist,
                               repl: Netlist,
                               chainMods: DefModules,
                               hasChain: ChainModSet)
                               (implicit chainType: ChainType.Value) = {
    def sumWidths(stmts: Statements) = (stmts foldLeft (0, 0, 0)){
      case ((sum, max, depth), s: DefMemory) =>
        val width = bitWidth(s.dataType).toInt
        (sum + width, max max width, depth max s.depth)
      case ((sum, max, depth), s: MemInstance) =>
        val sram = s.sram.get
        (sum + sram.width, max max sram.width, depth max sram.depth)
    }
    trait DaisyKind
    case object DaisyScan extends DaisyKind
    case object DaisyLoad extends DaisyKind
    def daisyConnect(stmts: Statements, daisyKind: DaisyKind)(
                     elem: (Statement, Int)): Seq[Expression] = {
      val (data, addr, ce, en, mask) = (elem._1: @unchecked) match {
        case s: MemInstance =>
          val sram = s.sram.get
          val memref = wref(s.name, s.tpe, InstanceKind)
          val memPorts = (create_exps(memref) map (_.serialize)).toSet
          val port = daisyKind match {
            case DaisyScan => (sram.ports filter (_.output match {
              case None => false
              case Some(p) => memPorts contains wsub(memref, p.name).serialize
            })).head
            case DaisyLoad => (sram.ports filter (_.input match {
              case None => false
              case Some(p) => memPorts contains wsub(memref, p.name).serialize
            })).head
          }
          val enable = daisyKind match {
            case DaisyScan => port.readEnable
            case DaisyLoad => port.writeEnable
          }
          (wsub(memref, daisyKind match {
            case DaisyScan => port.output.get.name
            case DaisyLoad => port.input.get.name
           }),
           wsub(memref, port.address.name),
           port.chipEnable match {
             case Some(ce) => wsub(memref, ce.name) -> inv(ce.polarity)
             case None => EmptyExpression -> false
           },
           enable match {
             case Some(en) => wsub(memref, en.name) -> inv(en.polarity)
             case None => EmptyExpression -> false
           },
           (port.maskPort, daisyKind) match {
             case (Some(mask), DaisyLoad) =>
               Seq(wsub(memref, mask.name)) -> inv(mask.polarity)
             case _ => Nil -> false
           })
        case s: DefMemory => daisyKind match {
          case DaisyScan if s.readers.nonEmpty => (
            memPortField(s, s.readers.head, "data"),
            memPortField(s, s.readers.head, "addr"),
            memPortField(s, s.readers.head, "en") -> false,
            EmptyExpression -> false,
            Nil -> false
          )
          case DaisyScan => (
            memPortField(s, s.readwriters.head, "rdata"),
            memPortField(s, s.readwriters.head, "addr"),
            memPortField(s, s.readwriters.head, "en") -> false,
            not(memPortField(s, s.readwriters.head, "wmode")) -> false,
            Nil -> false
          )
          case DaisyLoad if s.writers.nonEmpty => (
            memPortField(s, s.writers.head, "data"),
            memPortField(s, s.writers.head, "addr"),
            memPortField(s, s.writers.head, "en") -> false,
            EmptyExpression -> false,
            create_exps(memPortField(s, s.writers.head, "mask")) -> false
          )
          case DaisyLoad => (
            memPortField(s, s.readwriters.head, "wdata"),
            memPortField(s, s.readwriters.head, "addr"),
            memPortField(s, s.readwriters.head, "en") -> false,
            memPortField(s, s.readwriters.head, "wmode") -> false,
            create_exps(memPortField(s, s.readwriters.head, "wmask")) -> false
          )
        }
      }
      val addrIn = wsub(widx(wsub(chainIo, "addrIo"), elem._2), "in")
      val addrOut = wsub(widx(wsub(chainIo, "addrIo"), elem._2), "out")
      val addrValid = daisyKind match {
        case DaisyScan => wsub(addrOut, "valid")
        case DaisyLoad => wsub(chainDataIo("load"), "valid")
      }
      def memPortConnects(s: Statement): Statement = {
        s match {
          case Connect(info, loc, expr) => kind(loc) match {
            case MemKind | InstanceKind if weq(loc, ce._1) =>
              val locs = loc.serialize
              val exprx = repl getOrElse (locs, expr)
              repl(locs) =
                if (ce._2) and(not(addrValid), exprx) /* inverted port */
                else or(addrValid, exprx)
            case MemKind | InstanceKind if weq(loc, en._1) =>
              val locs = loc.serialize
              val exprx = repl getOrElse (locs, expr)
              repl(locs) =
                if (en._2) and(not(addrValid), exprx) /* inverted port */
                else or(addrValid, exprx)
            case MemKind | InstanceKind if mask._1 exists (weq(loc, _)) =>
              val locs = loc.serialize
              val width = bitWidth(loc.tpe).toInt
              val exprx = repl getOrElse (locs, expr)
              repl(locs) =
                if (mask._2) and(cat(Seq.fill(width)(not(addrValid))), exprx) /* inverted port */
                else or(cat(Seq.fill(width)(addrValid)), exprx)
            case MemKind | InstanceKind if weq(loc, addr) =>
              val locs = loc.serialize
              val exprx = repl getOrElse (locs, expr)
              repl(locs) = Mux(addrValid, wsub(addrOut, "bits"), exprx, ut)
            case _ =>
          }
          case _ =>
        }
        s map memPortConnects
      }
      memPortConnects(m.body)
      (chainType, daisyKind) match {
        case (ChainType.SRAM, DaisyScan) => stmts ++= Seq(
          // <daisy_chain>.io.addr[i].in.bits <- <memory>.data
          Connect(NoInfo, wsub(addrIn, "bits"), netlist(addr)),
          // <daisy_chain>.io.addr[i].in.valid <- <memory>.en
          Connect(NoInfo, wsub(addrIn, "valid"), (ce._1, en._1) match {
            case (EmptyExpression, ex) =>
              if (en._2) not(netlist(ex)) else netlist(ex)
            case (ex, EmptyExpression) =>
              if (ce._2) not(netlist(ex)) else netlist(ex)
            case (ex1, ex2) => and(
              if (ce._2) not(netlist(ex1)) else netlist(ex1),
              if (en._2) not(netlist(ex1)) else netlist(ex1))
          }))
        case _ =>
      }
      create_exps(data).reverse
    }

    def daisyConnects(elems: Statements, width: Int, daisyLen: Int, daisyWidth: Int): Seq[Statement] = {
      val stmts = new Statements
      val dataCat = cat(elems.zipWithIndex flatMap daisyConnect(stmts, DaisyScan))
      val dataConnects = {
        ((0 until daisyLen foldRight (Seq[Connect](), width - 1)){ case (i, (stmts, high)) =>
          val low = (high - daisyWidth + 1) max 0
          val input = bits(dataCat, high, low)
          (stmts :+ (daisyWidth - (high - low + 1) match {
            case 0 =>
              // <daisy_chain>.io.dataIo.data[i] <- <memory>.data(high, low)
              Connect(NoInfo, widx(chainDataIo("data"), i), input)
            case margin =>
              val pad = UIntLiteral(0, IntWidth(margin))
              // <daisy_chain>.io.dataIo.data[i] <- cat(<memory>.data(high, low), pad)
              Connect(NoInfo, widx(chainDataIo("data"), i), cat(Seq(input, pad)))
          }), high - daisyWidth)
        })._1
      }
      val loads = elems.zipWithIndex flatMap daisyConnect(stmts, DaisyLoad)
      val loadPort = cat(((daisyLen - 1) to 0 by -1) map (i => widx(wsub(chainDataIo("load"), "bits"), i)))
      val loadConnects = {
        ((loads foldLeft (Seq[Conditionally](), daisyLen * daisyWidth - 1)){ case ((stmts, high), load) =>
          val low = high - bitWidth(load.tpe).toInt + 1
          val data = load.tpe match {
            case t: UIntType =>
              bits(loadPort, high, low)
            case t: SIntType =>
              DoPrim(PrimOps.AsSInt, Seq(bits(loadPort, high, low)), Nil, t)
          }
          // <memory>.data <- <daisy_chain>.io.dataIo.load.bits
          val stmt = Conditionally(NoInfo, wsub(chainDataIo("load"), "valid"),
            Connect(NoInfo, load, data), EmptyStmt)
          assert(low >= 0)
          (stmts :+ stmt, low - 1)
        })._1
      }
      dataConnects ++ loadConnects ++ stmts
    }

    val chainElems = new Statements
    collect(chainType, chainElems, m.name)(m.body)
    meta.chains(chainType)(m.name) = chainElems
    if (chainElems.isEmpty) Nil
    else {
      val (sum, max, depth) = sumWidths(chainElems)
      lazy val chain = new SRAMChain()(param alterPartial ({
        case DataWidth => sum
        case MemWidth  => max
        case MemDepth  => depth
        case MemNum    => chainElems.size
        case SeqRead   => chainType == ChainType.SRAM
      }))
      val instStmts = generateChain(() => chain, namespace, chainMods)
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val portConnects = Seq(
        // <daisy_chain>.clock <- clock
        Connect(NoInfo, wsub(chainRef, "clock"), clock.get),
        // <daisy_chain>.reset <- daisyReset
        Connect(NoInfo, wsub(chainRef, "reset"), wref("daisyReset")),
        // <daisy_chain>.io.stall <- not(targetFire)
        Connect(NoInfo, wsub(chainIo, "stall"), not(wref("targetFire"))),
        // <daisy_chain>.io.copy <- <daisy_port>.copy
        Connect(NoInfo, wsub(chainIo, "copy"), daisyPort("copy")),
        // <daisy_chain>.io.load <- daisyPort.load
        Connect(NoInfo, wsub(chainIo, "load"), daisyPort("load")),
         // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
        Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
      )
      hasChain += m.name
      instStmts ++ portConnects ++ daisyConnects(chainElems, sum, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertChains(m: Module,
                           namespace: Namespace,
                           netlist: Netlist,
                           readers: Readers,
                           repl: Netlist,
                           chainMods: DefModules,
                           hasChainMap: Map[ChainType.Value, ChainModSet])
                           (t: ChainType.Value) = {
    implicit val chainType = t
    val hasChain = hasChainMap(chainType)
    val chainStmts = chainType match {
      case ChainType.Regs | ChainType.Trace =>
        insertRegChains(m, namespace, netlist, readers, chainMods, hasChain)
      case ChainType.Mems | ChainType.RegFile | ChainType.SRAM =>
        insertSRAMChains(m, namespace, netlist, repl, chainMods, hasChain)
    }
    // Filter children who have daisy chains
    val childrenWithChains = meta.childInsts(m.name) filter { x =>
      val childMod = meta.instModMap(x, m.name)
      hasChain(childMod) &&
      !meta.dontTouchMods(childMod) &&
      !meta.dontTouchInsts(x -> m.name)
    }
    val invalids = childrenWithChains flatMap (c => Seq(
      IsInvalid(NoInfo, childDaisyPort(c)("in")),
      IsInvalid(NoInfo, childDaisyPort(c)("out"))))
    val connects = (childrenWithChains foldLeft (None: Option[String], Seq[Connect]())){
      case ((None, stmts), child) if !hasChain(m.name) =>
        // <daisy_port>.out <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, daisyPort("out"), childDaisyPort(child)("out")))
      case ((None, stmts), child) =>
        // <daisy_chain>.io.dataIo.in <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, chainDataIo("in"), childDaisyPort(child)("out")))
      case ((Some(p), stmts), child) =>
        // <prev_child>.<daisy_port>.io.in <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, childDaisyPort(p)("in"), childDaisyPort(child)("out")))
    } match {
      case (None, stmts) if !hasChain(m.name) => stmts
      case (None, stmts) =>
        // <daisy_chain>.io.dataIo.in <- <daisy_port>.in
        stmts :+ Connect(NoInfo, chainDataIo("in"), daisyPort("in"))
      case (Some(p), stmts) =>
        // <prev_child>.<daisy_port>.in <- <daisy_port>.in
        stmts :+ Connect(NoInfo, childDaisyPort(p)("in"), daisyPort("in"))
    }
    if (childrenWithChains.nonEmpty) hasChain += m.name
    Block(invalids ++ chainStmts ++ connects)
  }

  def updateStmts(readers: Readers,
                  repl: Netlist,
                  clock: Option[Expression],
                  stmts: Statements)
                  (s: Statement): Statement = s match {
    // Connect copy/load pins
    case s: MemInstance => s
    case s: WDefInstance => Block(Seq(s,
      IsInvalid(NoInfo, wsub(wref(s.name), "daisy")),
      Connect(NoInfo, wsub(wref(s.name), "daisyReset"), wref("daisyReset", BoolType))) ++
      (ChainType.values flatMap (t => Seq(
        Connect(NoInfo, childDaisyPort(s.name)("copy")(t), daisyPort("copy")(t)),
        Connect(NoInfo, childDaisyPort(s.name)("load")(t), daisyPort("load")(t))
      )))
    )
    case s: DefMemory => readers get s.name match {
      case None => s
      case Some((rs, ws, wen)) =>
        val mem = s.copy(readers = s.readers ++ rs, writers = s.writers ++ ws)
        stmts ++= ((rs zip ws).zipWithIndex flatMap { case ((r, w), i) =>
          val addr = UIntLiteral(i, IntWidth(chisel3.util.log2Ceil(s.depth) max 1))
          Seq(Connect(NoInfo, memPortField(mem, r, "clk"), clock.get),
              Connect(NoInfo, memPortField(mem, r, "en"), one),
              Connect(NoInfo, memPortField(mem, r, "addr"), addr),
              Connect(NoInfo, memPortField(mem, w, "clk"), clock.get),
              Connect(NoInfo, memPortField(mem, w, "en"), wen),
              Connect(NoInfo, memPortField(mem, w, "addr"), addr)) ++
          (create_exps(memPortField(mem, w, "mask")) map (mask => Connect(NoInfo, mask, one)))
        })
        mem
    }
    case s: Connect => kind(s.loc) match {
      // Replace sram inputs
      case MemKind | InstanceKind => repl get s.loc.serialize match {
        case Some(expr) => 
          stmts += s.copy(expr = expr)
          EmptyStmt
        case None => s
      }
      case _ => s
    }
    case s => s map updateStmts(readers, repl, clock, stmts)
  }

  private def transform(namespace: Namespace,
                        daisyType: Type,
                        hasChain: Map[ChainType.Value, ChainModSet])
                        (m: DefModule) = m match {
    case m: Module if !(meta.memMods contains m.name) =>
      val chainMods = new DefModules
      val netlist = Netlist(m)
      val readers = new Readers
      val stmts = new Statements
      val repl = new Netlist
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val daisyReset = Port(NoInfo, "daisyReset", Input, BoolType)
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy", daisyType))
      val chainStmts = (ChainType.values.toList map
        insertChains(m, namespace, netlist, readers, repl, chainMods, hasChain))
      val bodyx = updateStmts(readers, repl, clock, stmts)(m.body)
      (m.copy(ports = m.ports ++ Seq(daisyReset, daisyPort), body =
        Block(Seq(daisyInvalid, bodyx) ++ chainStmts ++ stmts)) +: chainMods).toSeq
    case m => Seq(m)
  }

  def run(c: Circuit) = {
    val namespace = Namespace(c)
    val hasChain = (ChainType.values.toList map (_ -> new ChainModSet)).toMap
    val chirrtl = Parser parse (chisel3.Driver emit (() => new core.DaisyBox))
    val daisybox = (new MiddleFirrtlCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), Nil)).circuit
    val daisyType = daisybox.modules.head.ports.find(_.name == "io").get.tpe
    c.copy(modules = postorder(c, meta)(transform(namespace, daisyType, hasChain)))
  }
}
