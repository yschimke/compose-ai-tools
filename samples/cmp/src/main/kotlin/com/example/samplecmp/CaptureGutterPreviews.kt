package com.example.samplecmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
