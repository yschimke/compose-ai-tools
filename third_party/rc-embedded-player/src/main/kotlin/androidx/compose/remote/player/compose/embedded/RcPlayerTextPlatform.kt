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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontStyle

/*
 * Every place the canvas text path reaches for `android.graphics`, gathered behind four functions so
 * the rest of the draw path doesn't have to name the platform.
 *
 * This is a *seam*, not a port. The bodies below are the same framework calls the ops made inline
 * before, moved here unchanged — `Paint.getTextBounds`, `Paint.measureText`, `Canvas.drawText`,
 * `Canvas.drawTextOnPath`. Android keeps drawing text exactly as it did; what changes is that
 * `RcPlayerDrawing.kt` and `RcPlayerPaint.kt` no longer mention `android.graphics`, so a jvm sibling
 * of *this file alone* is what the draw path needs to run off Android.
 *
 * That sibling replicates these four over skiko (`org.jetbrains.skia.Font` for both measurements,
 * `Canvas.drawString` for the origin draw, and manual `PathMeasure` glyph placement for
 * text-on-path) and is judged against Android's output. Choosing skiko over Compose's own
 * multiplatform text APIs is the point: `DrawTextAnchored` anchors against *ink* bounds and reads
 * `left`/`top` directly (mirroring `DrawTextAnchored.getHorizontalOffset`/`getVerticalOffset` in
 * remote-core), while Compose exposes layout bounds — side bearings and line spacing included — so
 * substituting them would shift every anchored string. See the CMP section of PROVENANCE.md.
 */

/**
 * Tight ink bounds of a string — the box the glyphs actually mark, relative to the text origin
 * (baseline at y=0, pen start at x=0). Left/top are frequently negative.
 *
 * A neutral carrier for `android.graphics.Rect`, so the anchoring arithmetic that consumes it (in
 * `RcPlayerDrawing.kt`) names no platform type. Deliberately not `androidx.compose.ui.geometry.Rect`:
 * that would invite reading it as a layout box, which is a different measurement.
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
 * Moved here from `ComposeLocalPaint` unchanged, so the paint-state class itself names no Android
 * type. Note it resolves *named* families through [EmbeddedPlayerTypefaceResolver] (`device:` /
 * `google:` prefixes included) — richer than the generics-only mapping `toTextStyle` uses for
 * `DrawText`, and the reason measurement and drawing must both go through this one function.
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

/** Advance width of [text] with the platform's text engine. */
internal fun measureTextWidth(
    text: String,
    paintState: ComposeLocalPaint,
    context: RemoteContext,
): Float = paintState.toNativeTextPaint(context).measureText(text)

/**
 * Draws [text] with its origin at ([x], [y]) — pen start and baseline, the same convention
 * [measureTextInkBounds] measures against.
 */
internal fun DrawScope.drawTextAtOriginPlatform(
    text: String,
    x: Float,
    y: Float,
    paintState: ComposeLocalPaint,
    context: RemoteContext,
) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paintState.toNativeTextPaint(context))
}

/**
 * Lays [text] along [path]. Compose has no multiplatform equivalent — neither `DrawScope` nor
 * `TextMeasurer` can place glyphs along a path.
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
