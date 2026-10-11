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
 * Renders a Glance Wear widget preview and preserves its encoded RemoteCompose document.
 *
 * Upstream [WearWidgetPreview] only rasters the captured document; this hands the bytes to
 * [IrSidecarChannel] (the `<stem>.rc` sidecar), then plays them. Outside a render the offer is a
 * no-op.
 *
 * Player: [WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED] by default; `-PcomposePreview.rcPlayer=
 * androidx-view` selects the View-backed lane. Falls back to upstream [WearWidgetPreview] if the
 * embedded player is missing or capture failed.
 *
 * Pass the widget's fill as [background], not inside [content]: the host lays [content] out inside
 * the padded squircle container, so a full-bleed background there draws a square rectangle that
 * can't reach the rounded corners. (As upstream `wear-os-samples/WearWidget` does.)
 *
 * [useSafeFallbackRendererVersion] mirrors upstream: `true` captures against
 * `RendererVersion.SAFE_FALLBACK_VERSION`, `false` against `MAX_RENDERER_VERSION`; written into
 * [params] so sidecar, raster and fallback agree.
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
 * [CapturingWearWidgetPreview] with the replay lane passed explicitly, so a test can drive both
 * lanes in one JVM — notably [WearWidgetPreviewPlayer.ANDROIDX_VIEW], the lane a Glance Wear
 * signature change breaks.
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
      // Best-effort, but never silent: catch `Throwable` (linkage errors like a coroutines-skew
      // `NoSuchMethodError` aren't `Exception`s) and log, so a missing sidecar shows in the render
      // log.
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
