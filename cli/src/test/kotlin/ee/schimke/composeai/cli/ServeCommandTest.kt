package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeBackgroundWork
import ee.schimke.composeai.cli.serve.ServeCatalogStore
import ee.schimke.composeai.cli.serve.ServeRunner
import ee.schimke.composeai.cli.serve.ServeUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeCommandTest {
  @Test
  fun `serve constructor normalises its network and capacity arguments`() {
    val command =
      ServeCommand(
        listOf(
          "--lan",
          "--host",
          "ignored.example",
          "--port=9090",
          "--live-seats",
          "-4",
          "--revisions-allow",
          " main, release/*, ,",
          "--accept-bundles-from",
          "artifacts.example, cdn.example",
          "--exit-when-idle=45",
          "--catalog-max-images",
          "2500",
        )
      )

    assertTrue(command.field("lan"))
    assertEquals(ServeUrls.ALL_INTERFACES, command.field<String>("host"))
    assertEquals(9090, command.field<Int>("requestedPort"))
    assertEquals(0, command.field<Int>("liveSeats"))
    assertEquals(listOf("main", "release/*"), command.field<List<String>>("revisionAllowRefs"))
    assertEquals(
      listOf("artifacts.example", "cdn.example"),
      command.field<List<String>>("acceptBundlesFrom"),
    )
    assertTrue(command.field("exitWhenIdle"))
    assertEquals(45L, command.field<Long>("idleExitSeconds"))
    assertEquals(2500, command.field<Int>("catalogMaxImages"))
  }

  @Test
  fun `serve defaults remain loopback token gated and non discovering`() {
    val command = ServeCommand(emptyList())

    assertEquals(ServeUrls.LOOPBACK, command.field<String>("host"))
    assertFalse(command.field("lan"))
    assertFalse(command.field("public"))
    assertFalse(command.field("discover"))
    assertFalse(command.field("allowRenderTrusted"))
    assertEquals(ServeCatalogStore.DEFAULT_MAX_IMAGES, command.field<Int>("catalogMaxImages"))
  }

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
    val runner = ServeRunner(this)
    return (runner.javaClass
        .getDeclaredField("backgroundWork\$delegate")
        .apply { isAccessible = true }
        .get(runner) as Lazy<ServeBackgroundWork>)
      .value
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.field(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
