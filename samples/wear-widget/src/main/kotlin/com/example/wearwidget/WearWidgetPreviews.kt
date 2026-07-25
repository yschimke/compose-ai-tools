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

/**
 * Glance Wear widget previews — the exact shape issue #2670 is about: a device-less `@Preview` on a
 * widget composable driven by an `androidx.glance.wear.tooling.preview` `@PreviewParameter` provider
 * (`WearWidgetParams`). Because the provider is under `androidx.glance.wear.*`, discovery's
 * auto-detect recognises these as widgets and crops each render to its intrinsic bounds at wear
 * density — no `retargetWearPreviews` config needed, never the 227dp watch-face canvas.
 *
 * Each preview goes through [CapturingWearWidgetPreview], so alongside the cropped PNG the render
 * also emits the widget's encoded RemoteCompose document as a `<stem>.rc` sidecar — the widget
 * travels in the bundle as data, not bytecode.
 */
// Fans out over every squircle footprint the platform ships (`SquircleAllWidgetPreviewParams`).
@Preview(name = "Image Widget Squircle")
@Composable
fun ImageWidgetSquirclePreview(
  @PreviewParameter(SquircleAllWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params) { RemoteImageWidget() }
}

// A single fixed footprint (`SquircleLargeWidgetPreviewParams`) to show the crop tracks the params.
@Preview(name = "Image Widget Squircle Large")
@Composable
fun ImageWidgetSquircleLargePreview(
  @PreviewParameter(SquircleLargeWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params) { RemoteImageWidget() }
}

// The squircle host spec (240dp screen), spelled out literally — same values as the upstream
// `SquircleSmallWidgetPreviewParams`. Kept as a plain (non-`@PreviewParameter`) preview so it emits a
// single capture whose render stem matches its `.rc` sidecar exactly, which is what lets the bundle
// pack the encoded document under `ir/`. (A `@PreviewParameter` fan-out renders one `.rc` per value
// under a `_PARAM_N` stem, which the base-stem bundle IR lookup doesn't resolve — so the param
// previews above still capture their doc as a render sidecar, but the packaged-in-bundle proof rides
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
  CapturingWearWidgetPreview(params = fixedWidgetParams) { RemoteImageWidget() }
}
