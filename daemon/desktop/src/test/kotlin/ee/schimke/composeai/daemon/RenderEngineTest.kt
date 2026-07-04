package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B-desktop.1.4 verification — exercises the real Compose Desktop render body.
 *
 * Two tests:
 * * **redSquareRendersToValidPng** — submit one render through a [DesktopHost] backed by a real
 *   [RenderEngine]; assert the PNG file exists, decodes, and is mostly red. Mirrors the "is this
 *   mostly red?" assertion pattern from `samples/android/.../ScrollPreviewPixelTest.kt`.
 * * **tenSequentialRendersExposeWarmRuntime** — log per-render wall-clock for 10 sequential renders
 *   so we can see whether the warm-runtime amortisation is working (first render pays JIT warmup;
 *   subsequent renders should be faster). The test only fails if a render itself fails — the timing
 *   data is for the agent to report back, not a CI assertion.
 *
 * Pixel-diff helper is inlined here rather than imported from `:daemon:harness`'s `PixelDiff` to
 * avoid a circular dep (`:daemon:desktop` ← `:daemon:harness` would invert the dependency graph the
 * harness was built around). v2 reconciles by hoisting `PixelDiff` into a shared utilities module
 * if a third call-site needs it.
 */
class RenderEngineTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun redSquareRendersToValidPng() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
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
      val result = host.submit(request, timeoutMs = 60_000)

      // pngPath populated and the file actually exists.
      assertNotNull("pngPath must be populated by the real render body", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      // tookMs metric is populated.
      val metrics = result.metrics
      assertNotNull("metrics must be populated", metrics)
      assertTrue("metrics must contain tookMs", metrics!!.containsKey("tookMs"))
      assertTrue(
        "tookMs should be a sane wall-clock value (was ${metrics["tookMs"]})",
        metrics["tookMs"]!! in 0..60_000,
      )

      // PNG decodes and is mostly red. Use a wide channel tolerance because Compose's @Preview
      // surface composition + Skia's PNG encoder can introduce a few LSB of channel drift; the
      // assertion is "the rendered fill is the colour we asked for", not a pixel-perfect compare.
      val bytes = pngFile.readBytes()
      val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      assertEquals(64, img!!.width)
      assertEquals(64, img.height)
      val expectedRgb = 0xEF5350
      val matchPct = pixelMatchPct(img, expectedRgb, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun renderAlsoProducesSemanticsArtifacts() {
    // End-to-end: a real desktop render must drop the always-on `compose/semantics` JSON sidecar
    // AND the `compose/semantics-wireframe` artefacts (SVG + baked PNG) into the data dir, keyed by
    // the preview id, alongside the PNG. The plain JSON sidecar (compose-semantics.json) is what
    // `bundle pack --with-semantics` and design-parity read — the desktop backend previously wrote
    // only the wireframe and omitted it, so semantics never reached the bundle (issue #1885
    // follow-up).
    val outputDir = tempFolder.newFolder("renders-wireframe")
    val dataDir = tempFolder.newFolder("data-wireframe")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=RedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=wireframe-red"
        )
      host.submit(request, timeoutMs = 60_000)

      val previewDir = File(dataDir, "wireframe-red")
      val semantics = File(previewDir, "compose-semantics.json")
      assertTrue(
        "compose-semantics.json sidecar must be produced: ${semantics.absolutePath}",
        semantics.exists(),
      )
      assertTrue(
        "compose-semantics.json must carry a node tree",
        semantics.readText().contains("\"root\""),
      )
      val svg = File(previewDir, "compose-semantics-wireframe.svg")
      val png = File(previewDir, "compose-semantics-wireframe.png")
      assertTrue("wireframe SVG must be produced: ${svg.absolutePath}", svg.exists())
      assertTrue("wireframe SVG must be valid", svg.readText().trimStart().startsWith("<svg"))
      assertTrue("wireframe PNG must be produced: ${png.absolutePath}", png.exists())
      assertTrue("wireframe PNG must be non-empty", png.length() > 0)
      // The layered `compose/figma-svg` export rides the same captured trees as the wireframe.
      val figma = File(previewDir, "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      assertTrue("figma SVG must be valid", figmaSvg.trimStart().startsWith("<svg"))
      // The export is layered: at least the root composable is emitted as a named `<g id=…>` group.
      assertTrue("figma SVG must carry named layer groups", figmaSvg.contains("<g id="))
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun privateComposableRendersToValidPng() {
    // Regression: Kotlin `private fun` previews compile to JVM-private static methods. The daemon
    // resolves them via `getDeclaredComposableMethod` but the reflective `invoke` threw
    // `IllegalAccessException: … cannot access a member … with modifiers "private static final"`
    // until [RenderEngine] started calling `asMethod().isAccessible = true` after resolution. The
    // `samples/android/.../Previews.kt`'s `RedBoxPreview` ships such a preview on purpose.
    val outputDir = tempFolder.newFolder("renders-private")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=PrivateRedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=private-red-square"
        )
      val result = host.submit(request, timeoutMs = 60_000)

      assertNotNull("private @Composable must render a PNG, not blank", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      val img = ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      val matchPct = pixelMatchPct(img!!, 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun tenSequentialRendersExposeWarmRuntime() {
    val outputDir = tempFolder.newFolder("renders-warmup")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    val perRenderMs = mutableListOf<Long>()
    val totalStartNs = System.nanoTime()
    try {
      for (i in 1..10) {
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
        val result = host.submit(request, timeoutMs = 60_000)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000L
        perRenderMs.add(tookMs)
        assertNotNull("render $i pngPath must be populated", result.pngPath)
        assertTrue("render $i PNG must exist", File(result.pngPath!!).exists())
      }
      val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
      val firstMs = perRenderMs.first()
      val warmMedianMs = perRenderMs.drop(1).sorted().let { it[it.size / 2] }
      val ratio = if (warmMedianMs == 0L) Double.NaN else firstMs.toDouble() / warmMedianMs
      // Free-form report so the agent can copy it into the task summary. Tests don't assert on
      // these numbers — perf assertions are intentionally not gated in unit tests (D2.x / D-harness
      // own that). We just want them visible in `gradle test --info` output.
      println(
        "RenderEngineTest 10-render warm-up: total=${totalMs}ms first=${firstMs}ms " +
          "warm-median=${warmMedianMs}ms ratio=${"%.2f".format(ratio)} per-render=$perRenderMs"
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance] of
   * the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
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
