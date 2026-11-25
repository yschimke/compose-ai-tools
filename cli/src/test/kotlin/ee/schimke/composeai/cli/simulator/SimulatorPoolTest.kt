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

  @Test
  fun `a standby is matched on the context the simulator reads, not the whole environment`() {
    val command = listOf("java", "Main")
    val dir = java.io.File("/work")
    val base = mapOf("PATH" to "/usr/bin", "GRADLE_BUILD_ID" to "1")

    assertEquals(
      SimulatorPool.identity(command, dir, base),
      SimulatorPool.identity(command, dir, base + ("GRADLE_BUILD_ID" to "2")),
    )
    kotlin.test.assertNotEquals(
      SimulatorPool.identity(command, dir, base),
      SimulatorPool.identity(command, dir, base + ("PATH" to "/opt/ffmpeg/bin:/usr/bin")),
    )
    kotlin.test.assertNotEquals(
      SimulatorPool.identity(command, dir, base),
      SimulatorPool.identity(command, dir, base + ("COMPOSE_PREVIEW_FFMPEG" to "/x/ffmpeg")),
    )
    kotlin.test.assertNotEquals(
      SimulatorPool.identity(command, dir, base),
      SimulatorPool.identity(command, java.io.File("/other"), base),
    )
  }
}
