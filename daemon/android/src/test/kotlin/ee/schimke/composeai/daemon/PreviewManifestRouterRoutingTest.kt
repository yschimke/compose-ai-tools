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
