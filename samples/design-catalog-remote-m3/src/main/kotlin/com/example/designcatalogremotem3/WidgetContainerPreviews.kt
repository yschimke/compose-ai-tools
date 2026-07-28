@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.glance.wear.verticalGradient
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText
import ee.schimke.composeai.wear.preview.CapturingWearWidgetPreview

// ---------------------------------------------------------------------------
// Widget container — the squircle frame the Wear widget host draws AROUND widget
// content: a rounded-rect background (default `surfaceContainerLow` when the
// widget declares no background), host-defined padding, and the content inset
// inside it. On-device this frame comes from the host, not the widget, so a bare
// component sticker never shows it; these stickers recreate it through the
// AndroidX preview tooling wrapper — `androidx.glance.wear:wear-tooling-preview`'s
// `WearWidgetPreview`, whose capture path runs the same `WearWidgetContainer`
// composable (`androidx.glance.wear.composable`) the real widget pipeline uses,
// under the `WEAR_WIDGETS` platform profile.
//
// The `WearWidgetParams` below mirror the *squircle host spec* that upstream's
// `WidgetPreviewParams` providers (`SquircleSmallWidgetPreviewParams` /
// `SquircleLargeWidgetPreviewParams`) ship for a 240dp screen: Small 200×60dp,
// Large 200×108dp content, 8dp padding on each side, 26dp corner radius. They are
// spelled out literally (rather than read from the providers) so each sticker is a
// zero-arg deterministic capture — the renderer's discovery doesn't need
// `@PreviewParameter` support, and the values are the stable published spec, not
// an implementation detail likely to wander.
//
// Unlike the component stickers these do NOT go through `RemoteSticker` /
// `RemoteOverridablePreview` — the Glance Wear preview path owns its own capture
// (`WearWidgetDocument.captureRawContent` with `isInspectionMode = true`) and
// player raster, so there is no named-value override here.
//
// They DO emit the encoded document, though: each sticker renders through
// `:wear-preview-runtime`'s `CapturingWearWidgetPreview` rather than upstream's
// `WearWidgetPreview`. Upstream captures the `RemoteDocument` internally and keeps
// the bytes to itself (only the raster escapes), which left these stickers riding
// the portable bundle as compiled `@Preview` bytecode. The wrapper captures the
// same document and offers it to `IrSidecarChannel`, so the render lands a
// `<stem>.rc` next to the PNG and `BundlePreviewTask.resolvePreviewIr` packs it as
// the sticker's IR — the widget travels as data, like every other Remote Compose
// sticker in this sheet. Same wrapper `:samples:wear-widget` uses.
//
// No Wear M3 parallel: the container is a Glance Wear *host* frame, not a
// `remote-material3` component.
// ---------------------------------------------------------------------------

/**
 * Canvas for the Small-container stickers: 200×60dp content + 8dp padding on every edge → 216×76.
 * Same solid-background single-mode contract as [CatalogRemoteModes].
 */
@Preview(showBackground = false, widthDp = 216, heightDp = 76)
annotation class CatalogRemoteWidgetSmall

/** Canvas for the Large-container stickers: 200×108dp content + 8dp padding → 216×124. */
@Preview(showBackground = false, widthDp = 216, heightDp = 124)
annotation class CatalogRemoteWidgetLarge

// The squircle host spec (240dp screen) from upstream's `WidgetPreviewParams`.
// `WidgetInstanceId` uses the same "tiles" carousel namespace as upstream; the id is
// inert in a preview capture (it only matters to a live host round-trip).
private val smallWidgetParams =
  WearWidgetParams(
    instanceId = WidgetInstanceId("tiles", 1),
    containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
    widthDp = 200f,
    heightDp = 60f,
    horizontalPaddingDp = 8f,
    verticalPaddingDp = 8f,
    cornerRadiusDp = 26f,
  )

private val largeWidgetParams =
  WearWidgetParams(
    instanceId = WidgetInstanceId("tiles", 2),
    containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
    widthDp = 200f,
    heightDp = 108f,
    horizontalPaddingDp = 8f,
    verticalPaddingDp = 8f,
    cornerRadiusDp = 26f,
  )

// Fills the container's padded content slot and centres the given content in it.
// `WearWidgetContainer` lays content out top-start; a real widget supplies its own
// layout, so the stickers do too.
@Composable
private fun CenteredWidgetContent(content: @Composable @RemoteComposable () -> Unit) {
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize(),
    contentAlignment = RemoteAlignment.Center,
    content = content,
  )
}

/**
 * The Small widget container with the host's **default** background: passing the empty
 * [WearWidgetBrush] makes `WearWidgetContainer` fall back to its `surfaceContainerLow` fill — the
 * frame every widget that declares no background gets. `RemoteText`'s near-white default content
 * colour reads correctly on it.
 */
@CatalogRemoteWidgetSmall
@Composable
fun WidgetContainerSmallRemote() {
  CapturingWearWidgetPreview(params = smallWidgetParams, background = WearWidgetBrush) {
    CenteredWidgetContent { RemoteText("Next: Standup 10:30".rs) }
  }
}

/** The Large widget container (default background) carrying a title + supporting line. */
@CatalogRemoteWidgetLarge
@Composable
fun WidgetContainerLargeRemote() {
  CapturingWearWidgetPreview(params = largeWidgetParams, background = WearWidgetBrush) {
    CenteredWidgetContent {
      RemoteColumn {
        RemoteText("Morning run".rs, style = RemoteMaterialTheme.typography.bodyLarge)
        RemoteText("5.2 km · 28 min".rs, style = RemoteMaterialTheme.typography.labelSmall)
      }
    }
  }
}

/**
 * The Small container with an explicit [WearWidgetBrush] background — the widget-declared
 * counterpart to the default-surface stickers. A dark vertical gradient keeps the default
 * near-white content colour legible while making the brush (and the corner clipping over it)
 * obvious.
 */
@CatalogRemoteWidgetSmall
@Composable
fun WidgetContainerGradientRemote() {
  CapturingWearWidgetPreview(
    params = smallWidgetParams,
    background =
      WearWidgetBrush.verticalGradient(listOf(Color(0xFF101820).rc, Color(0xFF2C4A6E).rc)),
  ) {
    CenteredWidgetContent { RemoteText("Gradient".rs) }
  }
}
