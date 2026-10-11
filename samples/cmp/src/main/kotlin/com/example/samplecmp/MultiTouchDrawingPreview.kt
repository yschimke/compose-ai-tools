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
 * Drawing canvas exercising three gestures on one surface, so a live session proves each
 * dispatches:
 * - Tap (no travel past `touchSlop`) drops a pink circle.
 * - Drag (one pointer past slop) draws a stroke, committed to [strokes] on lift.
 * - Pinch (≥ 2 pointers) scales everything by [calculateZoom], clamped to [[MIN_SCALE],
 *   [MAX_SCALE]].
 *
 * One `awaitEachGesture { }` switching on pointer count, rather than stacked `detectXGestures`
 * modifiers that race on `change.consume()`.
 *
 * With the `LiveTouchOverlay` extension (`overrides.touchOverlay = true`), cyan rings mark
 * dispatched pointers, so one frame shows both the input sent and the composition's reaction. The
 * desktop daemon's `TouchOverlayDrawingRecordingTest` uses a simpler fixture for a narrower
 * invariant.
 */
@Preview(name = "Multi-Touch Drawing", widthDp = 240, heightDp = 240)
@Composable
fun MultiTouchDrawingPreview() {
  MultiTouchDrawingCanvas()
}

/**
 * Static counterpart with pre-seeded circles and a stroke, so the PNG isn't a blank canvas.
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
