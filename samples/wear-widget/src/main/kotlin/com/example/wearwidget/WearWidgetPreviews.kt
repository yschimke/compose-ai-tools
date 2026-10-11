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
 * Glance Wear widget previews: device-less `@Preview`s driven by `androidx.glance.wear.tooling.preview`
 * `@PreviewParameter` providers, which discovery recognises as widgets and crops to their intrinsic
 * bounds at wear density with no config.
 *
 * Each goes through [CapturingWearWidgetPreview], so the render also emits the encoded document as a
 * `<stem>.rc` sidecar. The fill is passed as the container `background`
 * ([RemoteImageWidgetBackground]), not painted inside [RemoteImageWidget], so the render is one
 * corner-clipped squircle.
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

// The upstream (and UI-builder) shape: a widget preview declaring
// `device = "spec:width=1000dp,height=1000dp,dpi=320"`, a Studio scratch canvas. The widget's real
// footprint comes from `WearWidgetParams`, so discovery drops the device for widget previews
// (`PreviewDiscovery.retargetWearStickers`); this guards that it crops to the 216x124dp frame.
@Preview(name = "Image Widget Device Spec", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
fun ImageWidgetDeviceSpecPreview(
  @PreviewParameter(SquircleLargeWidgetPreviewParams::class) params: WearWidgetParams
) {
  CapturingWearWidgetPreview(params = params, background = RemoteImageWidgetBackground) {
    RemoteImageWidget()
  }
}

// The squircle host spec (240dp screen), as upstream's `SquircleSmallWidgetPreviewParams`. A plain
// preview, so its render stem matches its `.rc` sidecar and the bundle packs the document under
// `ir/` (`@PreviewParameter` fan-outs use `_PARAM_N` stems the bundle IR lookup doesn't resolve).
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
