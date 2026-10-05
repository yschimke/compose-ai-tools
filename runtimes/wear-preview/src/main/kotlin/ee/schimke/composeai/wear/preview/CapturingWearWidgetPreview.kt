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
import ee.schimke.composeai.rcembedded.player.RemoteImageSupport
import kotlinx.coroutines.runBlocking

/**
 * Renders a Glance Wear widget preview **and preserves its encoded RemoteCompose document**.
 *
 * Upstream [WearWidgetPreview] captures the widget's `RemoteDocument` but only rasters it, so the
 * widget would ride the bundle as bytecode. This wrapper captures the same document, hands the
 * bytes to [IrSidecarChannel] (drained into the `<stem>.rc` sidecar), then plays them. Outside a
 * daemon/test render the offer is a no-op and only the raster runs.
 *
 * ## Which player draws
 *
 * [WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED] by default (issue #5259); select the View-backed lane
 * with `-PcomposePreview.rcPlayer=androidx-view`. Either way the pixels come from the same captured
 * document, sized as upstream sizes it. Falls back to upstream [WearWidgetPreview] when the
 * embedded player is missing or the capture failed.
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
 * [useSafeFallbackRendererVersion] mirrors upstream's parameter and default: `true` captures
 * against `RendererVersion.SAFE_FALLBACK_VERSION` (what every host can draw), `false` against
 * `MAX_RENDERER_VERSION`. The version is written into [params] before capture, so the sidecar, the
 * raster and the upstream fallback all describe the same document.
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
 * [WearWidgetPreviewPlayer.ANDROIDX_VIEW], the one lane that calls upstream `WearWidgetPreview` and
 * so the one a Glance Wear binary signature change breaks (issue #5420).
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

  // The embedded lane replays the captured bytes; both fallbacks go to upstream, which captures
  // the document itself.
  if (
    captured != null &&
      player == WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED &&
      embeddedWearWidgetPlayerAvailable
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
 * Plays [bytes] with the embedded player at upstream's size: the widget footprint plus container
 * padding. Stated explicitly because `RcPlayer` fills its constraints rather than measuring the
 * document, and matching it keeps both lanes the same size.
 */
@Composable
private fun CmpWearWidgetPlayer(bytes: ByteArray, params: WearWidgetParams) {
  val document =
    remember(bytes) {
      // Before the constructor, which parses: an encoded `BitmapData` reference otherwise throws
      // and fails the whole document.
      RemoteImageSupport.enableEncodedImageReferences()
      RemoteDocument(bytes)
    }
  ExperimentalRemoteDocumentPlayer(
    document = document,
    modifier =
      Modifier.width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
        .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
  )
}
