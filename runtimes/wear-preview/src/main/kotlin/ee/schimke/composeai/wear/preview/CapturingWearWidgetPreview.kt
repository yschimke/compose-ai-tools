@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.RendererVersion
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.rcembedded.player.ExperimentalRemoteDocumentPlayer
import ee.schimke.composeai.rcembedded.player.enableEncodedImageReferences
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
 * the bundle as bytecode. This wrapper closes that gap: it captures the document the same way,
 * hands the bytes to [IrSidecarChannel] (which the render harness drains into the sidecar), and
 * then plays them. One call, both the encoded doc and the rendered PNG.
 *
 * The capture is best-effort: outside a daemon/test render there is no current preview id, so
 * [IrSidecarChannel.offer] is a no-op and only the raster runs (e.g. Android Studio's preview
 * pane).
 *
 * ## Which player draws
 *
 * The captured bytes are played by the Compose Multiplatform player ([WearWidgetPreviewPlayer.CMP])
 * — a widget composes into real Compose nodes rather than into one opaque `View`, which is what
 * stopped every widget preview reporting the same unlabelled `RemoteComposePlayer` accessibility
 * error (issue #5259). Select the View-backed lane with `-PcomposePreview.rcPlayer=view` (or
 * `-Dcomposeai.render.rcPlayer=view` on the render JVM); see [WearWidgetPreviewPlayer]. Either way
 * the pixels are drawn from the *same* captured document, sized exactly as upstream sizes it — the
 * widget's footprint plus its container padding.
 *
 * Where the embedded player is not on the render classpath, or the capture itself failed, this
 * falls back to the upstream [WearWidgetPreview] rather than failing the render — the same
 * classloader gate the connector's replay lane uses.
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
 *
 * ## Renderer version
 *
 * [useSafeFallbackRendererVersion] mirrors upstream's parameter of the same name and default
 * (Glance Wear `1.0.0-alpha18`+): `true` captures against `RendererVersion.SAFE_FALLBACK_VERSION`,
 * the oldest widget host with Remote Compose support, so the preview shows what every host can
 * draw; `false` captures against `RendererVersion.MAX_RENDERER_VERSION`, the latest host's
 * operation set. Either way the version is written into [params] *before* this wrapper's own
 * capture, exactly as upstream does before its capture — so the `.rc` sidecar, the CMP raster and
 * the upstream fallback all describe the same document. Upstream defaults to `true` while its Java
 * player lacks `CORE_TEXT` for widgets (b/553471238); this default follows theirs so switching a
 * preview between this wrapper and `WearWidgetPreview` never changes what it draws.
 */
@Composable
fun CapturingWearWidgetPreview(
  params: WearWidgetParams,
  background: WearWidgetBrush = WearWidgetBrush,
  useSafeFallbackRendererVersion: Boolean = true,
  content: @Composable @RemoteComposable () -> Unit,
) =
  CapturingWearWidgetPreviewOnLane(
    params = params,
    background = background,
    useSafeFallbackRendererVersion = useSafeFallbackRendererVersion,
    player = wearWidgetPreviewPlayer,
    content = content,
  )

/**
 * [CapturingWearWidgetPreview] with the replay lane stated rather than read from the process-wide
 * [wearWidgetPreviewPlayer], so a test can drive both lanes in one JVM — in particular
 * [WearWidgetPreviewPlayer.VIEW], the one lane that calls upstream `WearWidgetPreview` and so the
 * one a Glance Wear binary signature change breaks (issue #5420).
 */
@Composable
internal fun CapturingWearWidgetPreviewOnLane(
  params: WearWidgetParams,
  background: WearWidgetBrush,
  useSafeFallbackRendererVersion: Boolean,
  player: WearWidgetPreviewPlayer,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current
  val versionedParams =
    remember(params, useSafeFallbackRendererVersion) {
      params.withRendererVersion(
        if (useSafeFallbackRendererVersion) RendererVersion.SAFE_FALLBACK_VERSION
        else RendererVersion.MAX_RENDERER_VERSION
      )
    }
  // Capture once per (params, background, content) — mirrors the memoised `runBlocking` capture in
  // the connector's `RemoteOverridablePreview`. `captureRawContent` builds the same document
  // `WearWidgetPreview` will, so the sidecar matches the raster.
  val captured =
    remember(versionedParams, background, content) {
      // Best-effort: an IR capture must never fail the raster. But it must not fail *silently*
      // either — a swallowed `NoSuchMethodError` from a coroutines version skew is precisely how
      // this capture once degraded to "renders fine, emits no `.rc`" with a green build. Catch
      // `Throwable` (linkage errors are not `Exception`s) and say so on stderr, so a missing
      // sidecar is diagnosable from the render log instead of being invisible.
      try {
        val raw = runBlocking {
          WearWidgetDocument(background, content)
            .captureRawContent(context, versionedParams, /* isInspectionMode= */ true)
        }
        IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, raw.rcDocument)
        raw.rcDocument
      } catch (t: Throwable) {
        System.err.println(
          "CapturingWearWidgetPreview: failed to capture the widget's RemoteCompose document; " +
            "this render will emit no .rc sidecar. Cause: $t"
        )
        null
      }
    }

  // The captured bytes are the widget, so the CMP lane replays them directly. Both fallbacks route
  // to upstream, which recaptures the document itself: nothing here can be drawn from a capture
  // that failed, and the embedded player has to actually be on the classpath to be called.
  if (
    captured != null && player == WearWidgetPreviewPlayer.CMP && embeddedWearWidgetPlayerAvailable
  ) {
    CmpWearWidgetPlayer(bytes = captured, params = params)
  } else {
    WearWidgetPreview(
      params = params,
      background = background,
      useSafeFallbackRendererVersion = useSafeFallbackRendererVersion,
      content = content,
    )
  }
}

/**
 * [this] with [rendererVersion] swapped in — upstream's private `WearWidgetParams.copy`, restated
 * because `WearWidgetParams` is not a data class.
 */
internal fun WearWidgetParams.withRendererVersion(
  rendererVersion: RendererVersion
): WearWidgetParams =
  WearWidgetParams(
    instanceId = instanceId,
    containerType = containerType,
    widthDp = widthDp,
    heightDp = heightDp,
    horizontalPaddingDp = horizontalPaddingDp,
    verticalPaddingDp = verticalPaddingDp,
    cornerRadiusDp = cornerRadiusDp,
    rendererVersion = rendererVersion,
  )

/**
 * Plays [bytes] with the embedded Compose player at the widget's container size.
 *
 * The size is upstream's, spelled the same way: `WearWidgetPreview` lays its player out at
 * `(widthDp + 2 * horizontalPaddingDp) x (heightDp + 2 * verticalPaddingDp)`, the widget footprint
 * plus the container padding the host draws around it. It has to be stated rather than derived —
 * `RcPlayer` renders the document into the constraints it is given rather than measuring itself
 * from the document header — and stating it here is what keeps a widget's render the same size on
 * either lane, so switching players moves pixels within the frame and never the frame itself.
 */
@Composable
private fun CmpWearWidgetPlayer(bytes: ByteArray, params: WearWidgetParams) {
  val document =
    remember(bytes) {
      // Before the constructor, not after: `RemoteDocument(bytes)` parses inside it, and a
      // `BitmapData` carrying an encoded reference throws from `inflateFromBuffer` while the
      // remote-core globals are off — failing the *whole* document, not just the image. A widget
      // that draws artwork (the shape issue #5259 quotes) is exactly that document.
      enableEncodedImageReferences()
      RemoteDocument(bytes)
    }
  ExperimentalRemoteDocumentPlayer(
    document = document,
    modifier =
      Modifier.width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
        .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
  )
}
