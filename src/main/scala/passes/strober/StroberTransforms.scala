// See LICENSE for license details.

package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.CompilerUtils.getLoweringTransforms
import strober.core.ChainType
import dessert.passes.MemInstance
import scala.collection.mutable.{ArrayBuffer, ListBuffer, HashMap, HashSet, LinkedHashSet}

case class StroberDontTouchesAnnotation(target: CircuitName, dontTouches: Seq[String])
    extends SingleTargetAnnotation[CircuitName] {
  def duplicate(n: CircuitName) = this.copy(n)
}

case class StroberDontTouchInstanceAnnotation(target: ComponentName)
    extends SingleTargetAnnotation[ComponentName] {
  def duplicate(n: ComponentName) = this.copy(n)
}

case class StroberDontTouchModuleAnnotation(target: ModuleName)
    extends SingleTargetAnnotation[ModuleName] {
  def duplicate(n: ModuleName) = this.copy(n)
}

object StroberMetaData {
  private def collectChildren(
      mname: String,
      meta: StroberMetaData,
      blackboxes: Set[String])
     (s: Statement): Statement = {
    s match {
      case s: MemInstance =>
        meta.childMods(mname) += s.module
        meta.childInsts(mname) += s.name
        meta.instModMap(s.name -> mname) = s.module
        meta.memMods += s.module
      case s: WDefInstance if !blackboxes(s.module) =>
        meta.childMods(mname) += s.module
        meta.childInsts(mname) += s.name
        meta.instModMap(s.name -> mname) = s.module
      case _ =>
    }
    s map collectChildren(mname, meta, blackboxes)
  }

  private def collectChildrenMod(
      meta: StroberMetaData,
      blackboxes: Set[String])
     (m: DefModule) = {
    meta.childInsts(m.name) = ArrayBuffer[String]()
    meta.childMods(m.name) = LinkedHashSet[String]()
    m map collectChildren(m.name, meta, blackboxes)
  }


  def apply(c: Circuit, nobb: Boolean = true): StroberMetaData = {
    val meta = new StroberMetaData
    val blackboxes =
      if (nobb) c.modules collect { case m: ExtModule => m.name } else Nil
    c.modules foreach collectChildrenMod(meta, blackboxes.toSet)
    meta
  }

  def apply(state: CircuitState): StroberMetaData = {
    val meta = apply(state.circuit)
    meta.memMods ++= (state.annotations collect {
      case dessert.passes.MemModuleAnnotation(
        ModuleName(name, CircuitName(state.circuit.main)), _) => name
    })
    meta.dontTouches ++= (state.annotations flatMap {
      case StroberDontTouchesAnnotation(
        CircuitName(state.circuit.main), dontTouches) => dontTouches
      case _ => Nil
    })
    meta.dontTouchMods ++= (state.annotations collect {
      case StroberDontTouchModuleAnnotation(
        ModuleName(name, CircuitName(state.circuit.main))) => name
    })
    meta.dontTouchInsts ++= (state.annotations collect {
      case StroberDontTouchInstanceAnnotation(
        ComponentName(name, ModuleName(mname, CircuitName(state.circuit.main)))) =>
          name -> mname
    })
    meta
  }
}

class StroberMetaData {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]

  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
  val memMods = HashSet[String]()
  val dontTouches = HashSet[String]()
  val dontTouchMods = HashSet[String]()
  val dontTouchInsts = HashSet[(String, String)]()
  
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap
}

object preorder {
  def apply(modules: Seq[DefModule],
            main: String,
            meta: StroberMetaData)
           (visit: DefModule => Seq[DefModule]): Seq[DefModule] = {
    val head = (modules find (_.name == main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      visited += m.name
      visit(m) ++ (modules filter (x => meta.childMods(m.name)(x.name) && !visited(x.name))
        foldLeft Seq[DefModule]())((res, module) => res ++ loop(module))
    }
    loop(head) ++ (modules collect { case m: ExtModule => m })
  }

  def apply(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => Seq[DefModule]): Seq[DefModule] = {
    apply(c.modules, c.main, meta)(visit)
  }
}

object postorder {
  def apply(modules: Seq[DefModule],
            main: String,
            meta: StroberMetaData)
           (visit: DefModule => Seq[DefModule]): Seq[DefModule] = {
    val head = (modules find (_.name == main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      val res = (modules filter (x => meta.childMods(m.name)(x.name))
        foldLeft Seq[DefModule]())((res, module) => res ++ loop(module))
      if (visited(m.name)) {
        res
      } else {
        visited += m.name
        res ++ visit(m)
      }
    }
    loop(head) ++ (modules collect { case m: ExtModule => m })
  }

  def apply(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => Seq[DefModule]): Seq[DefModule] = {
    apply(c.modules, c.main, meta)(visit)
  }
}

class StroberTransforms(dir: java.io.File)
   (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState) = {
    val meta = StroberMetaData(state)
    val xforms =
      if (!param(midas.EnableSnapshot)) Nil
      else Seq(
        new AddDaisyChains(meta),
        new DumpChains(dir, meta))
    (xforms foldLeft state)((in, xform) =>
      xform runTransform in).copy(form=outputForm)
  }
}
