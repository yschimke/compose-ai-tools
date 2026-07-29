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

import androidx.compose.remote.core.RemoteContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.PathMeasure
import org.jetbrains.skia.RSXform
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Paint as SkiaPaint

/*
 * The jvm half of the canvas text seam — the four functions `RcPlayerTextPlatform.kt` declares for
 * Android, answered over skiko instead.
 *
 * This file has no Android counterpart to share, which is the whole point: `:…-player-jvm` compiles
 * the neutral player sources by path out of the Android module and adds *this* where the framework
 * text calls used to be. Nothing here is vendored — see PROVENANCE.md's CMP section.
 *
 * **The contract is the four signatures, not bit-identical output.** Both halves answer the same
 * questions about the same [TextPaintSpec]; they answer them through different font stacks, so metrics
 * differ and that is recorded as a parity limit rather than papered over. What the seam does buy is
 * that the difference is now *measurable* — one function per question, on both sides.
 *
 * Skia is reached directly rather than through Compose's multiplatform text APIs for the same reason
 * Android does not use them: `DrawTextAnchored` needs tight ink bounds, and Compose exposes layout
 * bounds. `org.jetbrains.skia.Font` is the same `SkFont` that Android's `Paint` drives underneath, so
 * these are the closest thing to the framework calls that exists off Android.
 */

/**
 * Family names tried, in order, for each core generic id — the first one the host actually has wins.
 *
 * Skia has no notion of a generic family, so unlike Android's `Typeface.SANS_SERIF` and friends there
 * is nothing to ask for directly. The CSS-style names come first because that is what fontconfig
 * resolves on Linux; the concrete names after them are for hosts where those aliases mean nothing
 * (macOS, Windows). Id 0 shares the sans list with id 1 for the same reason Android's
 * `Typeface.DEFAULT` and `Typeface.SANS_SERIF` are the same face in practice.
 */
private val GENERIC_FAMILY_CANDIDATES =
    mapOf(
        0 to listOf("sans-serif", "Helvetica", "Arial", "DejaVu Sans", "Liberation Sans"),
        1 to listOf("sans-serif", "Helvetica", "Arial", "DejaVu Sans", "Liberation Sans"),
        2 to listOf("serif", "Times New Roman", "Times", "DejaVu Serif", "Liberation Serif"),
        3 to listOf("monospace", "Courier New", "DejaVu Sans Mono", "Liberation Mono", "Menlo"),
    )

/**
 * Resolves [spec]'s family/weight/slant to a skiko [Typeface], mirroring what
 * `EmbeddedPlayerTypefaceResolver` does on Android.
 *
 * The two prefixes the Android resolver understands are handled the same way it does — strip and look
 * the remainder up as a family name. The difference is `google:`, which on Android triggers a
 * `FontsContractCompat` download: there is no JVM equivalent, so the name is tried locally and falls
 * back to the default face rather than being fetched. That is the "downloadable fonts" parity limit
 * in PROVENANCE.md, and it is a *substitution*, not an error — a document naming a Google font still
 * renders, in the default face.
 *
 * **Never resolves to null while the host has any font at all**, which matters more than it looks:
 * `Font(null, size)` maps every character to the missing glyph and measures zero, so a null typeface
 * would not be a fallback but a silently blank render. `matchFamilyStyle(null, …)` — the obvious way
 * to ask for "whatever the default is" — returns exactly that null on Linux, so the last resort is
 * the first family the manager enumerates instead.
 */
private fun resolveSkiaTypeface(spec: TextPaintSpec, context: RemoteContext): Typeface? {
    val style =
        FontStyle(
            spec.fontWeight,
            FontWidth.NORMAL,
            if (spec.italic) FontSlant.ITALIC else FontSlant.UPRIGHT,
        )
    val manager = FontMgr.default

    fun match(candidates: List<String>): Typeface? =
        candidates.firstNotNullOfOrNull { manager.matchFamilyStyle(it, style) }

    fun default(): Typeface? =
        match(GENERIC_FAMILY_CANDIDATES.getValue(0))
            ?: if (manager.familiesCount > 0) {
                manager.matchFamilyStyle(manager.getFamilyName(0), style)
            } else {
                null
            }

    // Deliberately the same branch shape as `toNativeTextPaint`: an unset typeface and an
    // unresolvable text id both fall to the generic default, not to some jvm-specific choice.
    if (!spec.isTypefaceSet) return default()
    if (spec.fontFamily in 0..3) {
        return match(GENERIC_FAMILY_CANDIDATES.getValue(spec.fontFamily)) ?: default()
    }
    val name = context.getText(spec.fontFamily) ?: return default()
    val bare = name.removePrefix("device:").removePrefix("google:")
    return match(listOf(bare)) ?: default()
}

/** The skiko [Font] the measurements and the draws all go through — one place, as on Android. */
private fun TextPaintSpec.toSkiaFont(context: RemoteContext): Font =
    Font(resolveSkiaTypeface(this, context), textSize)

/**
 * Counterpart of the framework `Paint` the Android half builds. Only colour and anti-aliasing carry
 * over: size and typeface live on the [Font] in Skia's API, not the paint.
 */
private fun TextPaintSpec.toSkiaPaint(): SkiaPaint =
    SkiaPaint().apply {
        isAntiAlias = true
        color = argbColor
    }

/**
 * Measures [text]'s ink bounds with the platform's text engine.
 *
 * `Font.measureText` is Skia's `SkFont::measureText`, which unions the positioned glyph bounds —
 * the same construction `android.graphics.Paint.getTextBounds` performs. Android rounds the result
 * out to whole pixels (its out-param is an integer `Rect`); this does not, so bounds here can be
 * fractionally tighter. That is a sub-pixel difference in the anchoring arithmetic, not a different
 * measurement.
 */
internal fun measureTextInkBounds(
    text: String,
    spec: TextPaintSpec,
    context: RemoteContext,
): TextInkBounds {
    val bounds = spec.toSkiaFont(context).measureText(text, spec.toSkiaPaint())
    return TextInkBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

/** Advance width of [text] with the platform's text engine. */
internal fun measureTextWidth(text: String, spec: TextPaintSpec, context: RemoteContext): Float =
    spec.toSkiaFont(context).measureTextWidth(text, spec.toSkiaPaint())

/**
 * Draws [text] with its origin at ([x], [y]) — pen start and baseline, the same convention
 * [measureTextInkBounds] measures against.
 *
 * `Canvas.drawString` takes exactly that origin, so this is the one of the four that is a direct
 * swap for its framework call.
 */
internal fun DrawScope.drawTextAtOriginPlatform(
    text: String,
    x: Float,
    y: Float,
    spec: TextPaintSpec,
    context: RemoteContext,
) {
    drawContext.canvas.nativeCanvas.drawString(
        text,
        x,
        y,
        spec.toSkiaFont(context),
        spec.toSkiaPaint(),
    )
}

/**
 * Lays [text] along [path].
 *
 * The one of the four with no single call behind it: `Canvas.drawTextOnPath` is Android-only. But it
 * is not hand-rolled either — Skia's own primitive for this is a `TextBlob` of `RSXform`s (rotate +
 * scale + translate, per glyph), which is what the framework builds internally too. So the glyph
 * *placement* is computed here and the drawing is still one Skia call.
 *
 * The framework behaviour being reproduced:
 * - glyphs are walked in order, each consuming its own advance along the path;
 * - a glyph is placed so its horizontal *centre* sits at the path position half an advance further
 *   on, rotated to the path's tangent there — so text bends with the curve instead of sliding along
 *   a chord;
 * - [hOffset] shifts the starting distance along the path, [vOffset] shifts perpendicular to it;
 * - a glyph whose centre falls past the end of the path continues onto the next contour of a
 *   multi-contour path, and is dropped once the contours run out.
 *
 * Advances come from [Font.getWidths] over the whole shaped run, so they carry the run's kerning
 * rather than being re-measured per glyph in isolation.
 */
internal fun DrawScope.drawTextOnPathPlatform(
    text: String,
    path: Path,
    hOffset: Float,
    vOffset: Float,
    spec: TextPaintSpec,
    context: RemoteContext,
) {
    if (text.isEmpty()) return
    val font = spec.toSkiaFont(context)
    val glyphs = font.getStringGlyphs(text)
    if (glyphs.isEmpty()) return
    val advances = font.getWidths(glyphs)

    val measure = PathMeasure(path.asSkiaPath(), false)
    var contourLength = measure.length
    // An empty path — or one whose contours are all degenerate — has nowhere to put a glyph, and
    // stepping contours would not find one.
    if (contourLength <= 0f) return

    val placedGlyphs = ShortArray(glyphs.size)
    val xforms = arrayOfNulls<RSXform>(glyphs.size)
    var placed = 0
    var distance = hOffset
    var index = 0
    while (index < glyphs.size) {
        val advance = advances[index]
        val centre = distance + advance / 2f
        if (centre > contourLength) {
            // Past the end of this contour: carry on at the start of the next. `nextContour`
            // returning false means the path is exhausted, so the rest of the string is dropped —
            // as the framework drops it.
            if (!measure.nextContour()) break
            contourLength = measure.length
            distance = 0f
            continue
        }
        val position = measure.getPosition(centre)
        val tangent = measure.getTangent(centre)
        // Skia declines to answer for a degenerate segment. Skip that glyph rather than guess a
        // position, and keep walking — one bad segment must not drop the whole string.
        if (position != null && tangent != null) {
            // RSXform maps glyph space to device as (x,y) -> (scos*x - ssin*y + tx, ssin*x + scos*y
            // + ty), so a unit tangent as (scos, ssin) rotates the glyph onto the path. The glyph's
            // own origin is then half an advance back along the tangent and `vOffset` along the
            // perpendicular, which in that basis is (-ssin, scos).
            val scos = tangent.x
            val ssin = tangent.y
            xforms[placed] =
                RSXform(
                    scos,
                    ssin,
                    position.x - scos * advance / 2f - ssin * vOffset,
                    position.y - ssin * advance / 2f + scos * vOffset,
                )
            placedGlyphs[placed] = glyphs[index]
            placed++
        }
        distance += advance
        index++
    }
    if (placed == 0) return

    val blob =
        TextBlob.makeFromRSXform(
            placedGlyphs.copyOf(placed),
            Array(placed) { xforms[it]!! },
            font,
        ) ?: return
    blob.use { drawContext.canvas.nativeCanvas.drawTextBlob(it, 0f, 0f, spec.toSkiaPaint()) }
}
