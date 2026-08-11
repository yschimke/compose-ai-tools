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

package androidx.compose.remote.player.compose.embedded

/*
 * The vocabulary the canvas text seam speaks: what to draw text *with* ([TextPaintSpec]) and what
 * came back from measuring it ([TextInkBounds]). Both are plain values, so this file is shared by
 * the android and jvm halves rather than replicated — it is the reason `RcPlayerTextPlatform.kt`'s
 * four functions can be implemented twice without their signatures mentioning either platform.
 *
 * Kept apart from `RcPlayerPaint.kt` deliberately. `ComposeLocalPaint` carries the whole paint state
 * — brushes, colour filters, blend modes, a framework `Shader` — and stays Android-coupled until the
 * AGSL and image-decode seams land (issue #2954). The text ops need six fields out of it, so taking
 * a projection rather than the paint state itself is what lets the jvm sibling exist now instead of
 * after those seams.
 */

/**
 * Everything the platform text engines need in order to agree: size, family, weight, slant, colour.
 *
 * Exactly the fields `toNativeTextPaint` read off `ComposeLocalPaint` before this projection
 * existed, with the same meanings:
 * - [fontFamily] is a *core* family id, not a Compose one. `0..3` are the generics (default,
 *   sans-serif, serif, monospace); anything else is a text id to be resolved through the
 *   [androidx.compose.remote.core.RemoteContext] into a family *name*, which may carry a `device:`
 *   or `google:` prefix. Meaningful only when [isTypefaceSet]; the ops treat an unset typeface as
 *   generic `0`.
 * - [argbColor] already has the paint's alpha folded in (`ComposeLocalPaint.effectiveColor()`), so a
 *   platform sets it on its paint verbatim and applies no further alpha.
 */
internal class TextPaintSpec(
    val textSize: Float,
    val fontFamily: Int,
    val isTypefaceSet: Boolean,
    val fontWeight: Int,
    val italic: Boolean,
    val argbColor: Int,
)

/**
 * Tight ink bounds of a string — the box the glyphs actually mark, relative to the text origin
 * (baseline at y=0, pen start at x=0). Left/top are frequently negative.
 *
 * A neutral carrier for what `android.graphics.Paint.getTextBounds` fills in, so the anchoring
 * arithmetic that consumes it (in `RcPlayerDrawing.kt`) names no platform type. Deliberately not
 * `androidx.compose.ui.geometry.Rect`: that would invite reading it as a layout box, which is a
 * different measurement — see the seam comment in `RcPlayerTextPlatform.kt`.
 */
internal class TextInkBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

/**
 * All values AndroidX's `PaintContext.getTextBounds` can select for `TextMeasure`.
 *
 * [left], [top], [right], and [bottom] are the tight ink rectangle. [fontTop] and [fontBottom]
 * replace its vertical edges for `MEASURE_MAX_HEIGHT_FLAG`; [advance] replaces its horizontal
 * extent for the two advance-based flags. Keeping the raw measurements together lets the shared
 * operation interpreter apply AndroidX's flag order exactly on every platform.
 */
internal class TextMeasureBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontTop: Float,
    val fontBottom: Float,
    val advance: Float,
)
