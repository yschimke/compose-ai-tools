package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit-level coverage for the v2 click-into-composition path: builds a [DesktopInteractiveSession]
 * directly from a [RenderEngine] (without going through [JsonRpcServer]) and asserts that a single
 * `dispatch(CLICK) → render` flips [ClickToGreenSquare]'s `remember`'d state from red to green.
 *
 * The companion [InteractiveDesktopRpcIntegrationTest] exercises the same flow through the full
 * JSON-RPC + DesktopHost stack; this one isolates the engine + session pieces so a failure here
 * tells us "the held scene + click dispatch are broken" rather than "something in the JSON-RPC
 * routing is wrong".
 */
class DesktopInteractiveSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test(timeout = 60_000)
  fun click_flips_remembered_state_from_red_to_green() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val spec =
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "ClickToGreenSquare",
        widthPx = 64,
        heightPx = 64,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "click-to-green-unit",
      )
    val held = engine.setUp(spec = spec, runInspectionMode = false)
    // Bootstrap render (mirrors DesktopHost.acquireInteractiveSession) so the scene has done one
    // layout pass before the pointer event arrives — without it Compose's hit-testing has no
    // measured tree to walk.
    held.scene.render()
    val session =
      DesktopInteractiveSession(previewId = "click-to-green", engine = engine, held = held)
    try {
      // Pre-click frame must be red.
      val baseline = engine.renderOnce(held, requestId = 1L)
      val baselineFile = File(baseline.pngPath!!)
      assertMostlyColor(baselineFile, "baseline (pre-click)", expectedRgb = 0xEF5350)

      session.dispatch(
        InteractiveInputParams(
          frameStreamId = "stream-1",
          kind = InteractiveInputKind.CLICK,
          pixelX = 32,
          pixelY = 32,
        )
      )
      val postClick = session.render(requestId = 2L)
      val postClickFile = File(postClick.pngPath!!)
      assertMostlyColor(postClickFile, "post-click", expectedRgb = 0x66BB6A)
    } finally {
      session.close()
    }
  }

  private fun assertMostlyColor(pngFile: File, label: String, expectedRgb: Int) {
    val bytes = pngFile.readBytes()
    val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    assertNotNull("$label PNG must decode via javax.imageio", img)
    // Tolerance 32: ImageComposeScene renders against a transparent backing surface, so the
    // tinted material colours come back several LSB darker than the literal hex (e.g. #66BB6A
    // observed as #5BA85F here) once Skia has composited and the AWT decoder has gone through
    // ARGB → RGB unpremultiply. Wide tolerance to keep the assertion robust against rendering
    // pipeline drift; the load-bearing claim is "this is the green fixture, not the red one",
    // which a single-channel-of-32 tolerance still differentiates trivially.
    val matchPct = pixelMatchPct(img!!, expectedRgb, perChannelTolerance = 32)
    assertTrue(
      "$label: expected ≥ 90% of pixels close to #${expectedRgb.toString(16).padStart(6, '0')}; " +
        "got ${"%.2f".format(matchPct * 100)}%",
      matchPct >= 0.90,
    )
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
}
