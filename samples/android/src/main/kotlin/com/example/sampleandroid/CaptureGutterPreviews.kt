package com.example.sampleandroid

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
