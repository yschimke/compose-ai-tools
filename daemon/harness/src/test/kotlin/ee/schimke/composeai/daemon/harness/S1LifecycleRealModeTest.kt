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
 * Real-mode counterpart to [S1LifecycleTest] — drives the same `initialize → initialized →
 * renderNow → renderStarted → renderFinished → shutdown → exit` lifecycle, but spawns the actual
 * `:daemon:desktop` `DaemonMain` via [RealDesktopHarnessLauncher] rather than [FakeDaemonMain].
 *
 * **Skipped under fake mode.** `JUnit Assume.assumeTrue(harnessHost == "real")` short-circuits the
 * test under the default `-Pharness.host=fake`. Run with `./gradlew :daemon:harness:test
 * -Pharness.host=real --tests "*S1LifecycleRealModeTest"`.
 *
 * **Auto-capture-on-first-run baseline.** A baseline PNG lives at
 * `daemon/harness/baselines/desktop/s1/red-square.png`. On the first run the test captures the
 * rendered PNG to that path and asserts only the basic "PNG exists, mostly red" properties. On
 * subsequent runs it pixel-diffs against the baseline. A `regenerateBaselines` task is the v1.5b
 * story; this auto-capture is the v1.5a flow — re-capturing means deleting the baseline file by
 * hand.
 *
 * **Generous timeouts.** Real-mode subprocess startup pays JVM cold-start + Compose runtime
 * bootstrap + Skiko native-library extraction. The in-process desktop integration test took 1-3s on
 * the dev machine; subprocess will pay JVM startup on top. Test-step polls allow up to 60s for
 * `renderFinished` so the test fails loudly with stderr rather than wedging — see
 * [HarnessClient.pollNotification]'s "subprocess exited before notification arrived" path which
 * dumps stderr.
 */
class S1LifecycleRealModeTest {

  @Test
  fun s1_lifecycle_real_mode_happy_path() {
    Assume.assumeTrue(
      "Skipping S1LifecycleRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    // D-harness.v2 — this class is the desktop variant; the Android variant lives in
    // S1LifecycleAndroidRealModeTest. Skip when `-Ptarget=android` so both target classes can
    // co-exist in the same JUnit suite without fighting over the daemon spawn.
    Assume.assumeTrue(
      "Skipping S1LifecycleRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val moduleBuildDir = File("build")
    val rendersDir =
      File(moduleBuildDir, "daemon-harness/renders/s1-real").apply {
        deleteRecursively()
        mkdirs()
      }
    val reportsDir =
      File(moduleBuildDir, "reports/daemon-harness/s1-real").apply {
        deleteRecursively()
        mkdirs()
      }
    val manifestFile =
      File(moduleBuildDir, "daemon-harness/manifests/s1-real-previews.json").apply {
        parentFile.mkdirs()
      }
    // Drives `PreviewManifestRouter` on the spawned daemon: the previewId `red-square` resolves
    // to the `RedSquare` composable in `:daemon:desktop`'s testFixtures source set
    // (`RedFixturePreviewsKt`, top-level function). RenderEngine's reflection finds the
    // `@Composable static` method on that class. The testFixtures source set is on the harness's
    // test runtime classpath via `testImplementation(testFixtures(project(...)))` —
    // see `daemon/harness/build.gradle.kts`.
    manifestFile.writeText(
      """
      {
        "previews": [
          {
            "id": "red-square",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "RedSquare",
            "widthPx": 64,
            "heightPx": 64,
            "density": 1.0,
            "showBackground": true,
            "outputBaseName": "red-square"
          }
        ]
      }
      """
        .trimIndent()
    )

    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }

    val launcher =
      RealDesktopHarnessLauncher(
        rendersDir = rendersDir,
        previewsManifest = manifestFile,
        classpath = classpath,
      )

    val cold = System.nanoTime()
    val client = HarnessClient.start(launcher)
    try {
      // 1. initialize. Cap at 30s — covers JVM cold start + Compose/Skiko bootstrap.
      val initResult = client.initialize()
      assertEquals(1, initResult.protocolVersion)

      // 2. initialized.
      client.sendInitialized()

      // 3. renderNow.
      val renderNowResult =
        client.renderNow(previews = listOf("red-square"), tier = RenderTier.FAST)
      assertEquals(listOf("red-square"), renderNowResult.queued)
      assertTrue(
        "renderNow.rejected must be empty: ${renderNowResult.rejected}",
        renderNowResult.rejected.isEmpty(),
      )

      // 4. renderStarted.
      val started = client.pollNotification("renderStarted", 30.seconds)
      val startedParams = started["params"]?.jsonObject
      assertNotNull("renderStarted must carry params", startedParams)
      assertEquals("red-square", startedParams!!["id"]?.jsonPrimitive?.contentOrNull)

      // 5. renderFinished — generous timeout for first-render Skiko-native warmup.
      val finished = client.pollNotification("renderFinished", 60.seconds)
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
      System.err.println("S1LifecycleRealModeTest: cold-spawn → renderFinished in ${wallClockMs}ms")

      // 6. Sanity: the rendered PNG must be mostly red (RedSquare = 0xFFEF5350).
      val img = ImageIO.read(reportedPng)
      assertNotNull("rendered PNG must decode", img)
      assertTrue(
        "rendered PNG must be mostly red — got dominantRed=${dominantRedFraction(img!!)}",
        dominantRedFraction(img) > 0.9,
      )

      // 7. Auto-capture-on-first-run baseline diff (v1.5a) + regenerate-overwrite (v1.5b).
      //    Centralised in [diffOrCaptureBaseline]: respects `composeai.harness.regenerate=true` to
      //    always overwrite, falls back to capture-on-first-run otherwise.
      diffOrCaptureBaseline(
        actualBytes = reportedPng.readBytes(),
        baseline = HarnessTestSupport.baselineFile("s1", "red-square.png"),
        reportsDir = reportsDir,
        scenario = "S1LifecycleRealModeTest",
        stderrSupplier = { client.dumpStderr() },
      )

      // 8. shutdown + exit.
      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S1LifecycleRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
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
   * succeeded. Used as a cheap "is the image obviously the right thing?" sanity check before the
   * pixel-diff path.
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
