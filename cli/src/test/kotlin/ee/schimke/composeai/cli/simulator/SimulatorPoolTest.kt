package ee.schimke.composeai.cli.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class SimulatorPoolTest {

  @Test
  fun `a run's own properties go in the go line, the rest is the standby's command`() {
    val command =
      listOf(
        "java",
        "-Dcomposeai.simulator.instrument=/c/test.jar",
        "-Dcomposeai.simulator.instrument.tests=a.A#one",
        "-Dcomposeai.simulator.instrument.json=/out/r=1.json",
        "-Dcomposeai.simulator.instrument.shard=0/2",
        "-cp",
        "x.jar",
        "Main",
      )
    val (base, go) = SimulatorPool.split(command)

    assertEquals(
      listOf("java", "-Dcomposeai.simulator.instrument=/c/test.jar", "-cp", "x.jar", "Main"),
      base,
    )
    assertEquals(
      mapOf(
        "composeai.simulator.instrument.tests" to "a.A#one",
        "composeai.simulator.instrument.json" to "/out/r=1.json",
        "composeai.simulator.instrument.shard" to "0/2",
      ),
      go,
    )
  }

  @Test
  fun `two runs of one suite share a standby whatever tests they pick`() {
    fun command(tests: String) =
      listOf("java", "-Dcomposeai.simulator.instrument.tests=$tests", "Main")
    assertEquals(
      SimulatorPool.split(command("a.A")).first,
      SimulatorPool.split(command("b.B#two")).first,
    )
  }
}
