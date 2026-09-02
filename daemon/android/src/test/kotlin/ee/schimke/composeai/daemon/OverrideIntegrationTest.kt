package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.overrides.OverrideVariantInteraction
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end verification that `renderNow.overrides` actually changes rendered pixels (PROTOCOL.md
 * § 5, INTERACTIVE.md § 8a). Drives [PreviewManifestRouter] directly with override-bearing payloads
 * so we exercise the same path `JsonRpcServer.encodeRenderPayload` produces — the manifest router
 * rewrites `previewId=…;widthPx=…;uiMode=…` into a full `RenderSpec` payload, and the engine
 * consumes the override-merged spec.
 *
 * This isn't a wire-level test (no JSON-RPC plumbing) — that's already covered by the protocol
 * round-trip in [`MessagesTest`][ee.schimke.composeai.daemon.protocol.MessagesTest]. What we add
 * here is the missing rung: confirm the override fields actually reach `setQualifiers` /
 * `setFontScale` / the `RenderSpec` dimensions, by rendering the same fixture twice with different
 * overrides and asserting the bytes differ in the expected way. Without this, a refactor on either
 * side of the router could silently break overrides while the unit tests stay green.
 */
class OverrideIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun widthPxOverrideChangesRenderedDimensions() {
    val outputDir = tempFolder.newFolder("renders-width")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "red-square",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "red-square-default",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      // Default (manifest's 64×64).
      val small = renderAndDecode(host, "previewId=red-square", "small")
      assertEquals("manifest default width should be honoured", 64, small.width)
      assertEquals("manifest default height should be honoured", 64, small.height)

      // Override pushes width and height to 128.
      val large = renderAndDecode(host, "previewId=red-square;widthPx=128;heightPx=128", "large")
      assertEquals("widthPx override should reach the RenderSpec", 128, large.width)
      assertEquals("heightPx override should reach the RenderSpec", 128, large.height)
    } finally {
      host.shutdown()
    }
  }

  /**
   * Issue #4639 — a `_VARIANT_hovered` / `_VARIANT_focused` / `_VARIANT_pressed` preview must
   * render the state it names, not the resting one.
   *
   * The seed half of an `@OverrideVariant` reaches the composition as data (#3652 / #4638); an
   * interaction is something that has to be *done* to the composition, and the daemon's
   * one-frame-per-id path did nothing at all. Everything read off that render — the PNG, the
   * semantics and layout trees, the `compose/figma-svg` built from them — therefore described the
   * resting state under a filename claiming otherwise.
   *
   * The two halves arrive by different routes and this covers both: focus and press ride
   * `spec.overrides.focus` (via `OverrideVariantSpec.toPreviewOverrides`) into the connector's
   * `FocusOverrideExtension`, which does its work inside composition; hover is dispatched by the
   * host from [RenderEngine], because positional input has no in-composition half.
   *
   * Pixels rather than the exported vector, matching this file's idiom — and the stronger assertion
   * of the two, since every structured product is projected from the same composition these pixels
   * were captured from.
   */
  @Test
  fun interactionVariantsRenderTheStateTheyName() {
    val outputDir = tempFolder.newFolder("renders-interaction")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val previewsJson = tempFolder.newFile("previews-interaction.json")
    // `staticHoverFor` reads the hover intent off the preview index, so the engine needs one on
    // disk — the same sysprop the gradle plugin sets on a production daemon JVM.
    previewsJson.writeText(
      """
      {"previews":[
        {"id":"interaction-hovered","functionName":"InteractionStateSquare",
         "className":"ee.schimke.composeai.daemon.RedFixturePreviewsKt",
         "overrides":{"name":"hovered","seeds":[],"interaction":"Hovered"}},
        {"id":"interaction-dragged","functionName":"InteractionStateSquare",
         "className":"ee.schimke.composeai.daemon.RedFixturePreviewsKt",
         "overrides":{"name":"dragged","seeds":[],"interaction":"Dragged"},
         "captures":[{"drag":{"targetIndex":0}}]}
      ]}
      """
        .trimIndent()
    )
    val previousPreviewsJson = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previewsJson.absolutePath)
    fun entry(id: String, interaction: OverrideVariantInteraction? = null) =
      PreviewManifestEntry(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "InteractionStateSquare",
        widthPx = 48,
        heightPx = 48,
        density = 1.0f,
        outputBaseName = id,
        overrides =
          interaction?.let { OverrideVariantSpec(name = it.name.lowercase(), interaction = it) },
      )
    val host =
      PreviewManifestRouter(
        manifest =
          PreviewManifest(
            previews =
              listOf(
                entry("interaction-base"),
                entry("interaction-hovered", OverrideVariantInteraction.Hovered),
                entry("interaction-focused", OverrideVariantInteraction.Focused),
                entry("interaction-pressed", OverrideVariantInteraction.Pressed),
                entry("interaction-dragged"),
              )
          )
      )
    host.start()
    try {
      fun renderedIs(id: String, rgb: Int): Double =
        pixelMatchPct(renderAndDecode(host, "previewId=$id", id), rgb, 8)
      assertTrue("resting render must stay red", renderedIs("interaction-base", 0xEF5350) > 0.9)
      assertTrue("hovered render must be blue", renderedIs("interaction-hovered", 0x42A5F5) > 0.9)
      assertTrue(
        "focused render must be orange",
        renderedIs("interaction-focused", 0xFFA726) > 0.9,
      )
      assertTrue("pressed render must be green", renderedIs("interaction-pressed", 0x66BB6A) > 0.9)
      assertTrue(
        "dragged render must be purple",
        renderedIs("interaction-dragged", 0xAB47BC) > 0.9,
      )
    } finally {
      host.shutdown()
      if (previousPreviewsJson == null) {
        System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
      } else {
        System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousPreviewsJson)
      }
    }
  }

  /** The Android platform-ripple half of #4639's cross-backend acceptance criteria. */
  @Test
  fun materialButtonInteractionVariantsExportTheStateTheyName() {
    val outputDir = tempFolder.newFolder("renders-material-button-interactions")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val baseId = "MaterialButton_Light"
    val focusedId = "MaterialButton_Light_VARIANT_focused"
    val pressedId = "MaterialButton_Light_VARIANT_pressed"
    fun entry(id: String, interaction: OverrideVariantInteraction? = null) =
      PreviewManifestEntry(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "MaterialButtonInteractionState",
        widthPx = 96,
        heightPx = 48,
        density = 1.0f,
        outputBaseName = id,
        overrides =
          interaction?.let { OverrideVariantSpec(name = it.name.lowercase(), interaction = it) },
      )
    val host =
      PreviewManifestRouter(
        manifest =
          PreviewManifest(
            previews =
              listOf(
                entry(baseId),
                entry(focusedId, OverrideVariantInteraction.Focused),
                entry(pressedId, OverrideVariantInteraction.Pressed),
              )
          )
      )
    host.start()
    try {
      for (id in listOf(baseId, focusedId, pressedId)) {
        host.submit(RenderRequest.Render(payload = "previewId=$id"), timeoutMs = 60_000)
      }
      val dataDir = outputDir.parentFile!!.resolve("data")
      fun svg(id: String) = dataDir.resolve(id).resolve("compose-figma.svg").readText()
      fun png(id: String) = outputDir.resolve("$id.png").readBytes()

      assertNotEquals(
        "focused Material button PNG must not stay resting",
        png(baseId).contentHashCode(),
        png(focusedId).contentHashCode(),
      )
      assertNotEquals(
        "pressed Material button PNG must not stay resting",
        png(baseId).contentHashCode(),
        png(pressedId).contentHashCode(),
      )
      assertTrue("focused SVG keeps the editable base fill", svg(focusedId).contains("#6750A4"))
      assertTrue(
        "focused SVG emits Android's inherited state layer above content",
        svg(focusedId).contains("id=\"Material State Layer\""),
      )
      assertTrue(
        "pressed SVG emits Android's platform ripple above content",
        svg(pressedId).contains("id=\"Material Press Ripple\""),
      )
      assertNotEquals("focused SVG must not stay resting", svg(baseId), svg(focusedId))
      assertNotEquals("pressed SVG must not stay resting", svg(baseId), svg(pressedId))
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiModeOverrideFlipsDarkAwareComposable() {
    val outputDir = tempFolder.newFolder("renders-uimode")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "dark-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "DarkAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "dark-aware",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val light = renderAndDecode(host, "previewId=dark-aware;uiMode=light", "uimode-light")
      val dark = renderAndDecode(host, "previewId=dark-aware;uiMode=dark", "uimode-dark")

      // DarkAwareSquare paints white (#FFFFFF) in light mode, black (#000000) in dark mode.
      // `setQualifiers("+notnight")` / `+night` is what flips `isSystemInDarkTheme()`; if the
      // override didn't reach the qualifier builder both renders would land on the same colour.
      val lightWhitePct = pixelMatchPct(light, expectedRgb = 0xFFFFFF, perChannelTolerance = 8)
      val darkBlackPct = pixelMatchPct(dark, expectedRgb = 0x000000, perChannelTolerance = 8)
      assertTrue(
        "light render should be mostly white; got ${"%.2f".format(lightWhitePct * 100)}%",
        lightWhitePct >= 0.95,
      )
      assertTrue(
        "dark render should be mostly black; got ${"%.2f".format(darkBlackPct * 100)}%",
        darkBlackPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * **Regression guard for the pseudolocale drop on the daemon lane (#4371).**
   *
   * A pseudolocale override is two halves. The qualifier half — rewrite `ar-XB` to base locale `en`
   * and add `ldrtl` — happens in `RenderEngine.applyPreviewQualifiers` off `spec.localeTag`. The
   * other half, `PseudolocaleOverrideExtension`'s wrapped `Resources`, is planned from the
   * **extension bag**; but `localeTag` travels as a typed wire token and
   * `JsonRpcServer.encodeRenderPayload` nulls tokenised fields out of the bag, so the planner saw
   * `localeTag = null` and abstained. The render came back correctly mirrored and qualified, with
   * every string un-pseudolocalised — `?localeTag=ar-XB` on the preview server looked like plain
   * English (the shape reported against `wear-m3-catalog`). The Gradle path plans from
   * `params.locale`, which is why only the live daemon lane was wrong.
   *
   * [PseudolocaleStringSquare] paints blue exactly when `Resources.getText` came back transformed,
   * so it probes the half the qualifier path cannot provide. Before the fix both pseudolocale
   * renders were red.
   */
  @Test
  fun pseudolocaleTagPseudolocalisesStringsOnThePayloadPath() {
    val outputDir = tempFolder.newFolder("renders-pseudolocale")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "pseudolocale-string",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "PseudolocaleStringSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "pseudolocale-string",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val plain = renderAndDecode(host, "previewId=pseudolocale-string", "pseudo-plain")
      val plainRedPct = pixelMatchPct(plain, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "no override must resolve the plain string (red); got" +
          " ${"%.2f".format(plainRedPct * 100)}% red",
        plainRedPct >= 0.95,
      )

      for ((tag, label) in listOf("en-XA" to "accent", "ar-XB" to "bidi")) {
        val rendered =
          renderAndDecode(host, "previewId=pseudolocale-string;localeTag=$tag", "pseudo-$label")
        val bluePct = pixelMatchPct(rendered, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
        assertTrue(
          "localeTag=$tag must plan PseudolocaleOverrideExtension so Resources.getText comes back" +
            " pseudolocalised; got ${"%.2f".format(bluePct * 100)}% blue — the pseudolocale bag" +
            " drop is back (#4371)",
          bluePct >= 0.95,
        )
      }

      // …and the wrap must not outlive the render that asked for it.
      val after = renderAndDecode(host, "previewId=pseudolocale-string", "pseudo-plain-after")
      val afterRedPct = pixelMatchPct(after, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "an un-overridden render after a pseudolocale one must resolve the plain string again;" +
          " got ${"%.2f".format(afterRedPct * 100)}% red",
        afterRedPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun deviceOverrideResolvesCatalogDimensions() {
    // PROTOCOL.md § 5 (`renderNow.overrides.device`) — `device=id:pixel_5` should resolve via
    // `DeviceDimensions.resolve` to widthDp=393, heightDp=851, density=2.75, giving widthPx=1080
    // (393 × 2.75) and heightPx=2340 (851 × 2.75). The manifest's per-preview defaults are
    // small (64×64) so the PNG dimension change is the visible signal.
    val outputDir = tempFolder.newFolder("renders-device")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "red-square",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "red-square-device",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val pixel5 = renderAndDecode(host, "previewId=red-square;device=id:pixel_5", "pixel_5")
      // 393dp × 2.75 density = 1080px nominal, 851dp × 2.75 = 2340px nominal. Robolectric's
      // qualifier round-trip is lossy by a couple of px (px → dp via integer division → px again),
      // so we assert "near nominal" rather than equality. The point is that the device override
      // routed through the catalog and produced ~Pixel 5 dimensions, not the manifest's 64×64.
      assertNearPx("device=id:pixel_5 width should be ~1080px", expected = 1080, pixel5.width)
      assertNearPx("device=id:pixel_5 height should be ~2340px", expected = 2340, pixel5.height)

      // Explicit widthPx still wins over the device-derived value — `device=id:pixel_5;widthPx=600`
      // takes the Pixel 5's density (2.75) but forces a custom width.
      val custom =
        renderAndDecode(host, "previewId=red-square;device=id:pixel_5;widthPx=600", "custom")
      assertNearPx("explicit widthPx should override device dims", expected = 600, custom.width)
      assertNearPx(
        "heightPx still flows from the device when not overridden",
        expected = 2340,
        custom.height,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun captureAdvanceMsOverrideThreadsThroughTheRenderPath() {
    // PROTOCOL.md § 5 (`renderNow.overrides.captureAdvanceMs`) — the override should reach the
    // Android RenderEngine's `mainClock.advanceTimeBy(...)` call site without breaking the
    // render. Smoke test only — verifying the actual paused-clock effect needs a fixture with a
    // `LaunchedEffect`-driven state flip and per-frame mainClock alignment, which is fragile in
    // a single-pass integration test. The wire round-trip + parser are covered by MessagesTest.
    val outputDir = tempFolder.newFolder("renders-advance")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "red-square",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "red-advance",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val img = renderAndDecode(host, "previewId=red-square;captureAdvanceMs=200", "advance-200")
      assertEquals(32, img.width)
      assertEquals(32, img.height)
    } finally {
      host.shutdown()
    }
  }

  /**
   * Asserts [actual] is within ±4px of [expected]. The Android backend's qualifier path round-trips
   * px → dp (via integer division) → px again inside `applyPreviewQualifiers`, so a request for
   * 1080px can come back as 1078px etc. The exact-px assertion isn't what this test is proving —
   * we're proving the device override reached the spec at all.
   */
  private fun assertNearPx(message: String, expected: Int, actual: Int) {
    assertTrue(
      "$message — expected ~$expected, got $actual (drift > 4px)",
      kotlin.math.abs(expected - actual) <= 4,
    )
  }

  private fun renderAndDecode(
    host: PreviewManifestRouter,
    payload: String,
    label: String,
  ): java.awt.image.BufferedImage {
    val request = RenderRequest.Render(payload = payload)
    val result = host.submit(request, timeoutMs = 120_000)
    assertNotNull("$label: pngPath must be populated", result.pngPath)
    val pngFile = File(result.pngPath!!)
    assertTrue("$label: rendered PNG must exist", pngFile.exists())
    return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      ?: error("$label: PNG failed to decode")
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance] of
   * the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
   * `PixelDiff` to avoid the same circular dep that [RenderEngineTest]'s helper sidesteps.
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
