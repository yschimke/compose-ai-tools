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

package ee.schimke.composeai.rcembedded.player.modifier

import androidx.compose.remote.core.operations.layout.modifiers.ClipRectModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RoundedClipRectModifierOperation
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
import ee.schimke.composeai.rcembedded.player.readDataReflection
import ee.schimke.composeai.rcembedded.player.state.rememberRemoteFloatAsState
import kotlin.math.min

@Composable
internal fun Modifier.clipRect(op: ClipRectModifierOperation): Modifier {
  return this.clip(RectangleShape)
}

/**
 * @param hoistPastDrawContent whether a `DrawContentOperation` has already been folded into the
 *   chain. Only then is the clip moved to the front — see the note at the return.
 */
@Composable
internal fun Modifier.roundedClipRect(
  op: RoundedClipRectModifierOperation,
  hoistPastDrawContent: Boolean = false,
): Modifier {
  val data = op.readDataReflection()
  val shape =
    RemoteRoundedClipShape(
      topStart = rememberRemoteFloatAsState(data.x1Value),
      topEnd = rememberRemoteFloatAsState(data.y1Value),
      bottomEnd = rememberRemoteFloatAsState(data.y2Value),
      bottomStart = rememberRemoteFloatAsState(data.x2Value),
    )

  // remote-core applies the rounded clip to the component's complete paint output, so the clip has
  // to sit *outside* the draw — but *inside* the layout modifiers, at the position the wire list
  // gives it.
  //
  // This used to prepend unconditionally, which put the clip ahead of `PaddingModifierOperation`
  // too. On a switch thumb — `padding(35.4dp, 7.9dp).size(16.dp)` then this clip then a background
  // — that clipped the padded 51x24dp box while the background painted the 16x16dp content well
  // inside it, so the rounded shape never touched the thing it was meant to round and the thumb
  // rendered square (compose-ai-tools#3992). The track beside it carries no padding, which is
  // exactly why it looked correct and the thumb did not.
  //
  // A `Modifier.clip` clips whatever the modifiers *after* it draw, so list order already puts the
  // draw inside: an explicit `DrawContentOperation` later in the list, or the implicit draw
  // `toModifier` appends when a component carries no marker. The one case list order cannot serve
  // is a `DrawContentOperation` that comes *before* this operation — there the clip still has to be
  // hoisted past it, and [hoistPastDrawContent] says so. No document in the 164-document catalog
  // sweep exercises that branch; it is kept because the wire format permits it, not because
  // anything observed needs it.
  return if (hoistPastDrawContent) Modifier.clip(shape).then(this) else this.clip(shape)
}

internal data class RemoteRoundedClipShape(
  val topStart: State<Float>,
  val topEnd: State<Float>,
  val bottomEnd: State<Float>,
  val bottomStart: State<Float>,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val minDimension = size.minDimension
    val fallback = minDimension / 2f
    fun radius(corner: State<Float>) = corner.value.resolveRadius(fallback, minDimension)
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

/**
 * Resolves one corner of a `RoundedClipRectModifierOperation` to a **pixel** radius.
 *
 * The corner arrives already scaled. remote-core's `updateVariables` folds the display density into
 * `mX1..mY2` before the player ever reads them, so a 26dp corner is `26` at density 1.0 and `52` at
 * density 2.0 — measured, at both densities, off a real document. There is no density behavior
 * branch here for the same reason there is none in the pixel behaviors: the value is in pixels
 * whatever the document declares.
 *
 * This used to multiply by density under [CoreDocument.DENSITY_BEHAVIOR_DP], on the belief that
 * remote-core scaled DP-mode corners only at paint time. It does not, and the extra multiply
 * **doubled every rounded clip** at density 2.0 — a 26dp card corner clipped as 52dp.
 *
 * It hid in plain sight because [roundedRectRadiusScale] rescues the common case: on a stadium or a
 * circle the doubled radius exceeds half the box and gets clamped straight back to the shape it
 * should have been, which is why every button on the Wear catalog looked right. Only a shape whose
 * corner is genuinely smaller than half its box keeps the doubling — and there it eats the corners
 * off whatever the component draws inside the clip. `RemoteOutlinedCard` drew its border as two
 * hairlines for exactly this reason
 * ([wear-m3-catalog#89](https://github.com/yschimke/wear-m3-catalog/issues/89)); the path handed to
 * `drawPath` measured a complete 1168px rounded-rect contour, and the clip removed the rest of it.
 *
 * At density 1.0 the multiply was a no-op, so nothing ever caught it.
 */
internal fun Float.resolveRadius(fallback: Float, minDimension: Float): Float {
  if (!isFinite()) return fallback

  // Percent corners can briefly arrive as 0..1 fractions before the component-size expression
  // settles. RoundRect normalizes an oversized result, so this remains safe.
  return if (this > 0f && this <= 1f) this * minDimension else this
}
