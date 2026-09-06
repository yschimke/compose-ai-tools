@file:Suppress("RestrictedApiAndroidX")

package com.example.wearwidget

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams
import ee.schimke.composeai.wear.preview.CapturingWearWidgetPreview

/**
 * Glance Wear widget previews — the exact shape issue #2670 is about: a device-less `@Preview` on a
 * widget composable driven by an `androidx.glance.wear.tooling.preview` `@PreviewParameter`
 * provider (`WearWidgetParams`). Because the provider is under `androidx.glance.wear.*`,
 * discovery's auto-detect recognises these as widgets and crops each render to its intrinsic bounds
 * at wear density — no `retargetWearPreviews` config needed, never the 227dp watch-face canvas.
 *
 * Each preview goes through [CapturingWearWidgetPreview], so alongside the cropped PNG the render
 * also emits the widget's encoded RemoteCompose document as a `<stem>.rc` sidecar — the widget
 * travels in the bundle as data, not bytecode.
 *
 * The widget's fill is passed as the container `background` ([RemoteImageWidgetBackground]), never
 * painted inside [RemoteImageWidget] — that is what keeps the render a single corner-clipped
 * squircle instead of a rounded frame with a square-cornered rectangle sitting inside it.
 */
// Fans out over every squircle footprint the platform ships (`SquircleAllWidgetPreviewParams`).
@Preview(name = "Image Widget Squircle")
@Composable
fun ImageWidgetSquirclePreview(
  @PreviewParameter(SquircleAllWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params, background = RemoteImageWidgetBackground) {
    RemoteImageWidget()
  }
}

// A single fixed footprint (`SquircleLargeWidgetPreviewParams`) to show the crop tracks the params.
@Preview(name = "Image Widget Squircle Large")
@Composable
fun ImageWidgetSquircleLargePreview(
  @PreviewParameter(SquircleLargeWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params, background = RemoteImageWidgetBackground) {
    RemoteImageWidget()
  }
}

// The upstream shape, and the one the UI builder generates: a widget preview that also declares
// `device = "spec:width=1000dp,height=1000dp,dpi=320"`. `wear-os-samples`' `WearWidget` sample
// writes its previews this way, so any widget pasted out of that sample — or out of the builder's
// Code pane, which reproduces it — arrives with that device attached.
//
// It is a Studio scratch canvas, not the widget's canvas: the widget's footprint is in its
// `WearWidgetParams`, which the provider fans out. Discovery therefore drops the device for a
// widget preview rather than honouring it (`PreviewDiscovery.retargetWearStickers`), and this
// preview is the guard on that — honoured, it renders the widget's background across 1000x1000dp
// instead of cropping to the 216x124dp frame, which is what it did until the retarget learned
// to drop it.
@Preview(name = "Image Widget Device Spec", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
fun ImageWidgetDeviceSpecPreview(
  @PreviewParameter(SquircleLargeWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params, background = RemoteImageWidgetBackground) {
    RemoteImageWidget()
  }
}

// The squircle host spec (240dp screen), spelled out literally — same values as the upstream
// `SquircleSmallWidgetPreviewParams`. Kept as a plain (non-`@PreviewParameter`) preview so it emits
// a
// single capture whose render stem matches its `.rc` sidecar exactly, which is what lets the bundle
// pack the encoded document under `ir/`. (A `@PreviewParameter` fan-out renders one `.rc` per value
// under a `_PARAM_N` stem, which the base-stem bundle IR lookup doesn't resolve — so the param
// previews above still capture their doc as a render sidecar, but the packaged-in-bundle proof
// rides
// on this fixed one.)
private val fixedWidgetParams =
  WearWidgetParams(
    instanceId = WidgetInstanceId("tiles", 1),
    containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
    widthDp = 200f,
    heightDp = 60f,
    horizontalPaddingDp = 8f,
    verticalPaddingDp = 8f,
    cornerRadiusDp = 26f,
  )

@Preview(name = "Image Widget Fixed", showBackground = false, widthDp = 216, heightDp = 76)
@Composable
fun ImageWidgetFixedPreview() {
  CapturingWearWidgetPreview(
    params = fixedWidgetParams,
    background = RemoteImageWidgetBackground,
  ) {
    RemoteImageWidget()
  }
}
