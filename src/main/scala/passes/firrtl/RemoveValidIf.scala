package dessert
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import Utils.throwInternalError

// FIXME: Custom RemoveValidIf to work around for IsInvalids of macros

/** Remove ValidIf and replace IsInvalid with a connection to zero */
object RemoveValidIf extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  val UIntZero = Utils.zero
  val SIntZero = SIntLiteral(BigInt(0), IntWidth(1))
  val ClockZero = DoPrim(PrimOps.AsClock, Seq(UIntZero), Seq.empty, ClockType)
  val FixedZero = FixedLiteral(BigInt(0), IntWidth(1), IntWidth(0))

  /** Returns an [[Expression]] equal to zero for a given [[GroundType]]
    * @note Accepts [[Type]] but dyanmically expects [[GroundType]]
    */
  def getGroundZero(tpe: Type): Expression = tpe match {
    case _: UIntType => UIntZero
    case _: SIntType => SIntZero
    case ClockType => ClockZero
    case _: FixedType => FixedZero
    case other => throwInternalError(s"Unexpected type $other")
  }

  // Recursive. Removes ValidIfs
  private def onExp(e: Expression): Expression = {
    e map onExp match {
      case ValidIf(_, value, _) => value
      case x => x
    }
  }

  // Recursive. Replaces IsInvalid with connecting zero
  private def onStmt(s: Statement): Statement = s map onStmt map onExp match {
    case invalid @ IsInvalid(info, loc) => loc.tpe match {
      case _: AnalogType => EmptyStmt
      case tpe => Connect(info, loc, getGroundZero(tpe))
    }
    case other => other
  }

  private def onModule(srams: Set[String])(m: DefModule) =
    if (srams(m.name)) m else m map onStmt

  private def run(c: Circuit, srams: Set[String]) =
    c.copy(modules = c.modules map onModule(srams))

  def execute(state: CircuitState) = {
    val srams = (state.annotations foldLeft Set[String]()){
      case (res, barstools.macros.MacroCompilerAnnotation(state.circuit.main, p)) =>
        val str = scala.io.Source.fromFile(new java.io.File(p.mem)).mkString
        mdf.macrolib.Utils.readMDFFromString(str) match {
          case None => res
          case Some(macros) => res ++ (macros collect {
            case x: mdf.macrolib.SRAMMacro => x.name })
        }
      case (res, _) => res
    }
    state.copy(circuit = run(state.circuit, srams))
  }
}
