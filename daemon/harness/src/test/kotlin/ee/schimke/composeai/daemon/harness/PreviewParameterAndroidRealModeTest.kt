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
 * Regression scenario for issue #3027 — a `@PreviewParameter` preview must render through the real
 * Android daemon.
 *
 * Before the fix the daemon resolved every preview with the parameterless
 * `getDeclaredComposableMethod(functionName)` lookup, which matches only `foo(Composer, int)`. A
 * preview declaring `@PreviewParameter` compiles to `foo(<T>, Composer, int)`, so resolution threw
 * `NoSuchMethodException` *before composition started*: no PNG, none of the composition-derived
 * data products, just an `.error.json`. 27 previews in one real consumer module were lost that way.
 *
 * This lives in `:daemon:harness` rather than `:daemon:android`'s own unit tests because the
 * harness is the Android-daemon surface CI actually runs (`daemon-harness.yml`,
 * `-Pharness.host=real -Ptarget=android`); `:daemon:android:testDebugUnitTest` is not wired into
 * any workflow, so an assertion left there would never execute on a PR.
 *
 * The fixture provider yields **two** values (green then blue) on purpose: the daemon renders one
 * frame per preview id, so the contract is the *first* value, not "any value". A regression that
 * binds the wrong one fails the pixel assertion instead of passing on "something rendered".
 *
 * **Skipped under fake mode and under `-Ptarget=desktop`** — same gating as the other
 * `*AndroidRealModeTest` scenarios. Timeouts mirror [S1LifecycleAndroidRealModeTest]'s, which
 * absorb Robolectric's sandbox bootstrap.
 */
class PreviewParameterAndroidRealModeTest {

  @Test
  fun preview_parameter_preview_renders_first_provider_value() {
    Assume.assumeTrue(
      "Skipping PreviewParameterAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping PreviewParameterAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "tinted-square"
    val paths =
      realAndroidModeScenario(
        name = "preview-parameter-android",
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

      client.pollNotification("renderStarted", 180.seconds)
      val finished = client.pollNotification("renderFinished", 30.seconds)
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
        "PreviewParameterAndroidRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Fraction of pixels whose green channel is dominant — the shape of
   * [S1LifecycleAndroidRealModeTest.dominantRedFraction], keyed on green so it separates the
   * provider's first value (`0xFF43A047`) from its second (`0xFF1E88E5`, blue-dominant).
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
