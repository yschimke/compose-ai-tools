package com.example.sampleandroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * `@Preview` with neither `showBackground` nor `backgroundColor` set, so the renderer falls through
 * to `Color.Transparent` in `RobolectricRenderTest.resolveBackgroundColor`. A 60dp circle-shape
 * Button is centred in a 100×100dp frame, leaving the four corners outside the button — those
 * pixels must round-trip as alpha=0 in the captured PNG. Pair-asserted by
 * [TransparentBackgroundPreviewPixelTest].
 */
@Preview(name = "Circle Button Transparent", widthDp = 100, heightDp = 100)
@Composable
fun CircleButtonTransparentPreview() {
  MaterialTheme {
    Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
      Button(onClick = {}, shape = CircleShape, modifier = Modifier.size(60.dp)) { Text("") }
    }
  }
}
