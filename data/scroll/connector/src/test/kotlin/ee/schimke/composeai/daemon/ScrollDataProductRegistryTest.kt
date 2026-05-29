package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.scroll.ScrollPreviewExtension
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the daemon's `render/scroll/long` / `render/scroll/gif` registry contract: both kinds are
 * `requiresRerender = true`, missing files surface as
 * [DataProductRegistry.Outcome.RequiresRerender] with the matching `scroll-long` / `scroll-gif`
 * mode, and on-disk artefacts live at `<rootDir>/render-scroll-{long,gif}/<previewId>.{png,gif}` —
 * the same paths the gradle plugin's `composePreviewRenderAll` writes, so the host's
 * `gradleService.readPreviewImage` reads the same files either way.
 */
class ScrollDataProductRegistryTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("scroll-data-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capabilities advertise scroll long and scroll gif as path-transport requiresRerender kinds`() {
    val registry = ScrollDataProductRegistry(rootDir)
    val byKind = registry.capabilities.associateBy { it.kind }
    assertEquals(
      setOf(ScrollPreviewExtension.KIND_LONG, ScrollPreviewExtension.KIND_GIF),
      byKind.keys,
    )
    for (cap in registry.capabilities) {
      assertEquals(DataProductTransport.PATH, cap.transport)
      assertTrue("${cap.kind}: attachable", cap.attachable)
      assertTrue("${cap.kind}: fetchable", cap.fetchable)
      assertTrue("${cap.kind}: requiresRerender", cap.requiresRerender)
    }
    assertEquals(listOf("image/png"), byKind.getValue(ScrollPreviewExtension.KIND_LONG).mediaTypes)
    assertEquals(listOf("image/gif"), byKind.getValue(ScrollPreviewExtension.KIND_GIF).mediaTypes)
  }

  @Test
  fun `missing scroll artefact fetches as RequiresRerender with the matching mode`() {
    val registry = ScrollDataProductRegistry(rootDir)

    val longOutcome =
      registry.fetch(
        previewId = "com.example.HomeKt#LongScroll",
        kind = ScrollPreviewExtension.KIND_LONG,
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.RequiresRerender("scroll-long"), longOutcome)

    val gifOutcome =
      registry.fetch(
        previewId = "com.example.HomeKt#GifScroll",
        kind = ScrollPreviewExtension.KIND_GIF,
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.RequiresRerender("scroll-gif"), gifOutcome)
  }

  @Test
  fun `fetch returns Ok with the on-disk path when the artefact exists`() {
    val previewId = "com.example.HomeKt#LongScroll"
    val longFile =
      rootDir.resolve(ScrollDataProductRegistry.SCROLL_LONG_SUBDIR).resolve("$previewId.png")
    longFile.parentFile.mkdirs()
    longFile.writeBytes(
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    )

    val outcome =
      ScrollDataProductRegistry(rootDir)
        .fetch(
          previewId = previewId,
          kind = ScrollPreviewExtension.KIND_LONG,
          params = null,
          inline = false,
        )
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertEquals(ScrollPreviewExtension.KIND_LONG, ok.result.kind)
    assertEquals(longFile.absolutePath, ok.result.path)
    assertNull("PATH transport must not also carry an inline payload", ok.result.payload)
  }

  @Test
  fun `inline upgrade is rejected for scroll kinds so binary PNG bytes never get parsed as JSON`() {
    // Binary artefacts: `allowInlineUpgrade = false`. Even when the caller asks for inline,
    // fetch still serves the path (matching the a11y overlay's PNG behaviour). Without this,
    // `data/fetch(inline=true)` on `render/scroll/long` would read the PNG and crash the JSON
    // parser with `FetchFailed`.
    val previewId = "com.example.HomeKt#LongScroll"
    val longFile =
      rootDir.resolve(ScrollDataProductRegistry.SCROLL_LONG_SUBDIR).resolve("$previewId.png")
    longFile.parentFile.mkdirs()
    // Real PNG signature plus 4 bytes of garbage — definitely not JSON.
    longFile.writeBytes(
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03)
    )

    val outcome =
      ScrollDataProductRegistry(rootDir)
        .fetch(
          previewId = previewId,
          kind = ScrollPreviewExtension.KIND_LONG,
          params = null,
          inline = true,
        )
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertNotNull(ok.result.path)
    assertNull("inline=true must not promote a binary kind to inline", ok.result.payload)
  }

  @Test
  fun `renderModeFor returns the per-kind scroll mode so the dispatcher routes the right scenario`() {
    val registry = ScrollDataProductRegistry(rootDir)
    assertEquals("scroll-long", registry.renderModeFor(ScrollPreviewExtension.KIND_LONG))
    assertEquals("scroll-gif", registry.renderModeFor(ScrollPreviewExtension.KIND_GIF))
    assertNull(registry.renderModeFor("a11y/atf"))
  }

  @Test
  fun `attachmentsFor surfaces an existing scroll PNG as a path attachment`() {
    val previewId = "com.example.HomeKt#LongScroll"
    val gifFile =
      rootDir.resolve(ScrollDataProductRegistry.SCROLL_GIF_SUBDIR).resolve("$previewId.gif")
    gifFile.parentFile.mkdirs()
    gifFile.writeBytes(byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()))

    val attachments =
      ScrollDataProductRegistry(rootDir)
        .attachmentsFor(
          previewId = previewId,
          kinds = setOf(ScrollPreviewExtension.KIND_LONG, ScrollPreviewExtension.KIND_GIF),
        )
    // LONG file is absent — only GIF appears.
    assertEquals(1, attachments.size)
    val gif = attachments.single()
    assertEquals(ScrollPreviewExtension.KIND_GIF, gif.kind)
    assertEquals(gifFile.absolutePath, gif.path)
    assertNull(gif.payload)
  }

  @Test
  fun `unknown kinds route through the base class to Outcome Unknown`() {
    val outcome =
      ScrollDataProductRegistry(rootDir)
        .fetch(
          previewId = "com.example.HomeKt#LongScroll",
          kind = "a11y/atf",
          params = null,
          inline = false,
        )
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
    // No spurious file lookups for unknown kinds.
    assertFalse(rootDir.resolve(ScrollDataProductRegistry.SCROLL_LONG_SUBDIR).exists())
  }
}
