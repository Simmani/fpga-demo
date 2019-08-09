package dessert
package rocketchip

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, AssemblyTestSuite, BenchmarkTestSuite, RegressionTestSuite}
import freechips.rocketchip.subsystem.RocketTilesKey
import collection.mutable.LinkedHashSet

private object FPBenchmarks extends BenchmarkTestSuite(
    "rvd",
    "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks",
    LinkedHashSet("spmv", "dgemm", "sgemm", "mt-vvadd")
)

private object LargeBenchmarkTestSuite extends BenchmarkTestSuite(
    "rv-large",
    "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks",
    LinkedHashSet("median", "qsort", "vvadd", "spmv",
                  "dgemm", "sgemm") map (x => s"$x-large")) {
  override def kind = "large-bmark"
}

private object HwachaBenchmarkTestSuite extends BenchmarkTestSuite(
    "hwacha",
    "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks",
    LinkedHashSet("vec-saxpy", "vec-daxpy", "vec-hsaxpy", "vec-sdaxpy",
                  "vec-sgemm-opt", "vec-dgemm-opt", "vec-hgemm-opt",
                  "vec-hsgemm-opt", "vec-sdgemm-opt",
                  "vec-vvadd"))

private object HwachaLargeBenchmarkTestSuite extends BenchmarkTestSuite(
    "hwacha-large",
    "$(RISCV)/riscv64-unknown-elf/share/riscv-tests/benchmarks",
    LinkedHashSet("vec-saxpy", "vec-daxpy", "vec-hsaxpy", "vec-sdaxpy",
                  "vec-sgemm-opt", "vec-dgemm-opt", "vec-hgemm-opt",
                  "vec-hsgemm-opt", "vec-sdgemm-opt",
                  "vec-vvadd") map (x => s"$x-large")) {
  override def kind = "large-bmark"
}

trait HasTestSuites {
  val simpleNames = collection.mutable.LinkedHashSet("simple")

  def addTestSuites(params: Parameters) {
    val coreParams = params(RocketTilesKey).head.core
    val env = if (coreParams.useVM) "v" else "p"
    if (coreParams.useVM) TestGeneration.addSuite(
      new AssemblyTestSuite("rv64ui", simpleNames)("v"))
    TestGeneration.addSuite(rv64ui("p"))
    TestGeneration.addSuite(rv64um("p"))
    TestGeneration.addSuite(rv64uf("p"))
    TestGeneration.addSuite(rv64ud("p"))
    TestGeneration.addSuite(rv64ua(env))
    if (coreParams.useCompressed) TestGeneration.addSuite(rv64uc(env))
    val rv64mi = new AssemblyTestSuite("rv64mi", rv32miNames)(_) // No breakpoint
    TestGeneration.addSuite(rv64mi("p"))
    TestGeneration.addSuite(rv64si("p"))
    TestGeneration.addSuite(benchmarks)
    TestGeneration.addSuite(FPBenchmarks)
    TestGeneration.addSuite(LargeBenchmarkTestSuite)

    if (scala.util.Try(params(hwacha.HwachaNLanes)).getOrElse(0) > 0) {
      import hwacha.HwachaTestSuites._
      val rv64uvNames = hwacha.HwachaTestSuites.rv64uvNames filterNot (_ contains "amo")
      val rv64uvBasic = new AssemblyTestSuite("rv64uv", rv64uvNames)(_)
      val rv64ufVecNames = hwacha.HwachaTestSuites.rv64ufVecNames -- Set("fcmp", "fcvt", "fcvt_w")
      val rv64ufVec = new hwacha.VectorAssemblyTestSuite("rv64uf", rv64ufVecNames)(_)
      val rv64udVec = new hwacha.VectorAssemblyTestSuite("rv64ud", rv64ufVecNames)(_)
      val rv64ufScalarVecNames = hwacha.HwachaTestSuites.rv64ufScalarVecNames -- Set("fcmp", "fcvt", "fcvt_w")
      val rv64ufScalarVec = new hwacha.ScalarVectorAssemblyTestSuite("rv64uf", rv64ufScalarVecNames)(_)
      val rv64udScalarVec = new hwacha.ScalarVectorAssemblyTestSuite("rv64ud", rv64ufScalarVecNames)(_)

      val rv64uv = List(rv64uvBasic,
        rv64umVec, rv64umScalarVec,
        rv64ufVec, rv64ufScalarVec,
        rv64udVec, rv64udScalarVec)
      TestGeneration.addSuites(rv64uv map (_("p")))
      TestGeneration.addSuite(rv64sv("p"))
      TestGeneration.addSuite(HwachaBenchmarkTestSuite)
      TestGeneration.addSuite(HwachaLargeBenchmarkTestSuite)
    }
  }
}
