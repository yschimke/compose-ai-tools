package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeCatalogStore
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

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.field(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
