package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Pinch-to-zoom preview that doubles as the integration fixture for the `LiveTouchOverlay`
 * recording feature. A centred coloured square scales between `0.5x` and `3.0x` via
 * [Modifier.transformable]'s zoom callback; the recording-session test scripts a two-finger outward
 * pinch and asserts:
 * 1. The captured frames show the per-pointer overlay rings (the visualization landed).
 * 2. The square visibly grew across frames (the multi-pointer dispatch actually reached the
 *    `Modifier.transformable` zoom handler, not just the touch-pipeline noop).
 *
 * The square stays a single solid colour and a fixed centre so the "did it zoom?" check is a pure
 * pixel-coverage diff (how much of the frame is `0xFF1976D2` blue) rather than anything
 * shape-dependent — keeping the test resilient to small Compose layout-density changes.
 */
@Preview(name = "Pinch To Zoom", widthDp = 240, heightDp = 240)
@Composable
fun PinchToZoomPreview() {
  var scale by remember { mutableStateOf(1f) }
  val transformableState = rememberTransformableState { zoomChange, _, _ ->
    scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
  }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color(0xFFECEFF1))
        .clipToBounds()
        .transformable(state = transformableState),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier =
        Modifier.size(80.dp)
          .graphicsLayer(scaleX = scale, scaleY = scale)
          .background(Color(0xFF1976D2))
    )
  }
}

private const val MIN_SCALE: Float = 0.5f
private const val MAX_SCALE: Float = 3.0f
