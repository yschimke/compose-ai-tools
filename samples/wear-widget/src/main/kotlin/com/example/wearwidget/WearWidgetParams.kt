package com.example.wearwidget

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * Local stand-in for `androidx.glance.wear.core.WearWidgetParams` (issue #2670's triggering type).
 * A Wear widget preview fans out over the shape's supported footprints — this carries the [name]
 * (used for the rendered PNG's filename suffix) and the widget's intrinsic size in dp. The renderer
 * crops each variant to exactly `widthDp`×`heightDp` at wear density (see the module's
 * `retargetWearPreviews = false`).
 */
data class WearWidgetParams(val name: String, val widthDp: Int, val heightDp: Int)

/**
 * Mirror of the wear-os-samples `SquircleAllWidgetPreviewParams`: the squircle widget's supported
 * (square) footprints. Representative sizes — the point is the fan-out + the ideal squircle shape,
 * not exact platform constants.
 */
class SquircleAllWidgetPreviewParams : PreviewParameterProvider<WearWidgetParams> {
  override val values: Sequence<WearWidgetParams> =
    sequenceOf(
      WearWidgetParams(name = "Squircle Small", widthDp = 192, heightDp = 192),
      WearWidgetParams(name = "Squircle Large", widthDp = 228, heightDp = 228),
    )
}

/**
 * Mirror of the wear-os-samples `RectangularAllWidgetPreviewParams`: the rectangular widget's wide
 * footprints, framed as a rounded rectangle by [RectangularWidgetWrapper].
 */
class RectangularAllWidgetPreviewParams : PreviewParameterProvider<WearWidgetParams> {
  override val values: Sequence<WearWidgetParams> =
    sequenceOf(
      WearWidgetParams(name = "Rectangular Small", widthDp = 228, heightDp = 102),
      WearWidgetParams(name = "Rectangular Large", widthDp = 228, heightDp = 150),
    )
}
