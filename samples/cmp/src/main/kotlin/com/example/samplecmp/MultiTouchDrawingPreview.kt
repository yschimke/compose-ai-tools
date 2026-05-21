package com.example.samplecmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview

/**
 * Multi-touch drawing canvas: every active pointer leaves a coloured dot trail. Colour is picked
 * from a 4-entry palette keyed by `(pointerId mod 4)`, so three or four simultaneous fingers
 * produce visibly distinct trails — the smoke test for "did real multi-pointer dispatch reach the
 * composition?".
 *
 * Pairs with the `LiveTouchOverlay` data-extension: enabling `overrides.touchOverlay = true` on the
 * recording / interactive session paints the visualization rings on top of these trails. The
 * captured frames then carry both the *agent's* coloured trails (what the composition saw) and the
 * *overlay's* cyan rings (what touch coords the daemon dispatched) — when those line up you have
 * proof the gesture pipeline is wired end-to-end.
 *
 * Implementation note: a single `SnapshotStateList<TrailPoint>` records every pressed-state change.
 * Mutating the list triggers recomposition; the `Canvas` redraws every point. We don't try to
 * decimate or path-stroke — the dot density is set by Compose's natural input polling so the trail
 * naturally thickens with slow fingers and thins with fast ones, which reads well in the GIF
 * artifact.
 */
@Preview(name = "Multi-Touch Drawing", widthDp = 240, heightDp = 240)
@Composable
fun MultiTouchDrawingPreview() {
  val points = remember { mutableStateListOf<TrailPoint>() }
  Canvas(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFF101010)).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
              if (change.pressed) {
                points.add(TrailPoint(change.id.value, change.position))
              }
              // Consume so nested gesture detectors (if any are ever added) don't double-handle the
              // raw events. The drawing canvas owns multi-touch input top-to-bottom.
              change.consume()
            }
          }
        }
      }
  ) {
    points.forEach { pt ->
      val color = STROKE_PALETTE[(pt.pointerId.toInt() % STROKE_PALETTE.size).coerceAtLeast(0)]
      drawCircle(color = color, radius = 4f, center = pt.position)
    }
  }
}

private data class TrailPoint(val pointerId: Long, val position: Offset)

private val STROKE_PALETTE: List<Color> =
  listOf(
    Color(0xFFFFAB00), // amber — pointer 0
    Color(0xFFE91E63), // pink — pointer 1
    Color(0xFF00C853), // green — pointer 2
    Color(0xFF2979FF), // blue — pointer 3
  )
