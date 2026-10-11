package com.example.samplecmp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.CaptureGutter

/**
 * `@CaptureGutter` fixture: a shadow-casting sticker cropped to its bounds and again with extended
 * capture bounds. The renderer applies the gutter outside the composable, so the 120×48 dp surface
 * is identical and only the canvas changes.
 */
@Composable
private fun ElevatedSticker() {
  Surface(
    modifier = Modifier.padding(0.dp),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shadowElevation = 6.dp,
  ) {
    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) { Text("Elevated") }
  }
}

/**
 * The "before": the render is cropped to the component's bounds, so the Level-1 shadow is sliced
 * off at all four edges of the image — most visibly along the bottom, where the shadow is offset.
 */
@Preview(name = "Shadow cropped", showBackground = true)
@Composable
fun ShadowStickerCroppedPreview() {
  ElevatedSticker()
}

/**
 * With the gutter: an 8 dp wider, 9 dp taller canvas around the same component.
 */
@CaptureGutter(all = 4, bottom = 5)
@Preview(name = "Shadow guttered", showBackground = true)
@Composable
fun ShadowStickerGutteredPreview() {
  ElevatedSticker()
}

/**
 * The motion counterpart: the same sticker and gutter as an `@AnimatedPreview` GIF, which must get
 * the same canvas as the still. The elevation pulses between Level 1 and 3, so at the deep end the
 * shadow reaches past the bare bounds.
 */
@CaptureGutter(all = 4, bottom = 5)
@AnimatedPreview(durationMs = 1200, frameIntervalMs = 50, showCurves = false)
@Preview(name = "Shadow guttered motion", showBackground = true)
@Composable
fun ShadowStickerGutteredAnimatedPreview() {
  val transition = rememberInfiniteTransition(label = "shadowPulse")
  val elevation by
    transition.animateFloat(
      initialValue = 2f,
      targetValue = 8f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 600),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "elevation",
    )
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shadowElevation = elevation.dp,
  ) {
    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) { Text("Elevated") }
  }
}
