/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("RestrictedApiAndroidX")

package androidx.compose.remote.player.compose.embedded

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.remote.core.MatrixAccess
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.ShaderData
import androidx.compose.remote.core.operations.Utils
import androidx.compose.ui.graphics.Shader

/*
 * The one platform seam of the embedded player's paint path (see issue #2954 and PROVENANCE.md's
 * "CMP android/jvm" sequencing, step 5). Everything else in RcPlayerPaint.kt — brushes, tile modes,
 * blend modes, colour filters, images — is expressed in multiplatform Compose graphics and shared
 * verbatim between the Android and jvm/desktop targets. Runtime shaders cannot be: they use a
 * shading language and a uniform-binding API that differ per platform, so they live behind a narrow
 * pair of functions rather than a parallel copy of the whole paint decoder.
 *
 * The *signatures* are portable — `androidx.compose.ui.graphics.Shader` is the multiplatform type,
 * even though it is a typealias for `android.graphics.Shader` here — so a jvm/desktop file supplying
 * the same two functions over skiko's SkSL `RuntimeEffect` is a drop-in replacement for this one,
 * with no change to the shared caller. This file holds the Android AGSL implementation; the desktop
 * counterpart is deliberately deferred (issue #2954), pending a runtime-shader preview to baseline
 * it against — the catalog's `ShaderGradientSticker`, despite the name, is a plain gradient fill
 * (`PaintBundle.GRADIENT`) that never reaches this seam, not an AGSL `RuntimeShader`.
 */

/**
 * Builds the AGSL [android.graphics.RuntimeShader] for a PaintBundle `SHADER` op (from a
 * [ShaderData], with its float/int/bitmap uniforms applied), mirroring the View player's
 * `AndroidPaintContext.setShader`. Returns null — for id 0, a missing [ShaderData] or shader text,
 * or below API 33 (RuntimeShader is API 33+); the caller then falls back to the solid color. The
 * caller wraps it as a Compose [androidx.compose.ui.graphics.Brush] (and keeps it for
 * SHADER_MATRIX).
 *
 * Returns the multiplatform [Shader] (a typealias for `android.graphics.Shader` on Android) so the
 * seam's signature is the same one a jvm/desktop implementation would expose.
 */
internal fun buildRuntimeShader(shaderId: Int, remoteContext: RemoteContext): Shader? {
  if (shaderId == 0) return null
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
  val data = remoteContext.mRemoteComposeState.getFromId(shaderId) as? ShaderData ?: return null
  val text = remoteContext.getText(data.shaderTextId) ?: return null
  // A shader that fails to compile or bind its uniforms (e.g. malformed AGSL, or a runtime that
  // doesn't fully support RuntimeShader such as a host without GPU shader compilation) must not
  // crash the whole document draw — fall back to no shader so the rest of the frame still
  // renders.
  return try {
    val shader = RuntimeShader(text)
    for (name in data.uniformFloatNames) {
      shader.setFloatUniform(name, data.getUniformFloats(name))
    }
    for (name in data.uniformIntegerNames) {
      shader.setIntUniform(name, data.getUniformInts(name))
    }
    for (name in data.uniformBitmapNames) {
      val bitmap = resolveBitmap(remoteContext, data.getUniformBitmapId(name))
      if (bitmap != null) {
        shader.setInputShader(
          name,
          BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
          ),
        )
      }
    }
    shader
  } catch (e: RuntimeException) {
    null
  }
}

/**
 * Applies a PaintBundle `SHADER_MATRIX` op: sets a local matrix on the current shader. [matrixWord]
 * is the NaN-encoded id (as raw bits) of a [MatrixAccess] object; id 0 clears the local matrix.
 * Mirrors the View player's `AndroidPaintContext.setShaderMatrix`.
 */
internal fun applyShaderMatrix(
  paintState: ComposeLocalPaint,
  matrixWord: Int,
  read: RemoteContext,
) {
  val shader = paintState.nativeShader ?: return
  val id = Utils.idFromNan(Float.fromBits(matrixWord))
  if (id == 0) {
    shader.setLocalMatrix(null)
    return
  }
  val matrix = read.getObject(id) as? MatrixAccess ?: return
  val values = matrix.get()
  // MatrixAccess.to3x3: a 4x4 (16) collapses to the 3x3 (9) android Matrix layout; a 9 is as-is.
  val m3x3 =
    when (values.size) {
      9 -> values
      16 ->
        floatArrayOf(
          values[0],
          values[1],
          values[3],
          values[4],
          values[5],
          values[7],
          values[8],
          values[9],
          values[15],
        )
      else -> return
    }
  shader.setLocalMatrix(Matrix().apply { setValues(m3x3) })
}
