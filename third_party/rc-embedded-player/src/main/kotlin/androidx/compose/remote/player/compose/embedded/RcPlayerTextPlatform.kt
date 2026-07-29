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

import android.graphics.Paint
import androidx.compose.remote.core.RemoteContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.toArgb

/*
 * The two text operations that cannot be expressed with Compose's multiplatform text APIs, gathered
 * in one place so the rest of the draw path doesn't have to name `android.graphics`.
 *
 * Splitting these out is what removes `android.graphics.Paint` from `RcPlayerPaint.kt` and
 * `android.graphics.Rect` from `RcPlayerDrawing.kt`: those files now describe *what* to draw, and
 * only this one knows how the platform measures and lays out glyphs. See the CMP section of
 * PROVENANCE.md.
 *
 * When the draw path grows a desktop counterpart, this file is the seam: a jvm sibling supplies the
 * same two functions over Skia (`org.jetbrains.skia.Font.measureText` and manual glyph placement
 * along a path), and nothing else in the draw path changes.
 */

/**
 * Tight ink bounds of [text] — the box the glyphs actually mark, relative to the text origin
 * (baseline at y=0, pen start at x=0). Left/top are frequently negative.
 *
 * This is *not* the layout box. `DrawTextAnchored`'s positioning is defined in terms of ink bounds
 * (it mirrors `DrawTextAnchored.getHorizontalOffset`/`getVerticalOffset` in remote-core, which
 * measures the same way), and it reads `left` and `top` directly. Substituting Compose's layout
 * bounds — which include side bearings and line spacing — would silently shift every anchored
 * string rather than port it, so the measurement stays platform-specific while the anchoring
 * arithmetic that consumes it does not.
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
 * Builds a framework [Paint] for the text ops from the current paint state: anti-aliased, the
 * effective colour, the text size, and a typeface derived from font weight/style.
 *
 * Moved here from `ComposeLocalPaint` so the paint-state class itself names no Android type.
 */
private fun ComposeLocalPaint.toNativeTextPaint(context: RemoteContext): Paint {
    val resolver = EmbeddedPlayerTypefaceResolver(context)
    val italic = fontStyle == FontStyle.Italic

    val fontInstance =
        if (isTypefaceSet) {
            if (fontFamily in 0..3) {
                resolver.resolve(fontFamily, fontWeight, italic, null, 400, false)
            } else {
                val name = context.getText(fontFamily)
                if (name != null) {
                    resolver.resolve(name, fontWeight, italic, null, 400, false)
                } else {
                    resolver.resolve(0, fontWeight, italic, null, 400, false)
                }
            }
        } else {
            resolver.resolve(0, fontWeight, italic, null, 400, false)
        }

    return Paint().apply {
        isAntiAlias = true
        color = effectiveColor().toArgb()
        textSize = this@toNativeTextPaint.textSize
        typeface = fontInstance.getTypeface()
    }
}

/** Measures [text]'s ink bounds with the platform's text engine. */
internal fun measureTextInkBounds(
    text: String,
    paintState: ComposeLocalPaint,
    context: RemoteContext,
): TextInkBounds {
    val bounds = android.graphics.Rect()
    paintState.toNativeTextPaint(context).getTextBounds(text, 0, text.length, bounds)
    return TextInkBounds(
        bounds.left.toFloat(),
        bounds.top.toFloat(),
        bounds.right.toFloat(),
        bounds.bottom.toFloat(),
    )
}

/** Advance width of [text] with the platform's text engine (Skia's `Font.measureText` on jvm). */
internal fun measureTextWidth(
    text: String,
    paintState: ComposeLocalPaint,
    context: RemoteContext,
): Float = paintState.toNativeTextPaint(context).measureText(text)

/**
 * Lays [text] along [path]. Compose has no multiplatform equivalent — neither `DrawScope` nor
 * `TextMeasurer` can place glyphs along a path — so this drops to the framework canvas. On a Skia
 * backend the counterpart is manual glyph placement via `PathMeasure`.
 */
internal fun DrawScope.drawTextOnPathPlatform(
    text: String,
    path: Path,
    hOffset: Float,
    vOffset: Float,
    paintState: ComposeLocalPaint,
    context: RemoteContext,
) {
    drawContext.canvas.nativeCanvas.drawTextOnPath(
        text,
        path.asAndroidPath(),
        hOffset,
        vOffset,
        paintState.toNativeTextPaint(context),
    )
}
