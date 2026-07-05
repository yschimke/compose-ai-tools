package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

      val layoutFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("red-square")
          .resolve(LayoutInspectorDataProducer.FILE)
      assertTrue(
        "layout/inspector data product should be written next to render data: $layoutFile",
        layoutFile.exists(),
      )
      val layoutJson = Json.parseToJsonElement(layoutFile.readText()).jsonObject
      val root = layoutJson["root"]!!.jsonObject
      assertEquals(64, (root["size"]!!.jsonObject["width"] as JsonPrimitive).content.toInt())
      assertEquals(0, (root["bounds"]!!.jsonObject["left"] as JsonPrimitive).content.toInt())
      assertTrue("layout/inspector root should name a component", root["component"] != null)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportEmbedsGoogleFontsWhenEnabled() {
    // Parity with the desktop export: with `-Dcomposeai.figma.embedFonts=true` the Android export
    // embeds each text node's face as an `@font-face` WOFF2 so the SVG renders the real typeface. We
    // pre-seed the shared font cache (`composeai.fonts.cacheDir`) so the resolver serves from disk —
    // deterministic, no network in CI. The `SerifTextPreview` fixture is `FontFamily.Serif`, so the
    // export must embed a concrete *serif* (Noto Serif) — not the Roboto sans default, which used to
    // erase serif/monospace specimens' identity.
    val outputDir = tempFolder.newFolder("renders-figma-fonts")
    val fontCache = tempFolder.newFolder("font-cache")
    // The generic `serif` family maps to Noto Serif; text weight defaults to 400. Seed all plausible
    // weights with the same sentinel bytes so the assertion is weight-agnostic.
    val sentinel = byteArrayOf(1, 2, 3)
    for (w in listOf(400, 500, 700)) File(fontCache, "noto-serif-$w.woff2").writeBytes(sentinel)
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty("composeai.figma.embedFonts", "true")
    System.setProperty("composeai.fonts.cacheDir", fontCache.absolutePath)
    val host = RobolectricHost()
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=SerifTextPreview;" +
              "widthPx=200;heightPx=80;density=1.0;showBackground=true;outputBaseName=figma-fonts"
        ),
        timeoutMs = 120_000,
      )

      val svg =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("figma-fonts")
          .resolve("compose-figma.svg")
      assertTrue("figma SVG must be produced: ${svg.absolutePath}", svg.exists())
      val text = svg.readText()
      assertTrue("export must embed an @font-face", text.contains("@font-face"))
      // base64 of {1,2,3} = "AQID" — the seeded face bytes, proving the resolver→embed wiring.
      assertTrue("must embed the resolved WOFF2 data URI", text.contains("data:font/woff2;base64,AQID"))
      assertTrue("serif specimen must embed a concrete serif", text.contains("font-family:'Noto Serif'"))
      assertTrue("text must name the serif face", text.contains("""font-family="Noto Serif""""))
    } finally {
      host.shutdown()
      System.clearProperty("composeai.figma.embedFonts")
      System.clearProperty("composeai.fonts.cacheDir")
    }
  }

  @Test
  fun figmaSvgExportRastersOpaqueImage() {
    // Parity with the desktop backend: a real Robolectric render of a screen containing an opaque
    // `Image` must emit the Image as an `<image>` layer in `compose-figma.svg` AND crop the
    // referenced background-free raster out of the captured frame into `figma-raster/`, so the SVG
    // never dangles a reference. This exercises the Android hybrid wiring: the render engine threads
    // the frame PNG through `RenderDataArtifactContextKeys.OutputPng` and `ComposeFigmaSvgExtension`
    // hands it to the shared producer.
    val outputDir = tempFolder.newFolder("renders-figma-raster")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=OpaqueImageSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=figma-raster"
        )
      host.submit(request, timeoutMs = 120_000)

      val previewDir = outputDir.parentFile!!.resolve("data").resolve("figma-raster")
      val figma = previewDir.resolve("compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      assertTrue("figma SVG must emit the opaque Image as an <image> layer", figmaSvg.contains("<image "))
      assertTrue("figma SVG must reference a figma-raster PNG", figmaSvg.contains("href=\"figma-raster/"))

      val rasterDir = previewDir.resolve("figma-raster")
      val pngs = rasterDir.listFiles { f -> f.extension == "png" }?.toList().orEmpty()
      assertTrue("hybrid export must write the referenced raster PNG(s)", pngs.isNotEmpty())
      val cropped = ByteArrayInputStream(pngs.first().readBytes()).use { ImageIO.read(it) }
      assertNotNull("raster PNG must decode", cropped)
      val center = cropped!!.getRGB(cropped.width / 2, cropped.height / 2)
      val r = (center shr 16) and 0xFF
      val g = (center shr 8) and 0xFF
      val b = center and 0xFF
      assertTrue("raster crop must land on the Image (green-dominant), got rgb=$r,$g,$b", g > r && g > b)
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
    // `samples/android/.../Previews.kt`'s `RedBoxPreview` ships such a preview on purpose, so a
    // regression here blanks one render and fails the whole baseline pipeline (MISSING_RENDERS=fail).
    val outputDir = tempFolder.newFolder("renders-private")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
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
      val result = host.submit(request, timeoutMs = 120_000)

      assertNotNull("private @Composable must render a PNG, not blank", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      val img = ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      val matchPct = pixelMatchPct(img!!, 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "expected >= 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun serifTextWritesFontsUsedDataProduct() {
    val outputDir = tempFolder.newFolder("renders-fonts")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "previewId=serif-text;" +
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=SerifTextPreview;" +
                "widthPx=160;heightPx=48;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=serif-text"
          ),
          timeoutMs = 120_000,
        )
      assertNotNull(result.pngPath)

      val fontsFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("serif-text")
          .resolve(FontsUsedDataProducer.FILE)
      assertTrue("fonts/used data product should be written: $fontsFile", fontsFile.exists())
      val payload = Json.parseToJsonElement(fontsFile.readText()).jsonObject
      val fonts = payload["fonts"]!!.jsonArray
      assertTrue("expected at least one resolved font entry", fonts.isNotEmpty())
      val serif =
        fonts
          .map { it.jsonObject }
          .firstOrNull { it["requestedFamily"]?.jsonPrimitive?.content == "serif" }
      assertNotNull("expected FontFamily.Serif request in $fonts", serif)
      assertEquals("normal", serif!!["style"]!!.jsonPrimitive.content)
      assertEquals("400", serif["weight"]!!.jsonPrimitive.content)
      assertTrue(serif["resolvedFamily"]!!.jsonPrimitive.content.isNotBlank())
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun themeModeRenderCapturesMaterialThemeDataProduct() {
    val outputDir = tempFolder.newFolder("renders-theme")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    val registry = ThemeDataProductRegistry()
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "previewId=android-theme;" +
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=ThemedPrimarySquare;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "mode=theme;" +
                "outputBaseName=android-theme"
          ),
          timeoutMs = 120_000,
        )
      registry.onRender("android-theme", result)

      val fetch =
        registry.fetch("android-theme", "compose/theme", params = null, inline = true)
          as DataProductRegistry.Outcome.Ok
      val colorScheme =
        fetch.result.payload!!.jsonObject["resolvedTokens"]!!.jsonObject["colorScheme"]!!.jsonObject
      assertTrue(colorScheme["primary"]!!.jsonPrimitive.content.matches(Regex("#[0-9A-F]{8}")))
      assertEquals("theme", result.previewContext!!.renderMode)
      assertTrue(result.previewContext!!.inspection.parameterInformationCollected)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun subscribedPreviewCapturesThemeOnDefaultRender() {
    val outputDir = tempFolder.newFolder("renders-theme-subscribe")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    val registry = ThemeDataProductRegistry()
    registry.onSubscribe("android-theme-subscribed", "compose/theme", JsonPrimitive("ignored"))
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "previewId=android-theme-subscribed;" +
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=ThemedPrimarySquare;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=android-theme-subscribed"
          ),
          timeoutMs = 120_000,
        )
      registry.onRender("android-theme-subscribed", result)

      val outcome =
        registry.fetch("android-theme-subscribed", "compose/theme", params = null, inline = true)
      assertTrue(outcome is DataProductRegistry.Outcome.Ok)
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

  @Test
  fun resourceReadsWriteResourcesUsedDataProduct() {
    val outputDir = tempFolder.newFolder("renders-resources")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("composeai.daemon.moduleProjectDir", File("daemon/android").absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=ResourceReadingPreview;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=resource-reading"
        )
      host.submit(request, timeoutMs = 120_000)

      val resourcesFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("resource-reading")
          .resolve(ResourcesUsedDataProducer.FILE)
      assertTrue(
        "resources/used data product should be written next to render data: $resourcesFile",
        resourcesFile.exists(),
      )
      val references =
        Json.parseToJsonElement(resourcesFile.readText())
          .jsonObject["references"]!!
          .jsonArray
          .map { it.jsonObject }
      val byName = references.associateBy { it["resourceName"]!!.jsonPrimitive.content }
      assertEquals("string", byName["compose_ai_resource_used_label"]!!["resourceType"]!!.jsonPrimitive.content)
      assertEquals(
        "Resource label",
        byName["compose_ai_resource_used_label"]!!["resolvedValue"]!!.jsonPrimitive.content,
      )
      assertTrue(
        byName["compose_ai_resource_used_label"]!!["resolvedFile"]!!
          .jsonPrimitive
          .content
          .endsWith("src/main/res/values/resources_used.xml")
      )
      assertEquals("color", byName["compose_ai_resource_used_color"]!!["resourceType"]!!.jsonPrimitive.content)
      assertEquals("dimen", byName["compose_ai_resource_used_size"]!!["resourceType"]!!.jsonPrimitive.content)
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
