package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
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
 * D-harness.v2 Android counterpart of [S1LifecycleRealModeTest] — drives the same lifecycle
 * (`initialize → initialized → renderNow → renderStarted → renderFinished → shutdown → exit`), but
 * spawns the real `:daemon:android` `DaemonMain` via [RealAndroidHarnessLauncher] rather than
 * [RealDesktopHarnessLauncher]. The first time DESIGN § 4's renderer-agnostic claim is enforced
 * end-to-end at the harness level: same scenario shape, same composable, only the launcher and
 * baseline directory differ.
 *
 * **Skipped under fake mode and under `-Ptarget=desktop`.**
 *
 * **Heavy spawn cost.** Robolectric sandbox bootstrap (the dummy-`@Test` runner trick) costs ~3-10s
 * on a typical dev machine — much higher than the desktop launcher's ~600ms cold. Test uses 60s
 * `renderStarted` and 120s `renderFinished` timeouts to absorb that without stall-flapping. See
 * [RealAndroidHarnessLauncher]'s KDoc for the full cost story.
 *
 * **Per-target baselines.** Robolectric's bitmap output (HardwareRenderer/ImageReader) won't be
 * byte-identical to Skiko's — different renderers produce different AA / sub-pixel positioning. The
 * android baseline at `daemon/harness/baselines/android/s1/red-square.png` is captured separately
 * on first run; subsequent runs pixel-diff against it within the standard tolerance.
 */
class S1LifecycleAndroidRealModeTest {

  @Test
  fun s1_lifecycle_real_mode_android_happy_path() {
    Assume.assumeTrue(
      "Skipping S1LifecycleAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S1LifecycleAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "s1-android",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )

    val cold = System.nanoTime()
    val client = HarnessClient.start(paths.launcher)
    try {
      // 1. initialize. Cap at 60s — covers JVM cold start + Robolectric sandbox bootstrap. The
      //    daemon's initialize handler does not block on the sandbox (sandbox starts lazily on
      //    first render), so this is fast in practice.
      val initResult = client.initialize()
      assertEquals(1, initResult.protocolVersion)

      // 2. initialized.
      client.sendInitialized()

      // 3. renderNow.
      val renderNowResult = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), renderNowResult.queued)
      assertTrue(
        "renderNow.rejected must be empty: ${renderNowResult.rejected}",
        renderNowResult.rejected.isEmpty(),
      )

      // 4. renderStarted — generous 180s for first-render Robolectric sandbox bootstrap +
      //    HardwareRenderer init. JsonRpcServer emits renderStarted just before renderFinished
      //    (it's the *server*'s tier-handle, not a "render-began-inside-engine" signal), so this
      //    timeout is effectively the cold-render budget for the daemon as a whole. Robolectric
      //    on JDK 17 + SDK 35 + first Compose render can take 30-60s on a contended dev machine;
      //    bump to 180s so a slow CI runner doesn't flap.
      val started = client.pollNotification("renderStarted", 180.seconds)
      val startedParams = started["params"]?.jsonObject
      assertNotNull("renderStarted must carry params", startedParams)
      assertEquals(previewId, startedParams!!["id"]?.jsonPrimitive?.contentOrNull)

      // 5. renderFinished — should arrive immediately after renderStarted (same notification
      // batch).
      val finished = client.pollNotification("renderFinished", 30.seconds)
      val finishedParams = finished["params"]?.jsonObject
      assertNotNull("renderFinished must carry params", finishedParams)
      val reportedPath = finishedParams!!["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", reportedPath)
      val reportedPng = File(reportedPath!!)
      assertTrue(
        "renderFinished.pngPath must reference an existing file: $reportedPath",
        reportedPng.exists(),
      )
      assertTrue("rendered PNG must be non-empty: $reportedPath", reportedPng.length() > 0)
      val wallClockMs = (System.nanoTime() - cold) / 1_000_000
      System.err.println(
        "S1LifecycleAndroidRealModeTest: cold-spawn → renderFinished in ${wallClockMs}ms"
      )

      // 6. Sanity: the rendered PNG must be mostly red (RedSquare = 0xFFEF5350).
      val img = ImageIO.read(reportedPng)
      assertNotNull("rendered PNG must decode", img)
      assertTrue(
        "rendered PNG must be mostly red — got dominantRed=${dominantRedFraction(img!!)}",
        dominantRedFraction(img) > 0.9,
      )

      // 7. Auto-capture-on-first-run baseline diff (v1.5a) + regenerate-overwrite (v1.5b).
      diffOrCaptureBaseline(
        actualBytes = reportedPng.readBytes(),
        baseline = HarnessTestSupport.baselineFile("s1", "red-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S1LifecycleAndroidRealModeTest",
        stderrSupplier = { client.dumpStderr() },
      )

      // 8. shutdown + exit. Generous timeout — sandbox teardown can take a few seconds.
      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S1LifecycleAndroidRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Fraction of pixels in [img] whose red channel is dominant (>120 and clearly above green/blue).
   * The RedSquare composable fills with `0xFFEF5350`; any value > ~0.9 here means the render
   * succeeded. Mirrors [S1LifecycleRealModeTest.dominantRedFraction] exactly.
   */
  private fun dominantRedFraction(img: BufferedImage): Double {
    var matching = 0
    val total = img.width * img.height
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val argb = img.getRGB(x, y)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (r > 120 && (r - g) > 30 && (r - b) > 30 && abs(g - b) < 30) matching++
      }
    }
    return matching.toDouble() / total.toDouble()
  }
}
