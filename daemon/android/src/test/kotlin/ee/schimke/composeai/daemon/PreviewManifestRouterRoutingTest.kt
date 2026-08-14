package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.OverrideSeed
import ee.schimke.composeai.data.overrides.OverrideSeedKind
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plumbing-only coverage for [PreviewManifestRouter.routePayload]. Issue #1440 added two new wire
 * tokens to the routed `RenderSpec` payload — `wrapperClassName` and `kind=GLANCE_APPWIDGET` —
 * sourced from the nested `params` block the gradle plugin's discovery emits. The end-to-end
 * coverage (real rendering through `RobolectricHost`) for the same fields lives in the harness's
 * S3.5+/S4 scenarios; this test sits on the routing layer alone so it stays cheap and runs in the
 * unit-test source set.
 *
 * The wrapper case asserts that a `params.wrapperClassName` set in the manifest emerges in the
 * routed payload as a top-level `wrapperClassName=` token — without this the render body cannot
 * route `@PreviewWrapper` previews through the wrapper's `Wrap(content)` (the upstream annotation
 * has `AnnotationRetention.BINARY`, so the runtime-reflection fallback misses every real-world
 * preview).
 *
 * The Glance case asserts that `params.kind=GLANCE_APPWIDGET` propagates through routing so the
 * render body dispatches through `GlanceAppWidgetPreviewComposable` instead of falling through to
 * `InvokeComposable`.
 */
class PreviewManifestRouterRoutingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `routePayload applies manifest variant seed and lets a live override win`() {
    val previewId = "CheckboxButtonChecked_VARIANT_unchecked"
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "com.example.WearPreviewsKt",
              functionName = "CheckboxButtonChecked",
              overrides =
                OverrideVariantSpec(
                  name = "unchecked",
                  seeds =
                    listOf(
                      OverrideSeed(
                        key = "checked",
                        kind = OverrideSeedKind.BOOLEAN,
                        raw = "false",
                      )
                    ),
                ),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val baked = RenderSpec.parseFromPayloadOrNull(router.routePayload("previewId=$previewId"))
    assertEquals(
      PreviewOverrideValue.BooleanValue(false),
      baked!!.overrides!!.namedOverrides!!["checked"],
    )

    val live =
      PreviewOverrides(namedOverrides = mapOf("checked" to PreviewOverrideValue.BooleanValue(true)))
    val liveToken =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
          json.encodeToString(PreviewOverrides.serializer(), live).toByteArray(Charsets.UTF_8)
        )
    val layered =
      RenderSpec.parseFromPayloadOrNull(
        router.routePayload("previewId=$previewId;overrides=$liveToken")
      )
    assertEquals(
      PreviewOverrideValue.BooleanValue(true),
      layered!!.overrides!!.namedOverrides!!["checked"],
    )
  }

  @Test
  fun `routePayload forwards wrapperClassName from nested params`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wrapped",
              className = "com.example.PreviewsKt",
              functionName = "Wrapped",
              params =
                PreviewParamsEntry(
                  widthDp = 100,
                  heightDp = 100,
                  wrapperClassName = "com.example.RemotePreviewWrapper",
                ),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=wrapped")

    assertTrue(
      "routed payload must carry wrapperClassName so RenderEngine can route through Wrap(content). " +
        "payload=$routed",
      routed.contains("wrapperClassName=com.example.RemotePreviewWrapper"),
    )
  }

  @Test
  fun `routePayload forwards the PreviewParameter provider from nested params`() {
    // Issue #3027: the provider FQN is the only thing that lets the render body resolve a
    // parameterized preview's `(<T>, Composer, int)` overload — `@PreviewParameter` has BINARY
    // retention, so the sandbox can't recover it by reflecting on the composable. Without this
    // token every such preview died on `getDeclaredComposableMethod` with NoSuchMethodException.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "themed",
              className = "com.example.PreviewsKt",
              functionName = "ThemedPreview",
              params =
                PreviewParamsEntry(
                  widthDp = 100,
                  heightDp = 100,
                  previewParameterProviderClassName = "com.example.ThemeProvider",
                  previewParameterLimit = 3,
                ),
            )
          )
      )

    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=themed")

    assertTrue(
      "routed payload must carry the provider FQN. payload=$routed",
      routed.contains("previewParameterProvider=com.example.ThemeProvider"),
    )
    assertTrue(
      "a non-default limit rides along too. payload=$routed",
      routed.contains("previewParameterLimit=3"),
    )
    val spec = RenderSpec.parseFromPayloadOrNull(routed)
    assertEquals("com.example.ThemeProvider", spec!!.previewParameterProviderClassName)
    assertEquals(3, spec.previewParameterLimit)
  }

  @Test
  fun `routePayload omits the provider token for an ordinary preview`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "plain",
              className = "com.example.PreviewsKt",
              functionName = "Plain",
              params = PreviewParamsEntry(widthDp = 100, heightDp = 100),
            )
          )
      )

    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=plain")

    assertFalse(routed.contains("previewParameterProvider="))
    assertEquals(
      null,
      RenderSpec.parseFromPayloadOrNull(routed)!!.previewParameterProviderClassName,
    )
  }

  @Test
  fun `routePayload emits wrapHeight for a widthDp-only preview - the TcpConnectPanel shape`() {
    // Regression for the figma-svg collapse: the wrap flags MUST ride the serialized payload, or
    // RenderSpec.parseFromPayloadOrNull defaults them false and RenderEngine never enters the
    // measure-and-crop path — leaving no-height previews reflowed past the 320px frame to zero.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "tcp",
              className = "com.example.PreviewsKt",
              functionName = "TcpConnectPanel",
              params = PreviewParamsEntry(widthDp = 340, density = 2.625f, showBackground = true),
            )
          )
      )
    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=tcp")

    assertTrue("width is pinned → no wrapWidth. payload=$routed", !routed.contains("wrapWidth="))
    assertTrue(
      "no height → wrapHeight=true must ride the payload. payload=$routed",
      routed.contains("wrapHeight=true"),
    )
    assertTrue(
      "pinned width stays 340dp × 2.625 = 893px. payload=$routed",
      routed.contains("widthPx=893"),
    )
    assertTrue(
      "wrapped height uses the 800dp sandbox bound (2100px). payload=$routed",
      routed.contains("heightPx=2100"),
    )
  }

  @Test
  fun `routePayload emits both wrap flags for a no-size preview`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "sticker",
              className = "com.example.PreviewsKt",
              functionName = "Sticker",
              params = PreviewParamsEntry(density = 2.0f, showBackground = true),
            )
          )
      )
    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=sticker")

    assertTrue(
      "wrapWidth=true must ride the payload. payload=$routed",
      routed.contains("wrapWidth=true"),
    )
    assertTrue(
      "wrapHeight=true must ride the payload. payload=$routed",
      routed.contains("wrapHeight=true"),
    )
  }

  @Test
  fun `routePayload omits wrap flags for an explicitly sized preview`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "sized",
              className = "com.example.PreviewsKt",
              functionName = "Sized",
              params = PreviewParamsEntry(widthDp = 300, heightDp = 170),
            )
          )
      )
    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=sized")

    assertFalse(
      "explicit size → no wrapWidth token. payload=$routed",
      routed.contains("wrapWidth="),
    )
    assertFalse(
      "explicit size → no wrapHeight token. payload=$routed",
      routed.contains("wrapHeight="),
    )
  }

  @Test
  fun `routePayload omits wrapHeight when an inbound heightPx override pins the axis`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "tcp",
              className = "com.example.PreviewsKt",
              functionName = "TcpConnectPanel",
              params = PreviewParamsEntry(widthDp = 340, density = 2.625f),
            )
          )
      )
    val routed =
      PreviewManifestRouter(manifest = manifest).routePayload("previewId=tcp;heightPx=900")

    assertFalse(
      "inbound heightPx override pins the axis → no wrapHeight. payload=$routed",
      routed.contains("wrapHeight="),
    )
    assertTrue("inbound heightPx override wins. payload=$routed", routed.contains("heightPx=900"))
  }

  @Test
  fun `routePayload omits wrapperClassName when params has none`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "plain",
              className = "com.example.PreviewsKt",
              functionName = "Plain",
              params = PreviewParamsEntry(widthDp = 100, heightDp = 100),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=plain")

    assertFalse(
      "no wrapperClassName in manifest → no wrapperClassName= token in routed payload. payload=$routed",
      routed.contains("wrapperClassName="),
    )
  }

  @Test
  fun `routePayload forwards GLANCE_APPWIDGET kind from nested params`() {
    // The gradle plugin's discovery emits `params.kind = "GLANCE_APPWIDGET"` for
    // `@androidx.glance.preview.Preview` functions; the daemon's render path needs the `kind=`
    // token in the rewritten payload to dispatch to `GlanceAppWidgetPreviewComposable`.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "glance",
              className = "com.example.GlancePreviewsKt",
              functionName = "MyWidgetPreview",
              params = PreviewParamsEntry(widthDp = 200, heightDp = 200, kind = "GLANCE_APPWIDGET"),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=glance")

    assertTrue(
      "Glance previews must carry kind=GLANCE_APPWIDGET so RenderEngine routes through " +
        "GlanceAppWidgetPreviewComposable. payload=$routed",
      routed.contains("kind=GLANCE_APPWIDGET"),
    )
  }

  @Test
  fun `routePayload preserves Wear theme catalog kind and display name`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wearthemecatalog__Dark",
              className = "com.example.JetcasterWearDarkThemeCatalog",
              functionName = "Dark theme",
              params =
                PreviewParamsEntry(
                  name = "Dark",
                  widthDp = 227,
                  heightDp = 227,
                  kind = "WEAR_THEME_CATALOG",
                  wrapperClassName = "com.example.JetcasterWearDarkThemeCatalog",
                ),
            )
          )
      )

    val routed =
      PreviewManifestRouter(manifest = manifest).routePayload("previewId=wearthemecatalog__Dark")
    val spec = RenderSpec.parseFromPayloadOrNull(routed)!!

    assertTrue(
      "Wear theme catalogs must retain their strategy kind. payload=$routed",
      spec.kind == "WEAR_THEME_CATALOG",
    )
    assertTrue(
      "the synthetic sheet's clean theme name must survive routing. payload=$routed",
      spec.previewName == "Dark",
    )
    assertTrue(
      "the provider FQN must survive routing. payload=$routed",
      spec.wrapperClassName == "com.example.JetcasterWearDarkThemeCatalog",
    )
  }

  @Test
  fun `routePayload derives uiMode=dark from the manifest night bit`() {
    // A `_Dark` multipreview variant differs from its `_Light` sibling ONLY by
    // `@Preview(uiMode = UI_MODE_NIGHT_YES)`. Dropping the bit rendered both variants identically
    // (theme = whatever the previous render's night qualifier left behind), so the bundled
    // layout/semantics/figma-svg data products for the two variants were byte-equal and the
    // published catalog SVG's theme was render-order-dependent.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "screen_Dark",
              className = "com.example.PreviewsKt",
              functionName = "Screen",
              // UI_MODE_NIGHT_YES (0x20) | UI_MODE_TYPE_NORMAL (0x01)
              params = PreviewParamsEntry(widthDp = 411, heightDp = 914, uiMode = 0x21),
            ),
            PreviewManifestEntry(
              id = "screen_Light",
              className = "com.example.PreviewsKt",
              functionName = "Screen",
              // UI_MODE_NIGHT_NO (0x10) | UI_MODE_TYPE_NORMAL (0x01)
              params = PreviewParamsEntry(widthDp = 411, heightDp = 914, uiMode = 0x11),
            ),
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val dark = router.routePayload("previewId=screen_Dark")
    val light = router.routePayload("previewId=screen_Light")

    assertTrue("night bit must emit uiMode=dark. payload=$dark", dark.contains("uiMode=dark"))
    // The no-night case must emit an EXPLICIT light: Robolectric qualifiers apply incrementally
    // (`setQualifiers("+…")`), so omitting the token would inherit the previous render's `night`.
    assertTrue("non-night must emit uiMode=light. payload=$light", light.contains("uiMode=light"))
  }

  @Test
  fun `routePayload lets an inbound uiMode override win over the manifest bit`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "screen",
              className = "com.example.PreviewsKt",
              functionName = "Screen",
              params = PreviewParamsEntry(widthDp = 411, heightDp = 914, uiMode = 0x21),
            )
          )
      )
    val routed =
      PreviewManifestRouter(manifest = manifest).routePayload("previewId=screen;uiMode=light")

    assertTrue("inbound override wins. payload=$routed", routed.contains("uiMode=light"))
    assertFalse(
      "manifest bit must not double-emit. payload=$routed",
      routed.contains("uiMode=dark"),
    )
  }

  /**
   * Issue #2883. `fontScale` and `locale` were the last two `@Preview` axes the router still
   * dropped: the DTO didn't declare them, so `ignoreUnknownKeys` swallowed them and a
   * large-font/locale annotation rendered exactly like its default sibling. That made the data
   * products those renders carry — layout, semantics and, most visibly, `compose/figma-svg` —
   * byte-identical across variants whose Gradle-rendered PNGs plainly differ.
   */
  @Test
  fun `routePayload forwards fontScale and locale from nested params`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "large-font",
              className = "com.example.PreviewsKt",
              functionName = "FeedScreen",
              params = PreviewParamsEntry(widthDp = 400, fontScale = 1.5f, locale = "ar-XB"),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=large-font")

    assertTrue("fontScale must reach the render. payload=$routed", routed.contains("fontScale=1.5"))
    assertTrue("locale must reach the render. payload=$routed", routed.contains("localeTag=ar-XB"))
  }

  @Test
  fun `routePayload lets an inbound fontScale override the manifest`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "large-font",
              className = "com.example.PreviewsKt",
              functionName = "FeedScreen",
              params = PreviewParamsEntry(widthDp = 400, fontScale = 1.5f),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=large-font;fontScale=2.0")

    assertTrue("inbound override wins. payload=$routed", routed.contains("fontScale=2.0"))
    assertFalse("manifest value must not double-emit. payload=$routed", routed.contains("=1.5"))
  }

  @Test
  fun `routePayload omits a redundant unit fontScale`() {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "plain",
              className = "com.example.PreviewsKt",
              functionName = "FeedScreen",
              params = PreviewParamsEntry(widthDp = 400, fontScale = 1.0f),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=plain")

    assertFalse("1.0 is the annotation default. payload=$routed", routed.contains("fontScale="))
  }

  @Test
  fun `routePayload sizes a manifest-declared device from the device catalog`() {
    // #3113 made a device frame own its size, so the resolver stops honouring the manifest's
    // widthDp/heightDp for a device preview. Nothing then supplied the device's own extent on this
    // path — `DeviceDimensions` was consulted only for an INBOUND `device=` override — so a Wear
    // preview routed at the fixed 320² default instead of 192dp × 2.0 = 384². The routed payload is
    // what the render body consumes, so assert the frame there, not just the wrap flags.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wear",
              className = "com.example.PreviewsKt",
              functionName = "WearTile",
              params =
                PreviewParamsEntry(
                  device = "id:wearos_small_round",
                  widthDp = 192,
                  heightDp = 192,
                  density = 2.0f,
                ),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=wear")

    assertTrue("device frame width. payload=$routed", routed.contains("widthPx=384;"))
    assertTrue("device frame height. payload=$routed", routed.contains("heightPx=384;"))
    assertTrue(
      "device id forwarded. payload=$routed",
      routed.contains("device=id:wearos_small_round"),
    )
    assertFalse("a device frame never wraps. payload=$routed", routed.contains("wrapWidth=true"))
  }

  @Test
  fun `routePayload truncates a fractional device frame exactly like the bake`() {
    // Batch/live parity: `RenderPreviewsTask` converts a catalog-derived device frame with
    // `(dp * density).toInt()`, so this path must truncate too. `id:pixel_5` is the case that
    // exposes a rounding difference — 393dp × 2.75 = 1080.75 — where rounding half-up would put
    // the live render at 1081 px against a 1080 px baked snapshot.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "phone",
              className = "com.example.PreviewsKt",
              functionName = "Phone",
              params = PreviewParamsEntry(device = "id:pixel_5", density = 2.75f),
            )
          )
      )

    val routed = PreviewManifestRouter(manifest = manifest).routePayload("previewId=phone")

    assertTrue(
      "393dp × 2.75 truncates to 1080, not 1081. payload=$routed",
      routed.contains("widthPx=1080;"),
    )
    assertTrue("851dp × 2.75 truncates to 2340. payload=$routed", routed.contains("heightPx=2340;"))
  }

  @Test
  fun `routePayload keeps an inbound explicit size over the device frame`() {
    // Precedence is unchanged: an explicit inbound widthPx still beats the device-derived extent.
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wear",
              className = "com.example.PreviewsKt",
              functionName = "WearTile",
              params = PreviewParamsEntry(device = "id:wearos_small_round", density = 2.0f),
            )
          )
      )
    val router = PreviewManifestRouter(manifest = manifest)

    val routed = router.routePayload("previewId=wear;widthPx=600")

    assertTrue("inbound width wins. payload=$routed", routed.contains("widthPx=600;"))
    assertTrue("height still device-derived. payload=$routed", routed.contains("heightPx=384;"))
  }

  @Test
  fun `routePayload rotates a landscape device frame for orientation=portrait`() {
    // #3547 — `?device=id:pixel_tablet&orientation=portrait` on the preview server rendered a
    // 2560x1600 landscape bitmap. The Android router forwarded `orientation` as a bare token and
    // never touched the device-derived pixels, so the frame stayed landscape while
    // `RenderEngine.applyPreviewQualifiers` derived a contradicting `port` qualifier from it.
    val router = routerWithSizedPreview()

    val routed = router.routePayload("previewId=sized;device=id:pixel_tablet;orientation=portrait")

    // Pixel Tablet: 1280x800dp at density 2.0 => 2560x1600px landscape, rotated to 1600x2560.
    assertTrue("expected widthPx=1600 in $routed", routed.contains("widthPx=1600;"))
    assertTrue("expected heightPx=2560 in $routed", routed.contains("heightPx=2560;"))
    assertTrue(
      "orientation token must still ride along in $routed",
      routed.contains("orientation=portrait"),
    )
  }

  @Test
  fun `routePayload leaves a device frame already in the requested orientation alone`() {
    val router = routerWithSizedPreview()

    val routed = router.routePayload("previewId=sized;device=id:pixel_tablet;orientation=landscape")

    assertTrue("expected widthPx=2560 in $routed", routed.contains("widthPx=2560;"))
    assertTrue("expected heightPx=1600 in $routed", routed.contains("heightPx=1600;"))
  }

  @Test
  fun `routePayload lets explicit pixels outrank the orientation request`() {
    val router = routerWithSizedPreview()

    val routed =
      router.routePayload("previewId=sized;device=id:pixel_tablet;orientation=portrait;widthPx=900")

    // Naming exact pixels outranks the rotation, so the caller's 900 stays on the width axis
    // rather than being swapped onto the height.
    assertTrue("expected widthPx=900 in $routed", routed.contains("widthPx=900;"))
    assertTrue("expected heightPx=1600 in $routed", routed.contains("heightPx=1600;"))
  }

  @Test
  fun `routePayload rotates the manifest frame when no device is given`() {
    // Orientation is not a device-only control: a preview with its own dp gets rotated too.
    val router = routerWithSizedPreview()

    val routed = router.routePayload("previewId=sized;orientation=landscape")

    assertTrue("expected widthPx=400 in $routed", routed.contains("widthPx=400;"))
    assertTrue("expected heightPx=200 in $routed", routed.contains("heightPx=200;"))
  }

  /** A 200x400px portrait preview (100x200dp at density 2.0), sized on both axes so no wrap. */
  private fun routerWithSizedPreview(): PreviewManifestRouter =
    PreviewManifestRouter(
      manifest =
        PreviewManifest(
          previews =
            listOf(
              PreviewManifestEntry(
                id = "sized",
                className = "com.example.PreviewsKt",
                functionName = "Sized",
                params = PreviewParamsEntry(widthDp = 100, heightDp = 200, density = 2.0f),
              )
            )
        )
    )
}
