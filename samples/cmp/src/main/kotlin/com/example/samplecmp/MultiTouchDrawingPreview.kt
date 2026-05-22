package com.example.samplecmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview

/**
 * Drawing canvas exercising three different gestures on the same surface so a single live session
 * proves all of them dispatch end-to-end:
 *
 * - **Tap** (down + up, no travel past `viewConfiguration.touchSlop`) — drops a pink circle at the
 *   tap position.
 * - **Drag** (single pointer that moves past slop) — accumulates positions into a path; on lift,
 *   the path is committed to [strokes] and rendered as a black polyline.
 * - **Pinch** (≥ 2 pressed pointers) — multiplies the canvas-wide scale by [calculateZoom] each
 *   frame, clamped to [[MIN_SCALE], [MAX_SCALE]]. The scale wraps every committed circle and stroke
 *   via `DrawScope.scale(..., pivot = centre)`.
 *
 * The tap-vs-drag-vs-pinch FSM lives in one `awaitEachGesture { }` block rather than three stacked
 * `pointerInput { detectXGestures { } }` modifiers. Stacking would race on `change.consume()` —
 * `detectDragGestures` waits past touch slop before consuming, but `detectTapGestures` consumes on
 * up, and either can shadow the transform detector once a pointer is in flight. A single explicit
 * pointer-count switch dodges that and keeps the decision tree visible in source.
 *
 * Pairs with the `LiveTouchOverlay` data extension: enabling `overrides.touchOverlay = true` on a
 * recording / interactive session paints cyan rings under the dispatched pointer coords, so a
 * single captured frame proves both "the daemon sent the right input" (overlay rings match expected
 * positions) and "the composition reacted appropriately" (a tap at that ring drops a circle; a drag
 * between two rings leaves a stroke; two-finger pinch shrinks/grows everything else).
 *
 * The recording-time fixture in `daemon/desktop/.../TouchOverlayDrawingRecordingTest.kt` is
 * deliberately a different (and simpler) shape — a 4-colour-per-pointer dot trail — because that
 * test exercises a narrower invariant (multi-pointer dispatch packing all pointers into a single
 * `sendPointerEvent` call). Treat the two as siblings rather than mirror images.
 */
@Preview(name = "Multi-Touch Drawing", widthDp = 240, heightDp = 240)
@Composable
fun MultiTouchDrawingPreview() {
  MultiTouchDrawingCanvas()
}

/**
 * Static counterpart with pre-seeded circles and a stroke so the rendered `@Preview` PNG isn't a
 * blank canvas. Same composable, same FSM — only the initial state differs.
 */
@Preview(name = "Multi-Touch Drawing — seeded", widthDp = 240, heightDp = 240)
@Composable
fun MultiTouchDrawingSeededPreview() {
  MultiTouchDrawingCanvas(
    initialCircles = listOf(Offset(60f, 70f), Offset(190f, 80f), Offset(120f, 200f)),
    initialStrokes =
      listOf(
        listOf(
          Offset(30f, 140f),
          Offset(55f, 120f),
          Offset(85f, 145f),
          Offset(115f, 110f),
          Offset(150f, 150f),
          Offset(185f, 115f),
          Offset(210f, 145f),
        )
      ),
  )
}

@Composable
private fun MultiTouchDrawingCanvas(
  initialCircles: List<Offset> = emptyList(),
  initialStrokes: List<List<Offset>> = emptyList(),
) {
  val circles = remember { mutableStateListOf<Offset>().apply { addAll(initialCircles) } }
  val strokes = remember { mutableStateListOf<List<Offset>>().apply { addAll(initialStrokes) } }
  var inProgress by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var scale by remember { mutableStateOf(1f) }

  Canvas(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startPos = down.position
          val pathPoints = mutableListOf(startPos)
          var mode = GestureMode.UNDECIDED
          down.consume()

          while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
              if (mode == GestureMode.DRAG) {
                // A second finger arrived mid-drag: abandon the in-flight stroke and re-purpose
                // the gesture as a pinch. Leaving the partial stroke committed would attribute the
                // first finger's motion to a drawing intent the user retracted by going multi.
                pathPoints.clear()
                inProgress = emptyList()
              }
              mode = GestureMode.PINCH
              val zoom = event.calculateZoom()
              if (zoom > 0f && zoom != 1f) {
                scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
              }
              event.changes.forEach { it.consume() }
            } else {
              val change = pressed.first()
              if (mode == GestureMode.PINCH) {
                // One pointer remaining after a pinch — don't drop into drag mode. The lift of the
                // first pointer would otherwise be interpreted as the start of a new drag from the
                // second pointer's position, which is never what the user meant.
                continue
              }
              val travel = (change.position - startPos).getDistance()
              if (mode == GestureMode.UNDECIDED && travel > slop) {
                mode = GestureMode.DRAG
              }
              if (mode == GestureMode.DRAG) {
                pathPoints.add(change.position)
                inProgress = pathPoints.toList()
                change.consume()
              }
            }
          }

          when (mode) {
            GestureMode.UNDECIDED -> circles.add(startPos)
            GestureMode.DRAG -> if (pathPoints.size > 1) strokes.add(pathPoints.toList())
            GestureMode.PINCH -> {} // scale already updated incrementally per frame
          }
          inProgress = emptyList()
        }
      }
  ) {
    val pivot = Offset(size.width / 2f, size.height / 2f)
    scale(scaleX = scale, scaleY = scale, pivot = pivot) {
      strokes.forEach { drawStroke(it) }
      if (inProgress.size > 1) drawStroke(inProgress)
      circles.forEach { drawCircle(color = CIRCLE_COLOR, radius = 12f, center = it) }
    }
  }
}

private fun DrawScope.drawStroke(points: List<Offset>) {
  val path =
    Path().apply {
      moveTo(points.first().x, points.first().y)
      for (i in 1 until points.size) {
        lineTo(points[i].x, points[i].y)
      }
    }
  drawPath(path = path, color = STROKE_COLOR, style = Stroke(width = STROKE_WIDTH_PX))
}

private enum class GestureMode {
  UNDECIDED,
  DRAG,
  PINCH,
}

private val CIRCLE_COLOR: Color = Color(0xFFE91E63)
private val STROKE_COLOR: Color = Color(0xFF1F1F1F)
private const val STROKE_WIDTH_PX: Float = 3f
private const val MIN_SCALE: Float = 0.5f
private const val MAX_SCALE: Float = 3f
