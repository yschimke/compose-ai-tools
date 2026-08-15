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

package androidx.compose.remote.player.compose.embedded.modifier

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.layout.modifiers.ClipRectModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RoundedClipRectModifierOperation
import androidx.compose.remote.player.compose.embedded.LocalCoreDocument
import androidx.compose.remote.player.compose.embedded.readDataReflection
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

@Composable
internal fun Modifier.clipRect(op: ClipRectModifierOperation): Modifier {
  return this.clip(RectangleShape)
}

@Composable
internal fun Modifier.roundedClipRect(op: RoundedClipRectModifierOperation): Modifier {
  val data = op.readDataReflection()
  val behavior = LocalCoreDocument.current.densityBehavior
  val shape =
    RemoteRoundedClipShape(
      topStart = rememberRemoteFloatAsState(data.x1Value),
      topEnd = rememberRemoteFloatAsState(data.y1Value),
      bottomEnd = rememberRemoteFloatAsState(data.y2Value),
      bottomStart = rememberRemoteFloatAsState(data.x2Value),
      densityBehavior = behavior,
    )

  // remote-core applies the rounded clip to the component's complete paint output. DrawContent
  // precedes this operation in the wire modifier list, but appending Compose's clip would leave
  // that draw node outside the clip. Prepend it so generated background paths are clipped too.
  return Modifier.clip(shape).then(this)
}

internal data class RemoteRoundedClipShape(
  val topStart: State<Float>,
  val topEnd: State<Float>,
  val bottomEnd: State<Float>,
  val bottomStart: State<Float>,
  val densityBehavior: Int,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val minDimension = size.minDimension
    val fallback = minDimension / 2f
    fun radius(corner: State<Float>) =
      corner.value.resolveRadius(fallback, minDimension, densityBehavior, density.density)
    val topStartRadius = radius(topStart)
    val topEndRadius = radius(topEnd)
    val bottomEndRadius = radius(bottomEnd)
    val bottomStartRadius = radius(bottomStart)
    val radiusScale =
      roundedRectRadiusScale(
        size,
        topStartRadius,
        topEndRadius,
        bottomEndRadius,
        bottomStartRadius,
      )

    return Outline.Rounded(
      RoundRect(
        rect = Rect(0f, 0f, size.width, size.height),
        topLeft = CornerRadius(topStartRadius * radiusScale),
        topRight = CornerRadius(topEndRadius * radiusScale),
        bottomRight = CornerRadius(bottomEndRadius * radiusScale),
        bottomLeft = CornerRadius(bottomStartRadius * radiusScale),
      )
    )
  }
}

/** Matches the radius normalization performed by Android's Path.addRoundRect in remote-core. */
private fun roundedRectRadiusScale(
  size: Size,
  topStart: Float,
  topEnd: Float,
  bottomEnd: Float,
  bottomStart: Float,
): Float {
  fun scaleFor(limit: Float, first: Float, second: Float): Float {
    val sum = first + second
    return if (sum > limit && sum != 0f) limit / sum else 1f
  }

  return min(
    min(scaleFor(size.width, topStart, topEnd), scaleFor(size.width, bottomStart, bottomEnd)),
    min(scaleFor(size.height, topStart, bottomStart), scaleFor(size.height, topEnd, bottomEnd)),
  )
}

internal fun Float.resolveRadius(
  fallback: Float,
  minDimension: Float,
  densityBehavior: Int,
  density: Float,
): Float {
  if (!isFinite()) return fallback

  // Percent corners can briefly arrive as 0..1 fractions before the component-size expression
  // settles. RoundRect normalizes an oversized result, so this remains safe under DP scaling.
  val resolved = if (this > 0f && this <= 1f) this * minDimension else this

  // Match remote-core's RoundedClipRectModifierOperation.paint exactly: updateVariables first
  // resolves literals and NaN-backed variables into mX1..mY2, then DP behavior scales every
  // resolved corner. Legacy and pixel behavior both pass the resolved value through unchanged.
  return if (densityBehavior == CoreDocument.DENSITY_BEHAVIOR_DP) resolved * density else resolved
}
