package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.overrides.OverrideSeed
import ee.schimke.composeai.data.overrides.OverrideSeedKind
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop counterpart of `:daemon:android`'s `OverrideIntegrationTest`. Drives
 * [PreviewManifestRouter] directly with override-bearing payloads to prove the desktop renderer
 * actually applies `widthPx`, `uiMode`, `fontScale`, and `orientation` (PROTOCOL.md § 5,
 * INTERACTIVE.md § 8a).
 *
 * `localeTag` is applied only when the Compose UI runtime exposes a providable locale list;
 * `orientation = landscape` reduces to a `widthPx ↔ heightPx` swap before `ImageComposeScene`
 * construction since issue #1208 — see [orientationOverrideSwapsDimensions].
 */
class OverrideIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun discoveredOverrideVariantKeepsItsOwnFigmaSvgAndState() {
    val outputDir = tempFolder.newFolder("renders-discovered-override-variant")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseId = "FilledButton_Light"
    val variantId = "FilledButton_Light_VARIANT_disabled"
    fun info(id: String, overrides: OverrideVariantSpec? = null) =
      PreviewInfoDto(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        methodName = "OverridableSquare",
        params = PreviewParamsDto(widthDp = 32, heightDp = 32, density = 1.0f),
        overrides = overrides,
      )
    val byId =
      listOf(
          info(baseId),
          info(
            variantId,
            OverrideVariantSpec(
              name = "disabled",
              seeds =
                listOf(
                  OverrideSeed(key = "fill", kind = OverrideSeedKind.COLOR, raw = "#FF42A5F5")
                ),
            ),
          ),
        )
        .associate { it.id to renderSpecFromInfo(it) }
    val host =
      DesktopHost(
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension()))
          ),
        previewSpecResolver = byId::get,
      )
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=$baseId"), timeoutMs = 30_000)
      host.submit(RenderRequest.Render(payload = "previewId=$variantId"), timeoutMs = 30_000)

      val dataDir = outputDir.parentFile!!.resolve("data")
      val baseSvg = dataDir.resolve(baseId).resolve("compose-figma.svg").readText()
      val variantSvg = dataDir.resolve(variantId).resolve("compose-figma.svg").readText()
      assertTrue("base SVG must retain the author-default red fill", baseSvg.contains("#EF5350"))
      assertTrue("variant SVG must carry its baked blue fill", variantSvg.contains("#42A5F5"))
      assertNotEquals("base and variant SVGs must not collide", baseSvg, variantSvg)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun widthPxOverrideChangesRenderedDimensions() {
    val outputDir = tempFolder.newFolder("renders-width")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
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
      val small = renderAndDecode(host, "previewId=red-square", "small")
      assertEquals("manifest default width should be honoured", 64, small.width)

      val large = renderAndDecode(host, "previewId=red-square;widthPx=128;heightPx=128", "large")
      assertEquals("widthPx override should reach the RenderSpec", 128, large.width)
      assertEquals("heightPx override should reach the RenderSpec", 128, large.height)
    } finally {
      host.shutdown()
    }
  }

  /**
   * Issue #1208 — `orientation = landscape` reduces to a `widthPx ↔ heightPx` swap on the desktop
   * backend because `ImageComposeScene` has no display-rotation concept. The test exercises the
   * swap on a manifest whose base shape is taller-than-wide; landscape should flip the rendered PNG
   * to wider-than-tall, and explicit `widthPx`/`heightPx` overrides on the same call should win
   * over the hint (orientation is a hint, not a forced rotation).
   */
  @Test
  fun orientationOverrideSwapsDimensions() {
    val outputDir = tempFolder.newFolder("renders-orientation")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "portrait-rect",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 60,
              heightPx = 120,
              density = 1.0f,
              outputBaseName = "portrait-rect",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val portrait = renderAndDecode(host, "previewId=portrait-rect", "portrait-default")
      assertEquals("manifest default width should be honoured", 60, portrait.width)
      assertEquals("manifest default height should be honoured", 120, portrait.height)
      assertTrue(
        "default orientation should be taller-than-wide; got ${portrait.width}x${portrait.height}",
        portrait.height > portrait.width,
      )

      val landscape =
        renderAndDecode(host, "previewId=portrait-rect;orientation=landscape", "landscape")
      assertEquals("orientation=landscape should swap widthPx", 120, landscape.width)
      assertEquals("orientation=landscape should swap heightPx", 60, landscape.height)
      assertTrue(
        "orientation=landscape should produce wider-than-tall; got ${landscape.width}x${landscape.height}",
        landscape.width > landscape.height,
      )

      // Re-asserting portrait keeps the spec at base dims — a no-op swap.
      val explicitPortrait =
        renderAndDecode(host, "previewId=portrait-rect;orientation=portrait", "portrait-explicit")
      assertEquals("orientation=portrait should leave widthPx alone", 60, explicitPortrait.width)
      assertEquals("orientation=portrait should leave heightPx alone", 120, explicitPortrait.height)

      // Precedence: explicit widthPx/heightPx win over the orientation hint. The caller asked
      // for 200x80 AND landscape; the explicit dims are taken as-is.
      val explicitLandscape =
        renderAndDecode(
          host,
          "previewId=portrait-rect;widthPx=200;heightPx=80;orientation=landscape",
          "explicit-landscape",
        )
      assertEquals(
        "explicit widthPx should win over orientation hint",
        200,
        explicitLandscape.width,
      )
      assertEquals(
        "explicit heightPx should win over orientation hint",
        80,
        explicitLandscape.height,
      )

      // Precedence in the opposite direction: explicit taller-than-wide pixels with
      // orientation=landscape — the explicit dims still win, no swap fires.
      val explicitTaller =
        renderAndDecode(
          host,
          "previewId=portrait-rect;widthPx=40;heightPx=100;orientation=landscape",
          "explicit-taller",
        )
      assertEquals(
        "explicit widthPx should win over orientation hint even when taller-than-wide",
        40,
        explicitTaller.width,
      )
      assertEquals(
        "explicit heightPx should win over orientation hint even when taller-than-wide",
        100,
        explicitTaller.height,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Issue #3547 — the orientation hint must apply to a **device-derived** frame too. It used to be
   * suppressed whenever `device=` was present (the swap was gated on `deviceOverride == null`),
   * which killed the commonest spelling of the request: pick a tablet in the viewer, then pick
   * Portrait. `?device=id:pixel_tablet&orientation=portrait` rendered the tablet's natural
   * 2560×1600 landscape frame and the Portrait control looked broken.
   *
   * A device supplies the frame's *natural* geometry, so rotating it is the point of asking; only
   * explicit `widthPx`/`heightPx` — the caller naming exact pixels — outrank the hint.
   */
  @Test
  fun orientationOverrideRotatesADeviceDerivedFrame() {
    val outputDir = tempFolder.newFolder("renders-orientation-device")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
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
              outputBaseName = "red-square-device-orientation",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      // `id:desktop_small` is 1366×768dp at density 1.0 — landscape by nature.
      val natural = renderAndDecode(host, "previewId=red-square;device=id:desktop_small", "natural")
      assertEquals("device width should be honoured", 1366, natural.width)
      assertEquals("device height should be honoured", 768, natural.height)

      val portrait =
        renderAndDecode(
          host,
          "previewId=red-square;device=id:desktop_small;orientation=portrait",
          "device-portrait",
        )
      assertEquals("portrait should swap the device frame's width", 768, portrait.width)
      assertEquals("portrait should swap the device frame's height", 1366, portrait.height)

      // Idempotent against the device's own orientation: landscape on a landscape device is a
      // no-op, so the routers can re-apply the same rule without rotating it back.
      val landscape =
        renderAndDecode(
          host,
          "previewId=red-square;device=id:desktop_small;orientation=landscape",
          "device-landscape",
        )
      assertEquals("landscape should leave a landscape device alone", 1366, landscape.width)
      assertEquals("landscape should leave a landscape device alone", 768, landscape.height)

      // Explicit pixels still outrank the rotation, device or no device.
      val explicit =
        renderAndDecode(
          host,
          "previewId=red-square;device=id:desktop_small;orientation=portrait;widthPx=300",
          "device-explicit",
        )
      assertEquals("explicit widthPx should win over the orientation hint", 300, explicit.width)
      assertEquals("height stays the device's own", 768, explicit.height)
    } finally {
      host.shutdown()
    }
  }

  /**
   * Issue #1208 — the orientation hint must be idempotent: a request that already matches the base
   * aspect ratio should be a no-op, never a blind swap. Otherwise a landscape-shaped base (e.g.
   * 120×60) plus `orientation = landscape` would flip to portrait (60×120), and the same payload
   * sent twice would oscillate. Mirrors the LANDSCAPE → LANDSCAPE branch in `applyOverrides`,
   * `specFromPreviewIdPayload`, and `PreviewManifestRouter.submit`.
   */
  @Test
  fun orientationOverrideIsIdempotentForMatchingBase() {
    val outputDir = tempFolder.newFolder("renders-orientation-idempotent")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "landscape-rect",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 120,
              heightPx = 60,
              density = 1.0f,
              outputBaseName = "landscape-rect",
            ),
            PreviewManifestEntry(
              id = "portrait-rect",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 60,
              heightPx = 120,
              density = 1.0f,
              outputBaseName = "portrait-rect",
            ),
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      // Landscape base + orientation=landscape => no swap, stays 120×60.
      val landscapeNoOp =
        renderAndDecode(host, "previewId=landscape-rect;orientation=landscape", "landscape-noop")
      assertEquals(
        "orientation=landscape on landscape base should not swap widthPx",
        120,
        landscapeNoOp.width,
      )
      assertEquals(
        "orientation=landscape on landscape base should not swap heightPx",
        60,
        landscapeNoOp.height,
      )

      // Sending the same payload again must produce identical dims (idempotent).
      val landscapeRepeat =
        renderAndDecode(host, "previewId=landscape-rect;orientation=landscape", "landscape-repeat")
      assertEquals(
        "repeated orientation=landscape must be idempotent on width",
        120,
        landscapeRepeat.width,
      )
      assertEquals(
        "repeated orientation=landscape must be idempotent on height",
        60,
        landscapeRepeat.height,
      )

      // Portrait base + orientation=portrait => no swap, stays 60×120 (symmetric branch).
      val portraitNoOp =
        renderAndDecode(host, "previewId=portrait-rect;orientation=portrait", "portrait-noop")
      assertEquals(
        "orientation=portrait on portrait base should not swap widthPx",
        60,
        portraitNoOp.width,
      )
      assertEquals(
        "orientation=portrait on portrait base should not swap heightPx",
        120,
        portraitNoOp.height,
      )

      // Landscape base + orientation=portrait => swap to 60×120 (PORTRAIT branch).
      val landscapeToPortrait =
        renderAndDecode(
          host,
          "previewId=landscape-rect;orientation=portrait",
          "landscape-to-portrait",
        )
      assertEquals(
        "orientation=portrait on landscape base should swap widthPx",
        60,
        landscapeToPortrait.width,
      )
      assertEquals(
        "orientation=portrait on landscape base should swap heightPx",
        120,
        landscapeToPortrait.height,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiModeOverrideFlipsDarkAwareComposable() {
    val outputDir = tempFolder.newFolder("renders-uimode")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
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

      // `LocalSystemTheme provides SystemTheme.Light/Dark` is what flips
      // `isSystemInDarkTheme()` on Compose Desktop. Without the override reaching the
      // CompositionLocalProvider both renders would fall through to `SystemTheme.Unknown` and
      // pick the same colour.
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
   * **Regression guard for the pseudolocale drop on every daemon lane (#4371).**
   *
   * A pseudolocale override is two halves: the renderer folds `ar-XB` to its base locale for the
   * `LocaleList` / JVM-default-`Locale` state, and `PseudolocaleOverrideExtensionDesktop` — planned
   * from the **extension bag** — installs the around-composable that flips `LocalLayoutDirection`
   * and pseudolocalises `stringResource(...)`. But `localeTag` travels as a typed wire token, and
   * `JsonRpcServer.encodeRenderPayload` nulls tokenised fields out of the bag, so the planner was
   * handed `localeTag = null` on every payload-driven render and abstained: `?localeTag=ar-XB` on
   * the preview server came back plain LTR English, looking exactly like the feature was off. The
   * Gradle path plans from `params.locale` instead, which is why the baked catalog PNGs were right
   * and only the live daemon lane was wrong.
   *
   * [LayoutDirectionAwareSquare] paints `Rtl` blue and `Ltr` red, and the base locale `ar-XB` folds
   * to is `en` (LTR) — so the flip can only come from the around-composable. Before the fix this
   * rendered red.
   *
   * Driven through a **gated** [ExtensionRegistry] (see below), which covers the second half of the
   * same deployed failure: `serve` never enables extensions, so the planner also had to stop being
   * gated on one.
   */
  @Test
  fun pseudolocaleTagReachesTheAroundComposableOnThePayloadPath() {
    val outputDir = tempFolder.newFolder("renders-pseudolocale")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "direction-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "LayoutDirectionAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "direction-aware",
            )
          )
      )
    // The production seam, as `namedOverrideAppliesThroughGatedRegistryWithoutEnable` uses it: a
    // real registry whose `data/pseudolocale` extension is registered but never enabled — exactly
    // what `serve` builds, since only the MCP supervisor calls `extensions/enable`. The planner is
    // marked `AlwaysOnPreviewOverrideExtension`, so it must plan despite the gate; without the
    // marker this render is the deployed bug a second time over.
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(
            id = "data/pseudolocale",
            previewOverrideExtensions = listOf(PseudolocalePreviewOverrideExtensionDesktop()),
          )
        )
      )
    assertFalse(
      "the pseudolocale extension must be inactive — half of what this regression covers",
      registry.isActive("data/pseudolocale"),
    )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine = RenderEngine(previewOverrideExtensions = registry.activeOverrideExtensions()),
      )
    host.start()
    try {
      val base = renderAndDecode(host, "previewId=direction-aware", "pseudo-base")
      val baseRedPct = pixelMatchPct(base, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "no override must stay LTR (red); got ${"%.2f".format(baseRedPct * 100)}%",
        baseRedPct >= 0.95,
      )

      val bidi = renderAndDecode(host, "previewId=direction-aware;localeTag=ar-XB", "pseudo-bidi")
      val bidiBluePct = pixelMatchPct(bidi, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "localeTag=ar-XB must plan PseudolocaleOverrideExtensionDesktop so the composition flips " +
          "to RTL; got ${"%.2f".format(bidiBluePct * 100)}% blue — the pseudolocale bag drop is " +
          "back (#4371)",
        bidiBluePct >= 0.95,
      )

      // The accent pseudolocale is LTR: it plans the same extension (pseudolocalising
      // `stringResource`) without touching layout direction, so this render must stay red. A blue
      // one would mean the wrap flips direction for every pseudolocale, not just the bidi one.
      val accent =
        renderAndDecode(host, "previewId=direction-aware;localeTag=en-XA", "pseudo-accent")
      val accentRedPct = pixelMatchPct(accent, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "localeTag=en-XA must stay LTR; got ${"%.2f".format(accentRedPct * 100)}% red",
        accentRedPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * `localeTag` must reach `androidx.compose.ui.text.intl.Locale.current` — the locale CMP string
   * resources (`components-resources`) resolve from via `rememberResourceEnvironment()`. On
   * Skiko/desktop that reads the JVM default `Locale`, **not** the `LocalProvidableLocaleList`
   * composition local `localeProviders` sets, so before the fix a `@Preview(locale = "de")`
   * override flipped layout direction but `stringResource(...)` still rendered the base (English)
   * copy — every locale render came out pixel-identical to English. [LocaleAwareSquare] paints its
   * language subtag (green = `de`, blue = `ar`, red = base), so this proves the override now
   * reaches the locale `stringResource` reads. The trailing base render proves
   * [RenderEngine.tearDown] restores the process-global default `Locale` — the switch never leaks
   * past a render.
   */
  @Test
  fun localeTagOverrideReachesComposeResourceLocale() {
    val outputDir = tempFolder.newFolder("renders-locale")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "locale-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "LocaleAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "locale-aware",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val base = renderAndDecode(host, "previewId=locale-aware", "locale-base")
      val german = renderAndDecode(host, "previewId=locale-aware;localeTag=de", "locale-de")
      val arabic = renderAndDecode(host, "previewId=locale-aware;localeTag=ar", "locale-ar")

      val baseRedPct = pixelMatchPct(base, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "no override should read the base locale (red); got ${"%.2f".format(baseRedPct * 100)}%",
        baseRedPct >= 0.95,
      )
      val germanGreenPct = pixelMatchPct(german, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
      assertTrue(
        "localeTag=de must reach androidx.compose.ui.text.intl.Locale.current so CMP " +
          "stringResource localizes; got ${"%.2f".format(germanGreenPct * 100)}% green — the " +
          "locale axis is back to rendering English",
        germanGreenPct >= 0.95,
      )
      val arabicBluePct = pixelMatchPct(arabic, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "localeTag=ar must reach Locale.current too; got ${"%.2f".format(arabicBluePct * 100)}% blue",
        arabicBluePct >= 0.95,
      )

      // The JVM default Locale switch must not outlive the render: an un-overridden render after
      // two
      // overridden ones reads the base locale (red) again.
      val baseAfter = renderAndDecode(host, "previewId=locale-aware", "locale-base-after")
      val baseAfterRedPct =
        pixelMatchPct(baseAfter, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "the JVM default Locale must be restored after an override render; got " +
          "${"%.2f".format(baseAfterRedPct * 100)}% red",
        baseAfterRedPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Regression guard for the held-session locale leak: `setUp` applies the `localeTag` JVM-default
   * `Locale` override only for the *composition* window, not for the lifetime of the returned
   * `SceneState`. A held interactive/recording session can keep its `SceneState` alive (idle)
   * between renders, and `Locale.setDefault` is process-global — so if the override persisted on
   * the held state, any *other* render in the same daemon during that idle window would inherit the
   * wrong locale and mis-resolve CMP `stringResource(...)`. This asserts the JVM default is already
   * restored by the time `setUp` returns the held scene.
   */
  @Test
  fun localeOverrideDoesNotOutliveTheHeldScene() {
    val outputDir = tempFolder.newFolder("renders-locale-held")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val original = java.util.Locale.getDefault()
    val engine = RenderEngine()
    val state =
      engine.setUp(
        RenderSpec(
          previewId = "locale-held",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "LocaleAwareSquare",
          widthPx = 32,
          heightPx = 32,
          density = 1.0f,
          localeTag = "de",
        )
      )
    try {
      assertEquals(
        "the held scene must not keep the process-global JVM default Locale switched to the " +
          "override — that would leak `de` onto every other render in the daemon until it closed",
        original.language,
        java.util.Locale.getDefault().language,
      )
    } finally {
      engine.tearDown(state)
      java.util.Locale.setDefault(original)
    }
  }

  @Test
  fun wallpaperOverrideDrivesAmbientPrimaryColor() {
    val outputDir = tempFolder.newFolder("renders-wallpaper")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wallpaper-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "WallpaperAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "wallpaper-aware",
            )
          )
      )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(WallpaperPreviewOverrideExtension()))
          ),
      )
    host.start()
    try {
      val baseline = renderAndDecode(host, "previewId=wallpaper-aware", "wallpaper-baseline")
      val red =
        renderAndDecode(
          host,
          "previewId=wallpaper-aware;overrides=${encodeWallpaperBag("#FFFF0000")}",
          "wallpaper-red",
        )
      val blue =
        renderAndDecode(
          host,
          "previewId=wallpaper-aware;overrides=${encodeWallpaperBag("#FF0000FF")}",
          "wallpaper-blue",
        )

      // The ambient primary should differ from the seedless baseline AND between two distinct
      // seeds. The exact derived primary depends on `WallpaperColorScheme`; sampling a single
      // pixel is enough — the fixture paints a solid fill.
      val basePrimary = baseline.getRGB(baseline.width / 2, baseline.height / 2) and 0xFFFFFF
      val redPrimary = red.getRGB(red.width / 2, red.height / 2) and 0xFFFFFF
      val bluePrimary = blue.getRGB(blue.width / 2, blue.height / 2) and 0xFFFFFF
      assertNotEquals(
        "wallpaper override should change primary vs the seedless baseline",
        basePrimary,
        redPrimary,
      )
      assertNotEquals("different seeds should yield different primaries", redPrimary, bluePrimary)
      // The derived primary for a pure-red seed should still be predominantly red.
      assertTrue(
        "red-seed primary expected red-dominant, got 0x%06X".format(redPrimary),
        ((redPrimary shr 16) and 0xFF) > ((redPrimary shr 8) and 0xFF) &&
          ((redPrimary shr 16) and 0xFF) > (redPrimary and 0xFF),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun fontScaleOverrideReachesLocalDensity() {
    val outputDir = tempFolder.newFolder("renders-fontscale")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "font-scale",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "FontScaleAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "font-scale",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val unscaled = renderAndDecode(host, "previewId=font-scale;fontScale=1.0", "fontscale-1")
      val scaled = renderAndDecode(host, "previewId=font-scale;fontScale=2.0", "fontscale-2")

      // FontScaleAwareSquare paints black at fontScale<1.5, white at fontScale>=1.5. The override
      // reaches the composition iff `LocalDensity.current.fontScale` reflects the spec's value.
      val unscaledBlackPct =
        pixelMatchPct(unscaled, expectedRgb = 0x000000, perChannelTolerance = 8)
      val scaledWhitePct = pixelMatchPct(scaled, expectedRgb = 0xFFFFFF, perChannelTolerance = 8)
      assertTrue(
        "fontScale=1.0 render should be mostly black; got ${"%.2f".format(unscaledBlackPct * 100)}%",
        unscaledBlackPct >= 0.95,
      )
      assertTrue(
        "fontScale=2.0 render should be mostly white; got ${"%.2f".format(scaledWhitePct * 100)}%",
        scaledWhitePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * End-to-end proof that a **named override** (`knob.<key>`) reaches the composition and changes
   * the rendered pixels — the render side of the `/render?knob.<key>=…` serve URL. Renders
   * [OverridableSquare] with no seed (its `fill` knob returns the author-default red) and with a
   * `namedOverrides` seed of blue (the same map the serve layer builds from a knob URL), then
   * asserts the fill actually repainted. Requires the `PreviewOverridesPreviewOverrideExtension`
   * planner registered on the engine — without it the seed never reaches `previewOverrideColor`.
   */
  @Test
  fun namedOverrideChangesRenderedFill() {
    val outputDir = tempFolder.newFolder("renders-named-override")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "overridable",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "OverridableSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "overridable",
            )
          )
      )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension()))
          ),
      )
    host.start()
    try {
      // No seed: the `fill` knob returns its author default (red).
      val default = renderAndDecode(host, "previewId=overridable", "named-default")
      val defaultRedPct = pixelMatchPct(default, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "unseeded fill should be the author-default red; got ${"%.2f".format(defaultRedPct * 100)}%",
        defaultRedPct >= 0.95,
      )

      // Seed `fill = #FF42A5F5` (blue) via the namedOverrides bag — the same map a
      // `/render?knob.fill=…` URL builds. It must reach `previewOverrideColor` and repaint the
      // fill.
      val seeded =
        renderAndDecode(
          host,
          "previewId=overridable;overrides=${encodeNamedBag("fill", "#FF42A5F5")}",
          "named-seeded",
        )
      val seededBluePct = pixelMatchPct(seeded, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "seeded fill should repaint blue; got ${"%.2f".format(seededBluePct * 100)}%",
        seededBluePct >= 0.95,
      )
      // The crux of the "does knob.label=Ground actually change anything?" question: the seed must
      // change pixels vs the author default, not silently fall back to it.
      assertNotEquals(
        "a named override must change the render vs the author default",
        default.getRGB(default.width / 2, default.height / 2),
        seeded.getRGB(seeded.width / 2, seeded.height / 2),
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Regression for #4210 — the seed has to be in the controller **before the first composition
   * pass**, not one pass late.
   *
   * [namedOverrideChangesRenderedFill] above cannot catch this: it reads the knob straight into the
   * composition, so a seed applied after the first pass still lands, via the recomposition the
   * snapshot write triggers. A knob captured by a **keyless `remember`** has no second chance — the
   * initializer runs once, on the first pass, and every androidx `remember*State` factory
   * (`rememberTimePickerState`, `rememberDatePickerState`, …) is exactly that shape. When the seed
   * arrived late, a `@OverrideVariant` cell seeding one of those quietly published its unseeded
   * sibling's pixels, with no error and no diff.
   *
   * The baked `:renderer-desktop` lane never had the bug (`DesktopRendererMain` calls
   * `PreviewOverrideController.set` before it composes anything), which is why "baked is right,
   * live is wrong" was the reported shape.
   */
  @Test
  fun namedOverrideReachesAKeylessRememberOnTheFirstComposition() {
    val outputDir = tempFolder.newFolder("renders-remembered-override")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "remembered",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RememberedOverridableSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "remembered",
            )
          )
      )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension()))
          ),
      )
    host.start()
    try {
      val seeded =
        renderAndDecode(
          host,
          "previewId=remembered;overrides=${encodeNamedBag("fill", "#FF42A5F5")}",
          "remembered-seeded",
        )
      val bluePct = pixelMatchPct(seeded, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "a seed captured by a keyless remember must be the seeded blue, not the author default; " +
          "got ${"%.2f".format(bluePct * 100)}% blue",
        bluePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Regression for #4209 — the sibling symptom of the same late seed.
   *
   * An animation captures its initial value on the first composition pass. Seeding after that pass
   * doesn't set the value, it *retargets* an animation already sitting on the unseeded one — and a
   * render captures the first frame or two, so the PNG shows the value the seed was supposed to
   * replace. On the live lane that drew `ToggleButton`'s unchecked container with its checked shape
   * (and, at `xs`, a bare rectangle), for exactly the cells whose resting shape differs from their
   * checked shape.
   *
   * Asserted on a colour animation rather than a shape one: the property under test is *when* the
   * seed lands, and both animations take their initial value from the first pass.
   */
  @Test
  fun namedOverrideSettlesAnAnimatedValueWithoutAnimatingFromTheDefault() {
    val outputDir = tempFolder.newFolder("renders-animated-override")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "animated",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "AnimatedOverridableSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "animated",
            )
          )
      )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension()))
          ),
      )
    host.start()
    try {
      val seeded =
        renderAndDecode(
          host,
          "previewId=animated;overrides=${encodeNamedBag("fill", "#FF42A5F5")}",
          "animated-seeded",
        )
      val bluePct = pixelMatchPct(seeded, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "an animated value must start settled on the seed, not animate to it from the author " +
          "default; got ${"%.2f".format(bluePct * 100)}% blue",
        bluePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * The `previewId=` one-shot lane — the same bundle-backed live-daemon path as
   * [namedOverrideAppliesOnPreviewIdPayloadPath] — rotates the frame but used to leave the wrap
   * flags on their old axis, so a fixed-width / wrapped-height preview turned portrait kept
   * wrapping height and the measure-and-crop pass sized the axis that was no longer free (#3552
   * review). Asserted on the resolved spec rather than through a render, since the wrap intent is
   * what the crop pass consumes.
   */
  @Test
  fun previewIdPayloadTradesWrapAxisWithARotatedFrame() {
    val baseSpec =
      RenderSpec(
        previewId = "wrapped",
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "RedSquare",
        widthPx = 800,
        heightPx = 400,
        wrapHeight = true,
      )
    val host = DesktopHost(previewSpecResolver = { id -> baseSpec.takeIf { id == "wrapped" } })

    val rotated = host.specFromPreviewIdPayload("previewId=wrapped;orientation=portrait")

    assertNotNull(rotated)
    assertEquals(400, rotated!!.widthPx)
    assertEquals(800, rotated.heightPx)
    assertTrue("a rotated frame must wrap width instead", rotated.wrapWidth)
    assertFalse("...and no longer wrap height", rotated.wrapHeight)

    // Already portrait: no rotation, so the wrap intent is untouched.
    val untouched =
      host.specFromPreviewIdPayload(
        "previewId=wrapped;widthPx=400;heightPx=800;orientation=portrait"
      )
    assertFalse("explicit pixels suppress the swap", untouched!!.wrapWidth)
    assertTrue(untouched.wrapHeight)
  }

  /**
   * **Regression guard for the `serve` / preview.coo.ee named-override drop.**
   * [namedOverrideChangesRenderedFill] above wires the planner with the *ungated*
   * `PreviewOverrideExtensions(listOf(...))` (its `isActive` defaults to `{ true }`), so it never
   * exercised the `extensions/enable` gate the real bundle-backed live daemon runs under — and a
   * `?knob.label=…` edit silently no-op'd on the deployed preview server while every unit test
   * stayed green.
   *
   * This renders the same seeded `fill` knob through the **production seam**: a real
   * [ExtensionRegistry] whose `compose/overrides` extension is registered but **never enabled**
   * (exactly what `serve` does — only the MCP supervisor enables an allowlist), threading its
   * [ExtensionRegistry.activeOverrideExtensions] into the engine. The named-override host is marked
   * [AlwaysOnPreviewOverrideExtension], so the seed must still apply despite the extension being
   * inactive. Before the fix the gate skipped the planner and the fill fell back to author-default
   * red; the assertion below would fail — which is the deployed bug, now caught in CI.
   */
  @Test
  fun namedOverrideAppliesThroughGatedRegistryWithoutEnable() {
    val outputDir = tempFolder.newFolder("renders-named-override-gated")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "overridable",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "OverridableSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "overridable",
            )
          )
      )
    // A real registry with the named-override extension registered but NOT enabled — the exact
    // shape `serve` builds (it never calls extensions/enable). activeOverrideExtensions() returns
    // the gated `isActive` predicate the deployed daemon uses.
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(
            id = "compose/overrides",
            previewOverrideExtensions = listOf(PreviewOverridesPreviewOverrideExtension()),
          )
        )
      )
    assertFalse(
      "the override extension must be inactive — the whole point of this regression",
      registry.isActive("compose/overrides"),
    )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine = RenderEngine(previewOverrideExtensions = registry.activeOverrideExtensions()),
      )
    host.start()
    try {
      val seeded =
        renderAndDecode(
          host,
          "previewId=overridable;overrides=${encodeNamedBag("fill", "#FF42A5F5")}",
          "named-seeded-gated",
        )
      val seededBluePct = pixelMatchPct(seeded, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "a named override must apply on the un-enabled (serve) path too; got " +
          "${"%.2f".format(seededBluePct * 100)}% blue — the label/knob drop is back",
        seededBluePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * **Regression guard for the `serve` / preview.coo.ee named-override drop.** The bundle-backed
   * live daemon renders via a `previewId=<id>` payload (`JsonRpcServer.encodeRenderPayload`), which
   * [DesktopHost.dispatchRender] routes through [DesktopHost.specFromPreviewIdPayload] — NOT the
   * `className=`-based [RenderSpec.parseFromPayload] that every other test here exercises via
   * [PreviewManifestRouter] (the router rewrites `previewId` → `className=…`). That previewId path
   * rebuilt the spec with `base.copy(...)` and **dropped the `overrides=<b64>` extension bag**, so
   * a `?knob.<key>=…` edit silently no-op'd on the deployed server while display axes (fontScale /
   * uiMode / …) still applied — the exact deployed bug. Drive [DesktopHost] directly with a bare
   * previewId payload carrying a seeded `fill` knob and assert the fill actually repaints; before
   * the fix the bag never reached the render and it stayed author-default red.
   */
  @Test
  fun namedOverrideAppliesOnPreviewIdPayloadPath() {
    val outputDir = tempFolder.newFolder("renders-previewid-override")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseSpec =
      RenderSpec(
        previewId = "overridable",
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "OverridableSquare",
        widthPx = 32,
        heightPx = 32,
        density = 1.0f,
      )
    // A real DesktopHost (not the PreviewManifestRouter subclass), so a `previewId=…` payload with
    // NO `className=` takes the specFromPreviewIdPayload branch — the serve/bundle-daemon path.
    val host =
      DesktopHost(
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension()))
          ),
        previewSpecResolver = { id -> baseSpec.takeIf { id == "overridable" } },
      )
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload = "previewId=overridable;overrides=${encodeNamedBag("fill", "#FF42A5F5")}"
        )
      val result = host.submit(request, timeoutMs = 30_000)
      assertNotNull("pngPath must be populated", result.pngPath)
      val png = ByteArrayInputStream(File(result.pngPath!!).readBytes()).use { ImageIO.read(it) }
      val bluePct = pixelMatchPct(png, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "a named override on the previewId payload path must repaint blue; got " +
          "${"%.2f".format(bluePct * 100)}% — the serve knob-drop is back",
        bluePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * End-to-end proof that a `themeProvider` override wraps an arbitrary preview in an app-declared
   * `@ThemeCatalog` theme (the render side of the serve viewer's declared-theme selector). Renders
   * [WallpaperAwareSquare] — which paints the *ambient* `MaterialTheme.colorScheme.primary` with no
   * inner theme of its own — with no override (the M3 default primary) and with `themeProvider =
   * <BluePrimaryThemeProvider FQN>`, then asserts the fill actually repainted to that theme's blue
   * primary. The wrapper resolves off the render classloader through the same
   * `loadPreviewWrapperClass` → `Wrap` path `@PreviewWrapper` uses, so no manifest wrapper is
   * needed.
   */
  @Test
  fun themeProviderOverrideWrapsPreviewInDeclaredTheme() {
    val outputDir = tempFolder.newFolder("renders-theme-provider")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "ambient-primary",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "WallpaperAwareSquare",
              widthPx = 200,
              heightPx = 120,
              density = 1.0f,
              outputBaseName = "ambient-primary",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val default = renderAndDecode(host, "previewId=ambient-primary", "theme-default")
      val themed =
        renderAndDecode(
          host,
          "previewId=ambient-primary;overrides=" +
            encodeThemeProviderBag("ee.schimke.composeai.daemon.BluePrimaryThemeProvider"),
          "theme-blue",
        )

      // The declared theme's primary (0xFF1565C0) should dominate the themed render...
      val themedBluePct = pixelMatchPct(themed, expectedRgb = 0x1565C0, perChannelTolerance = 8)
      assertTrue(
        "themeProvider render should paint the declared theme's blue primary; got ${"%.2f".format(themedBluePct * 100)}%",
        themedBluePct >= 0.95,
      )
      // ...and the render must differ from the un-themed M3 default, not silently fall back to it.
      assertNotEquals(
        "a themeProvider override must change the render vs the default theme",
        default.getRGB(default.width / 2, default.height / 2),
        themed.getRGB(themed.width / 2, themed.height / 2),
      )

      // Emit before/after PNGs for the PR's visual evidence (mirrors
      // RenderEngineClearBackgroundTest's `build/clearbg-evidence/`): the default M3 primary vs the
      // declared theme's blue primary, proving `themeProvider` re-renders under the chosen theme.
      val evidenceDir = File("build/theme-evidence").apply { mkdirs() }
      ImageIO.write(default, "png", File(evidenceDir, "ambient-primary-default.png"))
      ImageIO.write(themed, "png", File(evidenceDir, "ambient-primary-themed.png"))
    } finally {
      host.shutdown()
    }
  }

  /**
   * A stale / misspelled `themeProvider` must fall back to the preview's declared
   * `@PreviewWrapper`, not strip it (which would drop a required wrapper and misrender — the case a
   * shared URL with a removed provider hits). The manifest pins `wrapperClassName =
   * BluePrimaryThemeProvider` (blue), and the request supplies a bogus `themeProvider` FQN; the
   * render must still be blue (declared wrapper applied), not the un-wrapped M3 default.
   */
  @Test
  fun badThemeProviderFallsBackToDeclaredWrapper() {
    val outputDir = tempFolder.newFolder("renders-theme-fallback")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wrapped-ambient",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "WallpaperAwareSquare",
              widthPx = 48,
              heightPx = 48,
              density = 1.0f,
              outputBaseName = "wrapped-ambient",
              // The preview's own declared @PreviewWrapper (blue), threaded into the render spec
              // via
              // the resolver — so a bad themeProvider must fall back to THIS, not strip it.
              params =
                PreviewParamsEntry(
                  wrapperClassName = "ee.schimke.composeai.daemon.BluePrimaryThemeProvider"
                ),
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val bogus =
        renderAndDecode(
          host,
          "previewId=wrapped-ambient;overrides=" +
            encodeThemeProviderBag("ee.schimke.composeai.daemon.NoSuchThemeProvider"),
          "bad-theme",
        )
      // Declared wrapper (blue) still applied — the bogus override didn't strip it.
      val bluePct = pixelMatchPct(bogus, expectedRgb = 0x1565C0, perChannelTolerance = 8)
      assertTrue(
        "a bad themeProvider must fall back to the declared @PreviewWrapper (blue); got ${"%.2f".format(bluePct * 100)}%",
        bluePct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Regression for yschimke/wear-m3-catalog#33 — the **held** (Live stream) lane has to seed a
   * variant too, not only the one-shot `renderNow` lane.
   *
   * `renderSpecFromInfo` resolves a synthetic `@OverrideVariant` preview's `previews.json` seed
   * into `RenderSpec.overrides.namedOverrides`, and `specFromPreviewIdPayload` layers a per-render
   * bag over it — that is the path [discoveredOverrideVariantKeepsItsOwnFigmaSvgAndState] covers.
   * `interactive/start` (what the viewer's **Live (stream)** toggle opens) goes through
   * `applyOverrides` instead, which adapted the spec into a [PreviewOverrideBaseSpec] naming only a
   * couple of the bag's fields. The seed was not among them, so the held scene composed the
   * variant's *base* state while the baked PNG beside it showed the variant:
   * `switchbutton__ideal__split` drew the un-split switch the moment a reader ticked Live.
   *
   * Renders both ids through a held session and asserts the pixels differ the way the seed says
   * they should — the base red, the variant its seeded blue.
   */
  @Test
  fun heldSessionSeedsAnOverrideVariantWithNoPerRenderBag() {
    val outputDir = tempFolder.newFolder("renders-held-override-variant")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseId = "OverridableSquare_Light"
    val variantId = "OverridableSquare_Light_VARIANT_blue"
    fun info(id: String, overrides: OverrideVariantSpec? = null) =
      PreviewInfoDto(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        methodName = "OverridableSquare",
        params = PreviewParamsDto(widthDp = 32, heightDp = 32, density = 1.0f),
        overrides = overrides,
      )
    val byId =
      listOf(
          info(baseId),
          info(
            variantId,
            OverrideVariantSpec(
              name = "blue",
              seeds =
                listOf(
                  OverrideSeed(key = "fill", kind = OverrideSeedKind.COLOR, raw = "#FF42A5F5")
                ),
            ),
          ),
        )
        .associate { it.id to renderSpecFromInfo(it) }
    val host =
      DesktopHost(
        engine =
          RenderEngine(
            outputDir = outputDir,
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension())),
          ),
        previewSpecResolver = byId::get,
      )
    host.start()
    try {
      // The viewer opens Live with the display fields only — no `knob.*` in the bag at all, which
      // is precisely the case the dropped seed made indistinguishable from the primary.
      val base = heldRender(host, baseId, "held-base")
      val variant = heldRender(host, variantId, "held-variant")

      val basePct = pixelMatchPct(base, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "the un-seeded base must hold its author-default red; got ${"%.2f".format(basePct * 100)}%",
        basePct >= 0.95,
      )
      val variantPct = pixelMatchPct(variant, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      assertTrue(
        "the held variant must compose its baked seed (blue), not the base state; got " +
          "${"%.2f".format(variantPct * 100)}%",
        variantPct >= 0.95,
      )
      assertNotEquals(
        "a variant browsed live must not render identically to its primary",
        base.getRGB(base.width / 2, base.height / 2),
        variant.getRGB(variant.width / 2, variant.height / 2),
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * The reported shape of yschimke/wear-m3-catalog#33, on a fixture whose two states differ
   * *structurally*: [SplittableSwitchRow] draws one tap target unseeded and two when `split` is
   * seeded true.
   *
   * [heldSessionSeedsAnOverrideVariantWithNoPerRenderBag] above is the rigorous assertion (a flat
   * fill, matched per-pixel); this one is the legible one — the frames a reader compares. With the
   * seed dropped, the variant's held frame was **byte-identical** to its primary's, which is why
   * the bug read as "in Live mode the split switch becomes the normal one" rather than as an error.
   *
   * Set `HELD_VARIANT_DEMO_DIR` to write both frames out; that is how
   * `docs/evidence/live-variant-seed/` is regenerated.
   */
  @Test
  fun heldSessionSeedsTheSplitVariantTheIssueReported() {
    val outputDir = tempFolder.newFolder("renders-held-split-variant")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseId = "SplittableSwitchRow_Light"
    val variantId = "SplittableSwitchRow_Light_VARIANT_split"
    fun info(id: String, overrides: OverrideVariantSpec? = null) =
      PreviewInfoDto(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        methodName = "SplittableSwitchRow",
        params = PreviewParamsDto(widthDp = 200, heightDp = 64, density = 2.0f),
        overrides = overrides,
      )
    val byId =
      listOf(
          info(baseId),
          info(
            variantId,
            OverrideVariantSpec(
              name = "split",
              seeds =
                listOf(OverrideSeed(key = "split", kind = OverrideSeedKind.BOOLEAN, raw = "true")),
            ),
          ),
        )
        .associate { it.id to renderSpecFromInfo(it) }
    val host =
      DesktopHost(
        engine =
          RenderEngine(
            outputDir = outputDir,
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(PreviewOverridesPreviewOverrideExtension())),
          ),
        previewSpecResolver = byId::get,
      )
    host.start()
    try {
      val primary = heldRender(host, baseId, "held-split-primary")
      val variant = heldRender(host, variantId, "held-split-variant")

      System.getenv("HELD_VARIANT_DEMO_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { dir ->
          val target = File(dir).also { it.mkdirs() }
          ImageIO.write(primary, "png", File(target, "primary.png"))
          ImageIO.write(variant, "png", File(target, "variant.png"))
        }

      assertNotEquals(
        "a variant whose seed changes the component's structure must not render as its primary",
        pixelSignature(primary),
        pixelSignature(variant),
      )
    } finally {
      host.shutdown()
    }
  }

  /** Cheap whole-frame identity — enough to say "these two renders are the same picture". */
  private fun pixelSignature(img: java.awt.image.BufferedImage): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        digest.update(byteArrayOf((rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte()))
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** One frame off a held ([DesktopHost.acquireInteractiveSession]) session — the Live lane. */
  private fun heldRender(
    host: DesktopHost,
    previewId: String,
    label: String,
  ): java.awt.image.BufferedImage {
    val session =
      host.acquireInteractiveSession(
        previewId = previewId,
        classLoader =
          OverrideIntegrationTest::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
      )
    try {
      val result = session.render(requestId = RenderHost.nextRequestId())
      assertNotNull("$label: pngPath must be populated", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("$label: rendered PNG must exist", pngFile.exists())
      return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
        ?: error("$label: PNG failed to decode")
    } finally {
      session.close()
    }
  }

  private fun renderAndDecode(
    host: PreviewManifestRouter,
    payload: String,
    label: String,
  ): java.awt.image.BufferedImage {
    val request = RenderRequest.Render(payload = payload)
    val result = host.submit(request, timeoutMs = 30_000)
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
  private fun encodeWallpaperBag(seedColor: String): String {
    val json = Json { encodeDefaults = false }
    val bag = PreviewOverrides(wallpaper = WallpaperOverride(seedColor = seedColor))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
      )
  }

  /** A base64 `overrides=` bag carrying a single `themeProvider` FQN. */
  private fun encodeThemeProviderBag(fqn: String): String {
    val json = Json { encodeDefaults = false }
    val bag = PreviewOverrides(themeProvider = fqn)
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
      )
  }

  /** A base64 `overrides=` bag carrying a single named colour knob (`key = argb`). */
  private fun encodeNamedBag(key: String, argb: String): String {
    val json = Json { encodeDefaults = false }
    val bag = PreviewOverrides(namedOverrides = mapOf(key to PreviewOverrideValue.ColorValue(argb)))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
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
