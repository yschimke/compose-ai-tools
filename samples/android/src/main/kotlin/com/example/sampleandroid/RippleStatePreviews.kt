package com.example.sampleandroid

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Regression fixture for `ShadowRippleDrawable`: a Material button captured in its
 * pressed state via `@FocusedPreview(pressed = true)`. Without the ripple shadow
 * this renders as a plain resting button — the platform `RippleDrawable`'s ripple
 * / pressed state-layer doesn't draw under Robolectric (no RenderThread). With the
 * shadow, the pressed state layer renders, so the diff bot keeps the pressed
 * visual covered on every change to the ripple shadow.
 */
@Preview(name = "Pressed", showBackground = true)
@FocusedPreview(pressed = true)
@Composable
fun PressedButtonPreview() {
  MaterialTheme {
    Surface {
      Button(onClick = {}) { Text("Pressed") }
    }
  }
}
