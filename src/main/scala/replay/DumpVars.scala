package dessert
package replay

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import strober.passes.StroberMetaData
import java.io.{File, FileWriter, Writer}

class DumpVars(dir: File) extends firrtl.passes.Pass {
  override def inputForm = LowForm
  override def outputForm = LowForm

  type Vars = collection.mutable.LinkedHashSet[String]
  type VarsMap = collection.mutable.HashMap[String, Vars]

  def onStmt(vars: Vars)(s: Statement): Statement = {
    s match {
      case s: DefNode if !(s.name startsWith "_GEN") =>
          vars += s.name
      case s: DefWire if !(s.name startsWith "_GEN") =>
          vars += s.name
      case s: DefRegister if !(s.name startsWith "_GEN") =>
          vars += s.name
      case _ =>
    }
    s map onStmt(vars)
  }


  def onModule(vars: Vars)(m: DefModule) {
      vars ++= m.ports map (_.name)
      m map onStmt(vars)
  }


  def loop(f: Writer,
           meta: StroberMetaData,
           vars: VarsMap,
           mod: String,
           path: String) {
    f.write("$dumpvars(1, ")
    f.write(vars(mod) map (v => s"$path.$v") mkString ",")
    f.write(");\n")
    meta.childInsts(mod) foreach { child => loop(
      f, meta, vars, meta.instModMap(child -> mod), s"${path}.${child}")
    }
  }

  def run(c: Circuit) = {
    val vars = new VarsMap
    c.modules foreach (m => onModule(
      vars getOrElseUpdate (m.name, new Vars))(m))

    val meta = strober.passes.StroberMetaData(c, false)
    val f1 = new FileWriter(new File(dir, "dumpvars.vfrag"))
    loop(f1, meta, vars, c.main, "tester.dut")
    f1.close
    val f2 = new FileWriter(new File(dir, "dumpvars-replay.vfrag"))
    loop(f2, meta, vars, c.main, c.main)
    f2.close
    c
  }
}
