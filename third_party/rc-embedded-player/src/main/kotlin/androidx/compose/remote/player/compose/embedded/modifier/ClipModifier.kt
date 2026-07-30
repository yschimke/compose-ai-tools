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

@Composable
internal fun Modifier.clipRect(op: ClipRectModifierOperation): Modifier {
    return this.clip(RectangleShape)
}

@Composable
internal fun Modifier.roundedClipRect(op: RoundedClipRectModifierOperation): Modifier {
    val data = op.readDataReflection()
    // A corner is either a literal (a dp value the shape was authored with — a card's fixed corner,
    // `RemoteRoundedCornerShape(4.dp)`) or a size-relative *variable* (a NaN-encoded expression over
    // the component's measured size — `RemoteCircleShape`'s 50%). The raw bits (`data.x1` … `data.x2`)
    // are NaN for a variable and finite for a literal — the same signal
    // `RoundedClipRectModifierOperation.read` keeps upstream by reading ints, not floats.
    val behavior = LocalCoreDocument.current.densityBehavior
    return this.clip(
        RemoteRoundedClipShape(
            topStart = ClipCorner(rememberRemoteFloatAsState(data.x1Value), !data.x1.isNaN()),
            topEnd = ClipCorner(rememberRemoteFloatAsState(data.y1Value), !data.y1.isNaN()),
            bottomEnd = ClipCorner(rememberRemoteFloatAsState(data.y2Value), !data.y2.isNaN()),
            bottomStart = ClipCorner(rememberRemoteFloatAsState(data.x2Value), !data.x2.isNaN()),
            densityBehavior = behavior,
        )
    )
}

/** One resolved corner radius plus whether it was authored as a dp literal (vs a size variable). */
internal data class ClipCorner(val value: State<Float>, val literal: Boolean)

internal data class RemoteRoundedClipShape(
    val topStart: ClipCorner,
    val topEnd: ClipCorner,
    val bottomEnd: ClipCorner,
    val bottomStart: ClipCorner,
    val densityBehavior: Int,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val minDimension = size.minDimension
        val fallback = minDimension / 2f
        fun radius(corner: ClipCorner) =
            corner.resolveRadius(fallback, minDimension, densityBehavior, density.density)

        return Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size.width, size.height),
                topLeft = CornerRadius(radius(topStart)),
                topRight = CornerRadius(radius(topEnd)),
                bottomRight = CornerRadius(radius(bottomEnd)),
                bottomLeft = CornerRadius(radius(bottomStart)),
            )
        )
    }
}

internal fun ClipCorner.resolveRadius(
    fallback: Float,
    minDimension: Float,
    densityBehavior: Int,
    density: Float,
): Float {
    val v = value.value
    return when {
        !v.isFinite() -> fallback
        // A literal corner is authored in dp; remote-core's `RoundedClipRectModifierOperation.paint`
        // scales it by the document density under DP behavior (and treats it as raw pixels
        // otherwise). Replicate that so a literal-cornered button/card matches the baked render and
        // the TypeScript player instead of rendering density× under-rounded.
        literal -> if (densityBehavior == CoreDocument.DENSITY_BEHAVIOR_DP) v * density else v
        // A size-relative *variable* is computed from the component's measured size, which the engine
        // already carries in pixels, so it is used as-is (scaling would double-apply the density).
        // Percent corners can briefly arrive as 0..1 fractions before that expression settles.
        v > 0f && v <= 1f -> v * minDimension
        else -> v
    }
}
