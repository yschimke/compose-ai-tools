@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import ee.schimke.composeai.data.render.IrSidecarChannel
import kotlinx.coroutines.runBlocking

/**
 * Renders a Glance Wear widget preview **and preserves its encoded RemoteCompose document**.
 *
 * A Wear widget's value is its encoded document — the `RemoteDocument` byte stream the widget host
 * replays. The render pipeline carries that as the `<stem>.rc` sidecar (packed into the portable
 * bundle by `BundlePreviewTask.resolvePreviewIr`), so a bundled widget travels as **data, not
 * compiled `@Preview` bytecode**.
 *
 * The upstream [WearWidgetPreview] captures that document internally
 * (`WearWidgetDocument.captureRawContent(isInspectionMode = true)`) but keeps the bytes to itself
 * and only rasters — so a preview that calls it directly emits **no** `.rc`, and the widget rides
 * the bundle as bytecode. This wrapper closes that gap: it captures the document the same way and
 * hands the bytes to [IrSidecarChannel] (which the render harness drains into the sidecar), then
 * delegates to the real [WearWidgetPreview] for the pixels. One call, both the encoded doc and the
 * rendered PNG.
 *
 * The capture is best-effort: outside a daemon/test render there is no current preview id, so
 * [IrSidecarChannel.offer] is a no-op and only the raster runs (e.g. Android Studio's preview pane).
 *
 * ## Backgrounds belong here, not in [content]
 *
 * Pass the widget's fill as [background]. The host draws the squircle container — rounded
 * background + padding — and lays [content] out *inside* that frame, already inset by the padding.
 * Content that paints its own full-bleed background therefore paints a **square-cornered rectangle
 * inside the rounded container**: it can't reach the corners, and it isn't clipped to the corner
 * radius. Handing the fill to the document's `background` lets `WearWidgetContainer` paint it as
 * the container's own surface — corner-clipped and edge-to-edge. This mirrors upstream
 * `wear-os-samples/WearWidget`, which builds `WearWidgetDocument(background = …)` and keeps its
 * content transparent.
 */
@Composable
fun CapturingWearWidgetPreview(
  params: WearWidgetParams,
  background: WearWidgetBrush = WearWidgetBrush,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current
  // Capture once per (params, background, content) — mirrors the memoised `runBlocking` capture in
  // the connector's `RemoteOverridablePreview`. `captureRawContent` builds the same document
  // `WearWidgetPreview` will, so the sidecar matches the raster.
  remember(params, background, content) {
    // Best-effort: an IR capture must never fail the raster. But it must not fail *silently*
    // either — a swallowed `NoSuchMethodError` from a coroutines version skew is precisely how this
    // capture once degraded to "renders fine, emits no `.rc`" with a green build. Catch `Throwable`
    // (linkage errors are not `Exception`s) and say so on stderr, so a missing sidecar is
    // diagnosable from the render log instead of being invisible.
    try {
      val raw =
        runBlocking {
          WearWidgetDocument(background, content)
            .captureRawContent(context, params, /* isInspectionMode = */ true)
        }
      IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, raw.rcDocument)
    } catch (t: Throwable) {
      System.err.println(
        "CapturingWearWidgetPreview: failed to capture the widget's RemoteCompose document; " +
          "this render will emit no .rc sidecar. Cause: $t"
      )
    }
    Unit
  }
  WearWidgetPreview(params = params, background = background, content = content)
}
