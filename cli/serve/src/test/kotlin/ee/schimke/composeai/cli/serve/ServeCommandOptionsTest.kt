package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeCommandOptionsTest {
  @Test
  fun `constructor normalises network and capacity arguments`() {
    val options =
      options(
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

    assertTrue(options.lan)
    assertEquals(ServeUrls.ALL_INTERFACES, options.host)
    assertEquals(9090, options.requestedPort)
    assertEquals(0, options.liveSeats)
    assertEquals(listOf("main", "release/*"), options.revisionAllowRefs)
    assertEquals(listOf("artifacts.example", "cdn.example"), options.acceptBundlesFrom)
    assertTrue(options.exitWhenIdle)
    assertEquals(45L, options.idleExitSeconds)
    assertEquals(2500, options.catalogMaxImages)
  }

  @Test
  fun `defaults remain loopback token gated and non discovering`() {
    val options = options(emptyList())

    assertEquals(ServeUrls.LOOPBACK, options.host)
    assertFalse(options.lan)
    assertFalse(options.public)
    assertFalse(options.discover)
    assertFalse(options.allowRenderTrusted)
    assertEquals(ServeCatalogStore.DEFAULT_MAX_IMAGES, options.catalogMaxImages)
  }

  private fun options(args: List<String>): ServeCommandOptions =
    ServeCommandOptions(
      args = args,
      defaultTimeoutSeconds = 600L,
      previewMatcher = { _, _, _, _, _, _ -> true },
    )
}
