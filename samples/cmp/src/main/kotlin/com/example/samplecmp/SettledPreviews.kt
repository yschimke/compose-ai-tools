package com.example.samplecmp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.SettledPreview
import kotlinx.coroutines.delay

/**
 * CMP-desktop half of the `@SettledPreview` demo (issue #4202) — the same reveal the Android sample
 * renders, so the two lanes can be compared frame for frame.
 *
 * Desktop is the lane where the fix needed the most machinery: the still path draws through
 * `ImageComposeScene`, whose default coroutine context resolves `delay` against wall time, so
 * raising the frame clock alone left the reveal exactly where it was.
 */
@Composable
fun DesktopRevealCard() {
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    delay(200)
    alpha.animateTo(1f, tween(durationMillis = 300, easing = LinearEasing))
  }
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF102027)), Alignment.Center) {
    Column(
      modifier = Modifier.alpha(alpha.value),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(modifier = Modifier.size(72.dp).background(Color(0xFF4CAF50), CircleShape))
      Text(text = "Sent", color = Color.White)
    }
  }
}

/** The "before": no settle, so the capture lands inside the reveal's delay. */
@Preview(name = "Reveal unsettled")
@Composable
fun DesktopRevealUnsettledPreview() {
  Box(Modifier.size(200.dp)) { DesktopRevealCard() }
}

/** The "after": advance until the reveal has quiesced, then capture. */
@SettledPreview
@Preview(name = "Reveal settled")
@Composable
fun DesktopRevealSettledPreview() {
  Box(Modifier.size(200.dp)) { DesktopRevealCard() }
}
