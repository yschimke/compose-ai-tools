package com.example.samplecmp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Default-args `@AnimatedPreview` — the auto-detect sentinel (`durationMs = 0`) exercised
 * end-to-end on the CMP/desktop backend.
 *
 * This fixture exists as the regression guard for issue #2190: the desktop renderer used to treat
 * `durationMs = 0` as "no animation" and PNG-encode a single frame into the `.gif` renderOutput.
 * With the fix, a default-args annotation dispatches to the animated path and the desktop backend
 * captures its auto-detect fallback window (1500ms — this harness can't measure the animation, see
 * `AUTO_DURATION_FALLBACK_MS` in `DesktopAnimatedRenderer`). The dot bounces once per second, so a
 * correct capture shows visible motion across ~1.5 periods; a regressed capture is a frozen frame.
 *
 * Everything on the annotation is deliberately left at its default (`durationMs = 0`,
 * `frameIntervalMs = 33`, `showCurves = true` — which the desktop backend degrades to a
 * screenshot-only GIF with a logged note).
 */
@Preview(name = "Animated — Auto-Detect Duration", widthDp = 220, heightDp = 80)
@AnimatedPreview
@Composable
fun AutoDetectDurationAnimatedPreview() {
  val transition = rememberInfiniteTransition(label = "auto-detect-slider")
  val fraction by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "fraction",
    )
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF102030)).padding(16.dp)) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(6.dp)
          .align(Alignment.CenterStart)
          .background(Color(0xFF2A4A6A), RoundedCornerShape(3.dp))
    )
    Box(
      modifier =
        Modifier.align(Alignment.CenterStart)
          // Track is 220dp minus 32dp padding; the 24dp dot sweeps the remaining 164dp.
          .offset(x = 164.dp * fraction)
          .size(24.dp)
          .background(Color(0xFF64D2FF), CircleShape)
    )
  }
}
