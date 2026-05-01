package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v2 click-into-composition end-to-end test — see
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
 *
 * Drives the desktop host's interactive surface against a stateful composable ([ClickToggleSquare])
 * that paints red on first composition and green after one click. The test verifies:
 *
 * 1. **Bootstrap render is red** — the initial frame, with no input dispatched, paints the
 *    `mutableStateOf(false)` branch.
 * 2. **Click dispatch flips state and re-renders green** — `interactive/input` with kind=CLICK,
 *    routed through [DesktopInteractiveSession.dispatch] → `ImageComposeScene.sendPointerEvent` →
 *    `Modifier.clickable {}` → `clicked = true`, then [DesktopInteractiveSession.render] encodes
 *    the post-click composition.
 * 3. **`remember` state survives across renders** — implicit in (2). Without v2's held scene the
 *    second `setUp` would reset the `mutableStateOf` and the second render would paint red, so a
 *    green second render is the load-bearing assertion.
 *
 * Pixel-match helper inlined to avoid a circular dep on the harness's `PixelDiff` (same reasoning
 * as `RenderEngineTest`).
 */
class DesktopInteractiveSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun click_input_flips_state_and_repaints() {
    val outputDir = tempFolder.newFolder("interactive-renders")
    val engine = RenderEngine(outputDir = outputDir)
    // The host needs a previewSpecResolver to enable v2 sessions; for the test we resolve the
    // single fixture previewId we use into a hard-coded RenderSpec.
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickToggleSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "click-toggle-square",
            )
          } else null
        },
      )
    host.start()
    try {
      // Acquire the session — engine.setUp runs, the scene is held warm, classloader installed.
      val session =
        host.acquireInteractiveSession(
          previewId = FIXTURE_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        // 1. Bootstrap render — no input dispatched yet, scene paints the `clicked = false`
        //    branch (red).
        val first = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("first render must produce a pngPath", first.pngPath)
        val firstImage = readPng(File(first.pngPath!!))
        val redMatch = pixelMatchPct(firstImage, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% red pixels on bootstrap render; got ${"%.2f".format(redMatch * 100)}%",
          redMatch >= 0.95,
        )

        // 2. Dispatch a click in the centre of the scene. The whole-card `Modifier.clickable {}`
        //    catches it; the held scene's `mutableStateOf` flips to true.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-stream-1",
            kind = InteractiveInputKind.CLICK,
            pixelX = 32,
            pixelY = 32,
          )
        )

        // 3. Re-render — the second frame should now paint the `clicked = true` branch (green).
        val second = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("second render must produce a pngPath", second.pngPath)
        val secondImage = readPng(File(second.pngPath!!))
        val greenMatch = pixelMatchPct(secondImage, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% green pixels after click; got ${"%.2f".format(greenMatch * 100)}% — " +
            "this is the load-bearing v2 assertion (remember{} state must survive across renders)",
          greenMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun acquire_throws_unsupported_when_resolver_unwired() {
    // No previewSpecResolver passed — host advertises no v2 support and throws
    // UnsupportedOperationException, which `JsonRpcServer.handleInteractiveStart` translates into
    // a clean fall-back to the v1 stateless dispatch path.
    val host = DesktopHost(engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")))
    host.start()
    try {
      val thrown =
        runCatching {
            host.acquireInteractiveSession(
              previewId = FIXTURE_PREVIEW_ID,
              classLoader = DesktopInteractiveSessionTest::class.java.classLoader!!,
            )
          }
          .exceptionOrNull()
      assertNotNull(
        "DesktopHost without a resolver must throw on acquireInteractiveSession",
        thrown,
      )
      assertTrue(
        "expected UnsupportedOperationException; got ${thrown?.javaClass}",
        thrown is UnsupportedOperationException,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquire_throws_unsupported_when_resolver_returns_null() {
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")),
        previewSpecResolver = { _ -> null },
      )
    host.start()
    try {
      val thrown =
        runCatching {
            host.acquireInteractiveSession(
              previewId = "unknown-preview",
              classLoader = DesktopInteractiveSessionTest::class.java.classLoader!!,
            )
          }
          .exceptionOrNull()
      assertTrue(
        "expected UnsupportedOperationException for null resolver result; got ${thrown?.javaClass}",
        thrown is UnsupportedOperationException,
      )
    } finally {
      host.shutdown()
    }
  }

  private fun readPng(file: File): java.awt.image.BufferedImage {
    assertTrue("rendered PNG must exist on disk: ${file.absolutePath}", file.exists())
    assertTrue("rendered PNG must be non-empty", file.length() > 0)
    val bytes = file.readBytes()
    val img =
      ByteArrayInputStream(bytes).use { ImageIO.read(it) }
        ?: error("PNG must decode via javax.imageio: ${file.absolutePath}")
    return img
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }

  companion object {
    private const val FIXTURE_PREVIEW_ID = "click-toggle-square"
  }
}
