package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B1.4 verification — exercises the real Robolectric/Compose render body inside a
 * [RobolectricHost] sandbox.
 *
 * Two tests:
 * * **redSquareRendersToValidPng** — submit one render through a real [RobolectricHost]; assert
 *   the PNG file exists, decodes, and is mostly red. Mirrors the "is this mostly red?" assertion
 *   pattern from `samples/android/.../ScrollPreviewPixelTest.kt` and from
 *   `:daemon:desktop/RenderEngineTest`.
 * * **fiveSequentialRendersExposeWarmRuntime** — log per-render wall-clock for 5 sequential
 *   renders so the warm-runtime amortisation is visible (first render pays Robolectric sandbox
 *   bootstrap; subsequent renders should drop sharply). The test only fails on render failure;
 *   the timing data is for the agent to report back, not a CI assertion. Robolectric init
 *   dominates so we use 5 renders rather than 10 to keep CI runtime under the daemon-module
 *   budget.
 *
 * Pixel-diff helper is inlined here rather than imported from `:daemon:harness`'s
 * `PixelDiff` for the same reason as the desktop counterpart — `:daemon:android` ←
 * `:daemon:harness` would invert the dependency graph.
 */
class RenderEngineTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun redSquareRendersToValidPng() {
    val outputDir = tempFolder.newFolder("renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    // Roborazzi reads `roborazzi.test.record` at the static init that hooks `captureRoboImage`'s
    // write path. Setting it on the test thread before sandbox bootstrap ensures the sandbox-side
    // Roborazzi class-init sees record mode. Mirror the gradle-plugin's launch descriptor (see
    // `AndroidPreviewClasspath.RobolectricSystemProps`).
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=RedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=red-square"
        )
      // Robolectric sandbox bootstrap dominates the first render; allow generous timeout.
      val result = host.submit(request, timeoutMs = 120_000)

      assertNotNull("pngPath must be populated by the real render body", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      val metrics = result.metrics
      assertNotNull("metrics must be populated", metrics)
      assertTrue("metrics must contain tookMs", metrics!!.containsKey("tookMs"))
      assertTrue(
        "tookMs should be a sane wall-clock value (was ${metrics["tookMs"]})",
        metrics["tookMs"]!! in 0..120_000,
      )

      val bytes = pngFile.readBytes()
      val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      assertEquals(64, img!!.width)
      assertEquals(64, img.height)
      val expectedRgb = 0xEF5350
      val matchPct = pixelMatchPct(img, expectedRgb, perChannelTolerance = 8)
      assertTrue(
        "expected >= 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )

      val semanticsFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("red-square")
          .resolve(ComposeSemanticsDataProducer.FILE)
      assertTrue(
        "compose/semantics data product should be written next to render data: $semanticsFile",
        semanticsFile.exists(),
      )
      val semanticsJson = Json.parseToJsonElement(semanticsFile.readText()).jsonObject
      assertEquals(
        "0,0,64,64",
        semanticsJson["root"]!!.jsonObject["boundsInRoot"]!!.jsonPrimitive.content,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun fiveSequentialRendersExposeWarmRuntime() {
    val outputDir = tempFolder.newFolder("renders-warmup")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val host = RobolectricHost()
    host.start()
    val perRenderMs = mutableListOf<Long>()
    val totalStartNs = System.nanoTime()
    try {
      for (i in 1..5) {
        val request =
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=${if (i % 2 == 0) "BlueSquare" else "RedSquare"};" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=warmup-$i"
          )
        val startNs = System.nanoTime()
        val result = host.submit(request, timeoutMs = 120_000)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000L
        perRenderMs.add(tookMs)
        assertNotNull("render $i pngPath must be populated", result.pngPath)
        assertTrue("render $i PNG must exist", File(result.pngPath!!).exists())
      }
      val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
      val firstMs = perRenderMs.first()
      val warmMedianMs = perRenderMs.drop(1).sorted().let { it[it.size / 2] }
      // Free-form report so the agent can copy it into the task summary. CI does not gate on
      // these — perf assertions live in D2.x / D-harness. We just want them in --info output.
      println(
        "RenderEngineTest 5-render warm-up: total=${totalMs}ms first=${firstMs}ms " +
          "warm-median=${warmMedianMs}ms per-render=$perRenderMs"
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance]
   * of the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
   * `PixelDiff` to avoid the circular dep noted in the file KDoc.
   */
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
