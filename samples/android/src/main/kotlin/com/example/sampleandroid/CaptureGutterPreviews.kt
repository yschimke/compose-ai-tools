package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.CaptureGutter

/**
 * Android (Robolectric) half of the `@CaptureGutter` fixture (m3-catalog#179) — the same
 * shadow-casting sticker `:samples:cmp` renders, so the two lanes can be compared canvas for
 * canvas. Lane parity is the point of having both: a gutter that grew the desktop capture and not
 * the Android one would be a silent divergence in the published bounds.
 */
@Composable
private fun ElevatedSticker() {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shadowElevation = 6.dp,
  ) {
    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) { Text("Elevated") }
  }
}

/** The "before": cropped to the component's bounds, so the shadow is sliced off at every edge. */
@Preview(name = "Shadow cropped", showBackground = true)
@Composable
fun ShadowStickerCroppedPreview() {
  ElevatedSticker()
}

/** The "after": the same component, with the capture bounds extended by the shadow's own reach. */
@CaptureGutter(all = 4, bottom = 5)
@Preview(name = "Shadow guttered", showBackground = true)
@Composable
fun ShadowStickerGutteredPreview() {
  ElevatedSticker()
}

/**
 * A fill-width component on a 400dp frame, with and without a gutter. At 2.625 density the gutter's
 * rounded edges don't divide evenly, so a naive viewport loses a pixel and `fillMaxWidth` content
 * measures differently. The pixel test asserts equal band widths.
 */
@Preview(name = "Fill fixed", widthDp = 400, showBackground = true)
@Composable
fun FillWidthCroppedPreview() {
  Box(modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF1B5E20)))
}

/** The guttered twin of [FillWidthCroppedPreview]. */
@CaptureGutter(all = 4, bottom = 5)
@Preview(name = "Fill fixed guttered", widthDp = 400, showBackground = true)
@Composable
fun FillWidthGutteredPreview() {
  Box(modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF1B5E20)))
}
