package com.example.sampleandroid

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
import ee.schimke.composeai.preview.MotionFormat

/**
 * A dot sweeping its track at constant speed — 164dp per 1000ms, then back — so the capture's
 * timing can be read straight off its frames: at the default 33ms interval each frame should move
 * the dot 164 × 33 / 1000 ≈ 5.4dp, and the 1000ms window should end with the dot at the far end. A
 * renderer that advances its clock by anything other than the requested interval shows up as a
 * different per-frame step and an end position past (or short of) the track's end.
 *
 * The same motion is captured twice: as the default GIF, and as APNG, which the Android renderer
 * honours from compose-preview-daemon 3.13.0 — before that the request was written as GIF to a
 * `.gif` output. Both stay in the preview workflow so a timing or container regression on the
 * Android lane is diffed.
 */
@Composable
private fun LinearSweep() {
  val transition = rememberInfiniteTransition(label = "linear-sweep")
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

@Preview(name = "Motion timing — linear sweep (GIF)", widthDp = 220, heightDp = 80)
@AnimatedPreview(durationMs = 1000, showCurves = false)
@Composable
fun LinearSweepGifAnimatedPreview() {
  LinearSweep()
}

@Preview(name = "Motion timing — linear sweep (APNG)", widthDp = 220, heightDp = 80)
@AnimatedPreview(durationMs = 1000, showCurves = false, format = MotionFormat.Apng)
@Composable
fun LinearSweepApngAnimatedPreview() {
  LinearSweep()
}
