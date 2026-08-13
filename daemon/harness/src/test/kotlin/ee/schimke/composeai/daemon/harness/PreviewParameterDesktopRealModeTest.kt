package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Regression scenario for the desktop follow-up to issue #3027 — a `@PreviewParameter` preview must
 * render through the real **desktop** daemon.
 *
 * Before the fix the desktop daemon resolved every preview with the parameterless
 * `getDeclaredComposableMethod(functionName)` lookup, which matches only `foo(Composer, int)`. A
 * preview declaring `@PreviewParameter` compiles to `foo(<T>, Composer, int)`, so resolution threw
 * `NoSuchMethodException` *before composition started*: no PNG, none of the composition-derived
 * data products, just an `.error.json`. On a CMP/desktop consumer module that dropped every
 * fan-out's a11y tree, which took down `bundle pack --with-semantics`. The Android daemon already
 * resolved these (issue #3027); this is the desktop counterpart.
 *
 * The fixture provider yields **two** values (green then blue) on purpose: the daemon renders one
 * frame per preview id, so the contract is the *first* value, not "any value". A regression that
 * binds the wrong one fails the pixel assertion instead of passing on "something rendered". Mirrors
 * [PreviewParameterAndroidRealModeTest] one-to-one, swapping `realAndroidModeScenario` →
 * [realModeScenario] and the Android target gate → desktop.
 */
class PreviewParameterDesktopRealModeTest {

  @Test
  fun preview_parameter_preview_renders_first_provider_value() {
    Assume.assumeTrue(
      "Skipping PreviewParameterDesktopRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping PreviewParameterDesktopRealModeTest — desktop variant; set -Ptarget=desktop " +
        "(default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val previewId = "tinted-square"
    val paths =
      realModeScenario(
        name = "preview-parameter-desktop",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ThemedTintedSquare",
              previewParameterProvider = "ee.schimke.composeai.daemon.SquareTintProvider",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(2, client.initialize().protocolVersion)
      client.sendInitialized()

      val renderNowResult = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), renderNowResult.queued)
      assertTrue(
        "renderNow.rejected must be empty: ${renderNowResult.rejected}",
        renderNowResult.rejected.isEmpty(),
      )

      // Desktop cold start (Compose + Skiko bootstrap) is faster than Robolectric's, but keep a
      // generous window so a cold CI runner doesn't flake.
      val finished = client.pollRenderFinishedFor(previewId, timeout = 120.seconds)
      val finishedParams = finished["params"]?.jsonObject
      assertNotNull("renderFinished must carry params", finishedParams)
      // Pre-fix this is where the scenario died: resolution threw, so no pngPath was ever reported.
      val reportedPath = finishedParams!!["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull(
        "renderFinished.pngPath must be present — a @PreviewParameter preview must render",
        reportedPath,
      )
      val reportedPng = File(reportedPath!!)
      assertTrue("rendered PNG must exist: $reportedPath", reportedPng.exists())
      assertTrue("rendered PNG must be non-empty: $reportedPath", reportedPng.length() > 0)

      val img = ImageIO.read(reportedPng)
      assertNotNull("rendered PNG must decode", img)
      // SquareTintProvider's FIRST value is 0xFF43A047 (green); its second is 0xFF1E88E5 (blue).
      val green = dominantGreenFraction(img!!)
      assertTrue(
        "render must carry the provider's first value (green #43A047), not its second (blue) — " +
          "dominantGreen=$green",
        green > 0.9,
      )

      assertEquals(
        "Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}",
        0,
        client.shutdownAndExit(timeout = 60.seconds),
      )
    } catch (t: Throwable) {
      System.err.println(
        "PreviewParameterDesktopRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Issue #3749 — the row-addressed previewId, end to end. The harness manifest carries only the
   * base id `tinted-square` (exactly like the `previews.json` discovery writes, which cannot
   * enumerate a provider), so `tinted-square_PARAM_1` used to die in `PreviewManifestRouter` with
   * *no manifest entry for previewId* — the error from the issue report. It must now route to the
   * base entry and bind the provider's SECOND value.
   */
  @Test
  fun `row addressed preview id renders that row`() {
    Assume.assumeTrue(
      "Skipping PreviewParameterDesktopRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping PreviewParameterDesktopRealModeTest — desktop variant; set -Ptarget=desktop " +
        "(default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val rowId = "tinted-square_PARAM_1"
    val paths =
      realModeScenario(
        name = "preview-parameter-row-desktop",
        previews =
          listOf(
            RealModePreview(
              id = "tinted-square",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ThemedTintedSquare",
              previewParameterProvider = "ee.schimke.composeai.daemon.SquareTintProvider",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(2, client.initialize().protocolVersion)
      client.sendInitialized()

      val renderNowResult = client.renderNow(previews = listOf(rowId), tier = RenderTier.FAST)
      assertEquals(listOf(rowId), renderNowResult.queued)
      assertTrue(
        "renderNow.rejected must be empty: ${renderNowResult.rejected}",
        renderNowResult.rejected.isEmpty(),
      )

      val finished = client.pollRenderFinishedFor(rowId, timeout = 120.seconds)
      val reportedPath =
        finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull(
        "renderFinished.pngPath must be present — a row-addressed previewId must render",
        reportedPath,
      )
      val img = ImageIO.read(File(reportedPath!!))
      assertNotNull("rendered PNG must decode", img)
      // Row 1 is SquareTintProvider's blue (#1E88E5); row 0 is green. A regression that drops the
      // row token renders green and fails here rather than passing on "something rendered".
      val green = dominantGreenFraction(img!!)
      assertTrue(
        "row 1 must render the provider's SECOND value (blue #1E88E5), not its first (green) — " +
          "dominantGreen=$green",
        green < 0.1,
      )

      assertEquals(
        "Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}",
        0,
        client.shutdownAndExit(timeout = 60.seconds),
      )
    } catch (t: Throwable) {
      System.err.println(
        "PreviewParameterDesktopRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Fraction of pixels whose green channel is dominant — keyed on green so it separates the
   * provider's first value (`0xFF43A047`) from its second (`0xFF1E88E5`, blue-dominant). Mirrors
   * [PreviewParameterAndroidRealModeTest.dominantGreenFraction].
   */
  private fun dominantGreenFraction(img: BufferedImage): Double {
    var matching = 0
    val total = img.width * img.height
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val argb = img.getRGB(x, y)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (g > 100 && (g - r) > 30 && (g - b) > 30) matching++
      }
    }
    return matching.toDouble() / total.toDouble()
  }
}
