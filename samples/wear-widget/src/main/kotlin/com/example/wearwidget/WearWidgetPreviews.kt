package com.example.wearwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A stand-in for a Wear OS widget/tile "sticker": a fixed-size composable exported as a drawable
 * asset (Widget Picker / Play Store catalog). Its intrinsic layout is 192×60 dp — nothing about it
 * wants the 227dp square watch-face canvas, so its preview must crop to those bounds.
 *
 * See issue #2670: on a Wear module the discovery retarget normally pins device-less previews onto
 * the watch canvas; `:samples:wear-widget` sets `retargetWearPreviews = false` so this preview stays
 * wrap-content and the renderer crops it, at wear density.
 */
@Composable
fun ImageWidget(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.size(width = 192.dp, height = 60.dp).background(Color(0xFF1E88E5)),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(text = "Widget", style = TextStyle(color = Color.White))
  }
}

// A frame-less, device-less `@Preview` — exactly the shape #2670 is about. With the module's
// `retargetWearPreviews = false`, the render crops to the widget's 192×60 dp bounds (at 2.0x wear
// density → 384×120 px) instead of a 454×454 px watch-face canvas.
@Preview(name = "Image Widget", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ImageWidgetPreview() {
  ImageWidget()
}

// A second fixed size to show the crop tracks the composable's own bounds, not a single constant.
@Composable
fun BadgeWidget(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.size(96.dp).background(Color(0xFF43A047)),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(text = "OK", style = TextStyle(color = Color.White))
  }
}

@Preview(name = "Badge Widget", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun BadgeWidgetPreview() {
  BadgeWidget()
}
