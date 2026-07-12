package ee.schimke.composeai.daemon

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
    assertTrue("no height → wrapHeight=true must ride the payload. payload=$routed",
      routed.contains("wrapHeight=true"))
    assertTrue("pinned width stays 340dp × 2.625 = 892px. payload=$routed",
      routed.contains("widthPx=892"))
    assertTrue("wrapped height uses the 800dp sandbox bound (2100px). payload=$routed",
      routed.contains("heightPx=2100"))
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

    assertTrue("wrapWidth=true must ride the payload. payload=$routed", routed.contains("wrapWidth=true"))
    assertTrue("wrapHeight=true must ride the payload. payload=$routed", routed.contains("wrapHeight=true"))
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

    assertFalse("explicit size → no wrapWidth token. payload=$routed", routed.contains("wrapWidth="))
    assertFalse("explicit size → no wrapHeight token. payload=$routed", routed.contains("wrapHeight="))
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

    assertFalse("inbound heightPx override pins the axis → no wrapHeight. payload=$routed",
      routed.contains("wrapHeight="))
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
}
