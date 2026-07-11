package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
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
