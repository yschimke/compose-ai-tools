package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeBackgroundWork
import ee.schimke.composeai.cli.serve.ServeRunner
import kotlin.test.Test
import kotlin.test.assertEquals

class ServeCommandTest {
  /**
   * `--background-renders` widens the passes, not just the permits.
   *
   * A pass takes ONE render permit for the whole of its batch, so the permits are reachable only up
   * to the number of passes admitted. Wiring the render lane while leaving the optimizer lanes at
   * their own default made every permit past the second dead: the flag documented as the way past
   * the derivation's ceiling bought nothing above 2, and the cross-replica coordinator re-imposed
   * the same ceiling on the physical host.
   */
  @Test
  fun `the optimizer lane count follows the background render lane`() {
    val command = ServeCommand(listOf("--background-renders", "5"))

    assertEquals(5, command.backgroundWork().optimizerAdmissionSnapshot().lanes)
  }

  /**
   * `backgroundWork` moved to `ServeRunner` when the server body left `:cli`, so this reflects
   * there rather than on the command.
   *
   * The assertion is unchanged and still spans both halves — a flag parsed by `ServeCommand`
   * reaching a lane count inside the server — which is exactly what makes it worth keeping on this
   * side of the boundary: it is the wiring, not the server, that it checks.
   */
  @Suppress("UNCHECKED_CAST")
  private fun ServeCommand.backgroundWork(): ServeBackgroundWork {
    val runner = ServeRunner(options, this)
    return (runner.javaClass
        .getDeclaredField("backgroundWork\$delegate")
        .apply { isAccessible = true }
        .get(runner) as Lazy<ServeBackgroundWork>)
      .value
  }
}
