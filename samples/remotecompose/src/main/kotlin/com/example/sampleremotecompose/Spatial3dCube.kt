@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.TouchExpression
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.core.operations.utilities.MatrixOperations
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.remotePath
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeJoin

// A rotatable, lit 3D cube defined wholly inside one Remote Compose document: the geometry, the
// rotation, the perspective projection, back-face culling, shading and drag-to-rotate input are
// all document operations, so any player that implements `MatrixExpression` + `MatrixVectorMath`
// draws it with no host code. Nothing paints a background, so it composites over whatever sits
// behind the player (an XR passthrough room, a panel, a wall).
//
// Per frame the player evaluates one 4x4 rotation (`MatrixExpression`, driven by two
// `TouchExpression`s), transforms 8 corners and 6 normals (`MatrixVectorMath`), and
// fills at most 3 visible quads. See docs/design/REMOTE_COMPOSE_XR.md.

/** Unit-cube corners, indexed by [CubeFaces]. */
private val CubeCorners =
  arrayOf(
    floatArrayOf(-1f, -1f, -1f),
    floatArrayOf(1f, -1f, -1f),
    floatArrayOf(1f, 1f, -1f),
    floatArrayOf(-1f, 1f, -1f),
    floatArrayOf(-1f, -1f, 1f),
    floatArrayOf(1f, -1f, 1f),
    floatArrayOf(1f, 1f, 1f),
    floatArrayOf(-1f, 1f, 1f),
  )

/** A cube face: corners counter-clockwise seen from outside, its outward normal, and its hue. */
private class CubeFace(val corners: IntArray, val normal: FloatArray, val hue: Float)

private val CubeFaces =
  listOf(
    CubeFace(intArrayOf(4, 5, 6, 7), floatArrayOf(0f, 0f, 1f), hue = 0.58f), // front
    CubeFace(intArrayOf(1, 0, 3, 2), floatArrayOf(0f, 0f, -1f), hue = 0.08f), // back
    CubeFace(intArrayOf(5, 1, 2, 6), floatArrayOf(1f, 0f, 0f), hue = 0.33f), // right
    CubeFace(intArrayOf(0, 4, 7, 3), floatArrayOf(-1f, 0f, 0f), hue = 0.83f), // left
    CubeFace(intArrayOf(7, 6, 2, 3), floatArrayOf(0f, 1f, 0f), hue = 0.14f), // top
    CubeFace(intArrayOf(0, 1, 5, 4), floatArrayOf(0f, -1f, 0f), hue = 0.95f), // bottom
  )

/** Light direction (normalised), from the upper left and towards the viewer. */
private val Light = floatArrayOf(-0.42f, 0.56f, 0.71f)

/** Distance from the eye to the cube centre, in cube half-widths. */
private const val CameraDistance = 5f

/**
 * A cube the user rotates by dragging: horizontal drags turn it (wrapping), vertical drags tilt it
 * (clamped), and both ease to a stop on release. [initialYaw] and [initialPitch] are the starting
 * orientation in degrees; [spinDegreesPerSecond] adds an idle turn.
 *
 * The spin reads `CONTINUOUS_SEC`, not `ANIMATION_TIME`: the AndroidX View player schedules its
 * next frame only for documents that listen to `CONTINUOUS_SEC` (or the second / minute fields), so
 * an `ANIMATION_TIME`-only document draws once and stops there
 * (`RemoteComposeState.getOpsToUpdate`, unchanged through remote-core alpha22).
 */
@Composable
@RemoteComposable
fun RemoteCube3d(
  modifier: RemoteModifier = RemoteModifier.fillMaxSize(),
  initialYaw: Float = 35f,
  initialPitch: Float = 25f,
  spinDegreesPerSecond: Float = 0f,
) {
  RemoteCanvas(modifier) { drawCube(initialYaw, initialPitch, spinDegreesPerSecond) }
}

private fun RemoteDrawScope.drawCube(
  initialYaw: Float,
  initialPitch: Float,
  spinDegreesPerSecond: Float,
) {
  val doc = document
  val easing = doc.easing(/* maxTime */ 2f, /* maxAcceleration */ 400f, /* maxVelocity */ 720f)

  // Drag input: 0.5 degrees per pixel, relative to the value at touch-down.
  val yawTouch =
    doc.addTouch(
      initialYaw,
      Float.NaN, // NaN min: wrap around [0, max)
      360f,
      TouchExpression.STOP_GENTLY,
      0f,
      0,
      null,
      easing,
      RemoteContext.FLOAT_TOUCH_POS_X,
      0.5f,
      AnimatedFloatExpression.MUL,
    )
  val pitchTouch =
    doc.addTouch(
      initialPitch,
      -85f,
      85f,
      TouchExpression.STOP_GENTLY,
      0f,
      0,
      null,
      easing,
      RemoteContext.FLOAT_TOUCH_POS_Y,
      0.5f,
      AnimatedFloatExpression.MUL,
    )
  val yaw =
    if (spinDegreesPerSecond == 0f) RemoteFloat(yawTouch)
    else RemoteFloat(yawTouch) + remote.time.ContinuousSec() * spinDegreesPerSecond

  // Model rotation: yaw about the cube's Y axis, then pitch about the view's X axis.
  val rotation =
    doc.matrixExpression(
      RemoteFloat(pitchTouch).floatId,
      MatrixOperations.ROT_X,
      yaw.floatId,
      MatrixOperations.ROT_Y,
    )

  // Rotate the corners and the face normals. Each `MatrixVectorMath` writes three new floats.
  val rotatedCorners = CubeCorners.map { corner -> doc.rotate(rotation, corner) }
  val rotatedNormals = CubeFaces.map { face -> doc.rotate(rotation, face.normal) }

  // Perspective: a corner at depth z lands at centre + (x, -y) * scale * d / (d - z).
  val scale = width.min(height) * 0.5f / 2.2f
  val projected = rotatedCorners.map { (x, y, z) ->
    val perspective = scale * CameraDistance / (-z + CameraDistance)
    RemoteOffset(center.x + x * perspective, center.y - y * perspective)
  }

  val edgePaint = RemotePaint {
    style = PaintingStyle.Stroke
    strokeWidth = 2f.rf
    strokeJoin = StrokeJoin.Round
    color = RemoteColor(Color(0xCC10141C))
  }

  CubeFaces.forEachIndexed { index, face ->
    val corners = face.corners.map { projected[it] }
    val (a, b, c) = corners
    // Screen-space winding of the projected quad: its sign says which side faces the eye.
    val facing = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    val (nx, ny, nz) = rotatedNormals[index]
    val lambert = max(nx * Light[0] + ny * Light[1] + nz * Light[2], 0f)
    val fill = RemotePaint {
      color = RemoteColor.hsv(face.hue.rf, 0.62f.rf, lambert * 0.6f + 0.38f)
    }
    val path = remotePath {
      moveTo(corners[0])
      corners.drop(1).forEach { lineTo(it) }
      close()
    }
    drawConditionally(facing.isLessThan(0f.rf)) {
      drawPath(path, fill)
      drawPath(path, edgePaint)
    }
  }
}

/** `rotation * vector` as three live floats, via `MatrixVectorMath` (type 0: affine multiply). */
private fun RemoteComposeWriter.rotate(rotation: Float, vector: FloatArray): List<RemoteFloat> {
  val out = FloatArray(3)
  addMatrixMultiply(rotation, vector, out)
  return out.map { RemoteFloat(it) }
}
