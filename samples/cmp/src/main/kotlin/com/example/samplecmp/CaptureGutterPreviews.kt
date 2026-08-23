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
 * `@CaptureGutter` fixture (m3-catalog#179) — a shadow-casting sticker rendered twice, once cropped
 * to its own bounds and once with the capture bounds extended, so the visual-diff bot has a
 * standing before/after for the feature.
 *
 * The component is identical in both. That is the point: the gutter is applied by the renderer,
 * outside the composable, so the elevated surface measures the same 120×48 dp either way and only
 * the canvas around it changes.
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
 * The "after": the same component, with the capture bounds extended by the shadow's own reach. The
 * canvas is 8 dp wider and 9 dp taller than the one above; the component inside it is the same
 * size, in the same place relative to its own box.
 */
@CaptureGutter(all = 4, bottom = 5)
@Preview(name = "Shadow guttered", showBackground = true)
@Composable
fun ShadowStickerGutteredPreview() {
  ElevatedSticker()
}

/**
 * The motion counterpart (compose-ai-tools#4452). Same sticker, same gutter, but the capture is an
 * `@AnimatedPreview` GIF rather than a still — the fixture that keeps the two products of one
 * component honest about its bounds. Before the motion paths carried the gutter, this GIF came out
 * 8×9 dp smaller than the still above it, with the shadow sliced off exactly where the still keeps
 * it; now both canvases are the component plus its declared gutter.
 *
 * The elevation is animated so the frames actually differ — a shadow that pulses between Level 1
 * and Level 3 also makes the gutter's job visible in motion: at the deep end the shadow reaches
 * further than the bare bounds, which is the pixel the gutter exists to keep.
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
