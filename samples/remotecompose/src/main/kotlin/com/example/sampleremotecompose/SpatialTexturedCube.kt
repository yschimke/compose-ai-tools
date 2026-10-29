@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.animateRemoteFloatAsState
import androidx.compose.remote.creation.compose.state.atan2
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteFloat
import androidx.compose.remote.creation.compose.state.remotePath
import androidx.compose.remote.creation.compose.state.remoteTween
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.sin
import androidx.compose.remote.creation.compose.state.sqrt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

// A textured cube you interact with, defined wholly in one Remote Compose document. Tapping the
// cube eases it through a quarter turn; tapping the swatch swaps its texture. Both are document
// state changed by document actions (`valueChange`), so any player runs them with no host code.
//
// Two things differ from [RemoteCube3d] on purpose:
// - The rotation is plain `sin`/`cos` float expressions rather than `MatrixExpression` +
//   `MatrixVectorMath`, so it also turns on the embedded player in rc-players before 2.21.0, which
//   kept a matrix product at its first value. The embedded player is also the one whose semantics
//   expose the two tap targets.
// - Each face is textured by an affine map of a bitmap onto its projected corners: translate to one
//   corner, map the bitmap square onto the two edges leaving it, clip to the face. That is the
//   texture mapping a 2D canvas can do; perspective correction would need a mesh operation. The
//   map is applied as rotate · scale · rotate because the canvas has no skew, and a raw
//   `document.skew(...)` would bypass the recording canvas and land outside its save/restore.

private const val TextureSize = 128

/** Unit-cube corners, as in [RemoteCube3d]. */
private val Corners =
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

/** Faces: four corners counter-clockwise from outside (texture origin first), and the normal. */
private val Faces =
  listOf(
    intArrayOf(7, 6, 5, 4) to floatArrayOf(0f, 0f, 1f),
    intArrayOf(2, 3, 0, 1) to floatArrayOf(0f, 0f, -1f),
    intArrayOf(6, 2, 1, 5) to floatArrayOf(1f, 0f, 0f),
    intArrayOf(3, 7, 4, 0) to floatArrayOf(-1f, 0f, 0f),
    intArrayOf(3, 2, 6, 7) to floatArrayOf(0f, 1f, 0f),
    intArrayOf(4, 5, 1, 0) to floatArrayOf(0f, -1f, 0f),
  )

private val LightDir = floatArrayOf(-0.42f, 0.56f, 0.71f)

private const val Eye = 5f

/**
 * A cube with a bitmap texture on every face. Tap it to turn it a quarter turn; tap the swatch in
 * the corner to swap between the two textures.
 */
@Composable
@RemoteComposable
fun RemoteTexturedCube(
  modifier: RemoteModifier = RemoteModifier.fillMaxSize(),
  initialYaw: Float = 30f,
  pitch: Float = 24f,
) {
  val bricks = remember { RemoteImageBitmap(brickTexture().asImageBitmap()) }
  val tiles = remember { RemoteImageBitmap(tileTexture().asImageBitmap()) }
  val turns = rememberMutableRemoteFloat { 0f.rf }
  val textureIndex = rememberMutableRemoteFloat { 0f.rf }
  val yaw =
    animateRemoteFloatAsState(
      turns * 90f + initialYaw,
      remoteTween(durationMillis = 450),
    )
  RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.BottomEnd) {
    RemoteCanvas(
      RemoteModifier.fillMaxSize()
        .clickable(valueChange(turns, (turns + 1f).createReference()))
        .semantics { contentDescription = "Turn the cube".rs }
    ) {
      drawTexturedCube(yaw, pitch) { face ->
        drawConditionally(textureIndex.isLessThan(0.5f.rf)) { face(bricks) }
        drawConditionally(textureIndex.isGreaterThan(0.5f.rf)) { face(tiles) }
      }
    }
    RemoteBox(
      modifier =
        RemoteModifier.padding(12.rdp)
          .size(36.rdp)
          .clip(RemoteRoundedCornerShape(10.rdp))
          .background(RemoteColor(Color(0xFF2B3A55)))
          .clickable(valueChange(textureIndex, ((textureIndex + 1f) % 2f).createReference()))
          .semantics { contentDescription = "Swap the texture".rs }
    ) {}
  }
}

/**
 * Projects the cube and, for each face that faces the eye, clips to it, maps the texture onto it
 * through [textured], and darkens it by its Lambert term.
 */
private fun RemoteDrawScope.drawTexturedCube(
  yawDegrees: RemoteFloat,
  pitchDegrees: Float,
  textured: RemoteDrawScope.(face: RemoteDrawScope.(RemoteImageBitmap) -> Unit) -> Unit,
) {
  val yaw = yawDegrees * (Math.PI.toFloat() / 180f)
  val cy = cos(yaw)
  val sy = sin(yaw)
  val pitch = Math.toRadians(pitchDegrees.toDouble())
  val cp = kotlin.math.cos(pitch).toFloat()
  val sp = kotlin.math.sin(pitch).toFloat()

  // Rotate a constant vector: yaw about Y, then pitch about X. Only yaw is live.
  fun rotate(v: FloatArray): Triple<RemoteFloat, RemoteFloat, RemoteFloat> {
    val x1 = cy * v[0] + sy * v[2]
    val z1 = sy * -v[0] + cy * v[2]
    return Triple(x1, z1 * -sp + v[1] * cp, z1 * cp + v[1] * sp)
  }

  val scale = width.min(height) * 0.5f / 2.2f
  val projected = Corners.map { corner ->
    val (x, y, z) = rotate(corner)
    val perspective = scale * Eye / (-z + Eye)
    RemoteOffset(center.x + x * perspective, center.y - y * perspective)
  }

  val texturePaint = RemotePaint { color = RemoteColor(Color.White) }
  val edge = RemotePaint {
    style = androidx.compose.ui.graphics.PaintingStyle.Stroke
    strokeWidth = 2f.rf
    color = RemoteColor(Color(0xCC10141C))
  }

  for ((corners, normal) in Faces) {
    val (a, b, c) = corners.map { projected[it] }
    val d = projected[corners[3]]
    val facing = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    val (nx, ny, nz) = rotate(normal)
    val lambert = max(nx * LightDir[0] + ny * LightDir[1] + nz * LightDir[2], 0f)
    val path = remotePath {
      moveTo(a)
      lineTo(b)
      lineTo(c)
      lineTo(d)
      close()
    }
    drawConditionally(facing.isGreaterThan(0f.rf)) {
      clipPath(path) {
        // Texture space (0..S, 0..S) onto the parallelogram a + s·u + t·v, u = b - a, v = d - a:
        // the 2×2 map M = [u v] / S, applied as rotate · scale · rotate (its closed-form SVD),
        // because the canvas has rotate and scale but no skew.
        val m00 = (b.x - a.x) / TextureSize.toFloat()
        val m10 = (b.y - a.y) / TextureSize.toFloat()
        val m01 = (d.x - a.x) / TextureSize.toFloat()
        val m11 = (d.y - a.y) / TextureSize.toFloat()
        val e = (m00 + m11) * 0.5f
        val f = (m00 - m11) * 0.5f
        val g = (m10 + m01) * 0.5f
        val h = (m10 - m01) * 0.5f
        val q = sqrt(e * e + h * h)
        val r = sqrt(f * f + g * g)
        val a1 = atan2(g, f)
        val a2 = atan2(h, e)
        val toDegrees = 180f / Math.PI.toFloat()
        val canvas = remoteCanvas
        canvas.save()
        canvas.translate(a.x, a.y)
        canvas.rotate((a2 + a1) * 0.5f * toDegrees)
        canvas.scale(q + r, q - r)
        canvas.rotate((a2 - a1) * 0.5f * toDegrees)
        // Perspective puts the far corner c beyond the parallelogram, so lay a 2×2 block of the
        // (seamless) texture and let the face clip trim it. Bitmaps draw with the current paint,
        // and the previous face's lighting overlay left a translucent one behind: draw the texture
        // with an opaque paint, or every face after the first comes out see-through (players that
        // apply paint alpha to bitmaps, as the AndroidX View player and rc-players 2.21.0 do).
        usePaint(texturePaint) {
          textured { bitmap ->
            for (tile in 0 until 4) {
              drawScaledBitmap(
                bitmap,
                dstOffset =
                  RemoteOffset(
                    (tile % 2 * TextureSize).toFloat().rf,
                    (tile / 2 * TextureSize).toFloat().rf,
                  ),
                dstSize = RemoteSize(TextureSize.toFloat().rf, TextureSize.toFloat().rf),
                scaleType = ContentScale.FillBounds,
              )
            }
          }
        }
        canvas.restore()
        // Light it: darken by how far the face turns from the light.
        drawPath(
          path,
          RemotePaint { color = RemoteColor.rgb(0f.rf, 0f.rf, 0f.rf, (-lambert + 1f) * 0.55f) },
        )
      }
      drawPath(path, edge)
    }
  }
}

/** A warm brick texture: offset courses of rounded bricks over mortar. */
private fun brickTexture(): Bitmap {
  val bitmap = Bitmap.createBitmap(TextureSize, TextureSize, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  canvas.drawColor(0xFFD8CBB8.toInt())
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  val rows = 4
  val brickH = TextureSize / rows.toFloat()
  for (row in 0 until rows) {
    val offset = if (row % 2 == 0) 0f else TextureSize / 4f
    for (i in -1..2) {
      val left = i * TextureSize / 2f + offset + 2f
      paint.color = if ((row + i) % 3 == 0) 0xFFB5523B.toInt() else 0xFFC4673F.toInt()
      canvas.drawRoundRect(
        left,
        row * brickH + 2f,
        left + TextureSize / 2f - 4f,
        (row + 1) * brickH - 2f,
        4f,
        4f,
        paint,
      )
    }
  }
  return bitmap
}

/** A cool tile texture: a 4×4 checker of teal and ivory with a circle on each teal tile. */
private fun tileTexture(): Bitmap {
  val bitmap = Bitmap.createBitmap(TextureSize, TextureSize, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  val cell = TextureSize / 4f
  for (y in 0 until 4) {
    for (x in 0 until 4) {
      val teal = (x + y) % 2 == 0
      paint.color = if (teal) 0xFF1F7A7A.toInt() else 0xFFF3EEDF.toInt()
      canvas.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, paint)
      if (teal) {
        paint.color = 0xFFF2B33D.toInt()
        canvas.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, cell * 0.28f, paint)
      }
    }
  }
  return bitmap
}
