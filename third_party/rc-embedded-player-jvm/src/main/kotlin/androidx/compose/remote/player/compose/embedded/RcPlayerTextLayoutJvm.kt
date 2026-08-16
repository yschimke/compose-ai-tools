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

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.TextLayout
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteStringAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import ee.schimke.composeai.rcembedded.jvm.GoogleFontTypefaceResolver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The jvm counterpart of `RcPlayerTextLayout.kt`'s two `RcPlayerText` composables. The size, weight,
 * slant, colour, alignment, overflow, decoration, letter-spacing and line-height handling are all
 * verbatim — they use only multiplatform Compose text APIs and desktop `material3.Text`. The known
 * Skiko still paints Compose's start/middle overflow values as end ellipsis, so this seam resolves
 * those two one-line modes synchronously before handing the line to `Text`. Font family resolution
 * also differs: Android resolves `google:` families through GMS downloadable fonts and `device:` /
 * variation-axis families through the platform device-font loader (`DeviceFontFamilyName`),
 * neither of which exists off Android.
 *
 * A `google:` family is no longer substituted: [GoogleFontTypefaceResolver] downloads it into the
 * shared machine-local font cache — the same `(family, weight, italic) -> File` resolution the
 * Android lane's `FontsContractCompat` shadow and the figma-svg embed path use — so the branded
 * face this lane draws is the one every other lane draws. `device:` families, variation axes, and
 * any request the resolver can't serve (no cache configured, offline, a fetch that failed) still
 * map onto the nearest standard family: a documented parity limit, not a layout change — the string
 * renders in a real face at the right size and metrics from skiko.
 */

@Composable
internal fun RcPlayerText(layout: CoreText, modifier: Modifier) {
    val textId = layout.textId ?: return
    val text by rememberRemoteStringAsState(textId)
    val paintState = ComposeLocalPaint()
    updatePaintFromBundle(layout.mPaint, paintState, LocalRemoteContext.current)

    val data = layout.readDataReflection()

    val color = if (paintState.isColorSet) Color(paintState.color) else Color(data.colorValue)
    val fontSize = if (paintState.isTextSizeSet) paintState.textSize else data.fontSizeValue
    val density = LocalDensity.current
    val fontSizeSp = with(density) { fontSize.toSp() }
    val resolvedMaxFontSize = if (data.maxFontSize > 0f) data.maxFontSize else 400f
    val resolvedMinFontSize =
        minOf(if (data.minFontSize > 0f) data.minFontSize else 4f, resolvedMaxFontSize)

    val fontWeight =
        if (paintState.isTypefaceSet) FontWeight(paintState.fontWeight)
        else FontWeight(data.fontWeightValue.toInt())
    val fontStyle =
        if (paintState.isTypefaceSet) paintState.fontStyle
        else {
            if (data.fontStyle == 1) FontStyle.Italic else FontStyle.Normal
        }

    val fontFamilyType = if (paintState.isTypefaceSet) paintState.fontFamily else data.type
    val fontFamily =
        standardFontFamily(
            fontFamilyType,
            LocalRemoteContext.current.getText(fontFamilyType),
            fontWeight,
            fontStyle,
            fontVariationSettings(data.fontAxis, data.fontAxisValues, LocalRemoteContext.current),
        )

    val baseStyle = LocalTextStyle.current

    val textDecoration =
        when {
            data.underline && data.strikethrough ->
                TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            data.underline -> TextDecoration.Underline
            data.strikethrough -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }

    val ambientLineHeightStyle =
        baseStyle.copy(
            color = color,
            fontSize = fontSizeSp,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            fontStyle = fontStyle,
            textAlign =
                if (data.justificationMode == 1) TextAlign.Justify else when (data.textAlignValue) {
                    CoreText.TEXT_ALIGN_LEFT -> TextAlign.Left
                    CoreText.TEXT_ALIGN_RIGHT -> TextAlign.Right
                    CoreText.TEXT_ALIGN_CENTER -> TextAlign.Center
                    // AndroidX Java maps this field to ALIGN_NORMAL. Actual justification is the
                    // separate CoreText property 17 (justificationMode).
                    CoreText.TEXT_ALIGN_JUSTIFY -> TextAlign.Start
                    CoreText.TEXT_ALIGN_START -> TextAlign.Start
                    CoreText.TEXT_ALIGN_END -> TextAlign.End
                    else -> TextAlign.Start
                },
            letterSpacing = data.letterSpacing.em,
            textDecoration = textDecoration,
            // Unset means `Unspecified`, not "inherit": a document that says nothing about line
            // breaking must not pick up Material3's line-break role from the host (#3667).
            lineBreak =
                when (data.lineBreakStrategy) {
                    1 -> LineBreak.Paragraph
                    2 -> LineBreak.Heading
                    else -> LineBreak.Unspecified
                },
            hyphens = if (data.hyphenationFrequency > 0) Hyphens.Auto else Hyphens.Unspecified,
        )
    val textStyle =
        if (data.lineHeightMultiplier != 1f || data.lineHeightAdd != 0f) {
            ambientLineHeightStyle.copy(
                lineHeight =
                    if (data.autosize) {
                        (data.lineHeightMultiplier + data.lineHeightAdd / fontSize.coerceAtLeast(0.0001f)).em
                    } else {
                        with(density) { (fontSize * data.lineHeightMultiplier + data.lineHeightAdd).toSp() }
                    },
            )
        } else {
            ambientLineHeightStyle
        }
    SynchronousOneLineEllipsisText(
        text = text,
        modifier = modifier,
        overflow = composeOverflow(data.overflow),
        maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
        measureStyle = textStyle,
        autoSize =
            if (data.autosize) {
                SynchronousAutoSize(
                    minFontSize = with(density) { resolvedMinFontSize.toSp() },
                    maxFontSize = with(density) { resolvedMaxFontSize.toSp() },
                    stepSize = with(density) { 0.5f.toSp() },
                )
            } else {
                null
            },
    ) { displayText, displayModifier, displayOverflow, resolvedFontSize ->
        Text(
            text = displayText,
            modifier = displayModifier,
            autoSize =
                if (data.autosize && resolvedFontSize == null) {
                    TextAutoSize.StepBased(
                        minFontSize = with(density) { resolvedMinFontSize.toSp() },
                        maxFontSize = with(density) { resolvedMaxFontSize.toSp() },
                        stepSize = with(density) { 0.5f.toSp() },
                    )
                } else {
                    null
                },
            overflow = displayOverflow,
            maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
            style =
                if (resolvedFontSize == null) textStyle
                else textStyle.copy(fontSize = resolvedFontSize),
        )
    }
}

@Composable
internal fun RcPlayerText(layout: TextLayout, modifier: Modifier) {
    val textId = layout.textId ?: return
    val text by rememberRemoteStringAsState(textId)
    val paintState = ComposeLocalPaint()
    updatePaintFromBundle(layout.mPaint, paintState, LocalRemoteContext.current)

    val data = layout.readDataReflection()

    val color = if (paintState.isColorSet) Color(paintState.color) else Color(data.colorValue)
    val fontSize = if (paintState.isTextSizeSet) paintState.textSize else data.fontSizeValue
    val fontSizeSp = with(LocalDensity.current) { fontSize.toSp() }

    val fontWeight =
        if (paintState.isTypefaceSet) FontWeight(paintState.fontWeight)
        else FontWeight(data.fontWeight.toInt())
    val fontStyle = if (paintState.isTypefaceSet) paintState.fontStyle else FontStyle.Normal

    val fontFamilyType = if (paintState.isTypefaceSet) paintState.fontFamily else data.type
    val fontFamily =
        standardFontFamily(
            fontFamilyType,
            LocalRemoteContext.current.getText(fontFamilyType),
            fontWeight,
            fontStyle,
            // `TextLayout` carries no axis arrays upstream (only `CoreText` does), so there is
            // nothing to apply here.
            variationSettings = null,
        )

    val textAlign =
        when (data.textAlignValue) {
                TextLayout.TEXT_ALIGN_LEFT -> TextAlign.Left
                TextLayout.TEXT_ALIGN_RIGHT -> TextAlign.Right
                TextLayout.TEXT_ALIGN_CENTER -> TextAlign.Center
                TextLayout.TEXT_ALIGN_JUSTIFY -> TextAlign.Start
                TextLayout.TEXT_ALIGN_START -> TextAlign.Start
                TextLayout.TEXT_ALIGN_END -> TextAlign.End
            else -> TextAlign.Start
        }
    val textStyle =
        LocalTextStyle.current.copy(
            color = color,
            fontSize = fontSizeSp,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            fontStyle = fontStyle,
            textAlign = textAlign,
        )
    SynchronousOneLineEllipsisText(
        text = text,
        modifier = modifier,
        overflow = composeOverflow(data.overflow),
        maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
        measureStyle = textStyle,
    ) { displayText, displayModifier, displayOverflow, _ ->
        Text(
            text = displayText,
            modifier = displayModifier,
            overflow = displayOverflow,
            maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
            style = textStyle,
        )
    }
}

/**
 * Skiko currently treats [TextOverflow.StartEllipsis] and [TextOverflow.MiddleEllipsis] as end
 * ellipsis. Resolve those two one-line modes during the same measure pass that supplies the width,
 * then ask Skiko to clip the already-truncated line. `SubcomposeLayout` is important here: the
 * renderer captures a one-frame `ImageComposeScene`, so an `onTextLayout` state update is too late.
 */
@Composable
private fun SynchronousOneLineEllipsisText(
    text: String,
    modifier: Modifier,
    overflow: TextOverflow,
    maxLines: Int,
    measureStyle: TextStyle,
    autoSize: SynchronousAutoSize? = null,
    content: @Composable (String, Modifier, TextOverflow, TextUnit?) -> Unit,
) {
    if (
        maxLines != 1 ||
            overflow != TextOverflow.StartEllipsis && overflow != TextOverflow.MiddleEllipsis
    ) {
        content(text, modifier, overflow, null)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val resolvedStyle =
            autoSize?.resolveStyle(text, measureStyle, constraints, textMeasurer) ?: measureStyle
        val displayText =
            if (constraints.hasBoundedWidth) {
                synchronousEllipsis(text, overflow, constraints.maxWidth) { candidate ->
                    textMeasurer.singleLineWidth(candidate, resolvedStyle)
                }
            } else {
                text
            }
        val placeable =
            subcompose(displayText) {
                    content(
                        displayText,
                        Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
                        TextOverflow.Clip,
                        autoSize?.let { resolvedStyle.fontSize },
                    )
                }
                .single()
                .measure(constraints)
        val alignmentLines: Map<AlignmentLine, Int> =
            listOf(FirstBaseline, LastBaseline).mapNotNull { line ->
                placeable[line].takeUnless { it == AlignmentLine.Unspecified }?.let { line to it }
            }.toMap()
        layout(placeable.width, placeable.height, alignmentLines) {
            placeable.place(0, 0)
        }
    }
}

private data class SynchronousAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
    val stepSize: TextUnit,
) {
    fun resolveStyle(
        text: String,
        style: TextStyle,
        constraints: Constraints,
        textMeasurer: TextMeasurer,
    ): TextStyle {
        val steps = ((maxFontSize.value - minFontSize.value) / stepSize.value).toInt().coerceAtLeast(0)
        var low = 0
        var high = steps
        var best = -1
        while (low <= high) {
            val candidate = (low + high) / 2
            val candidateStyle =
                style.copy(fontSize = (minFontSize.value + candidate * stepSize.value).sp)
            val size = textMeasurer.singleLineSize(text, candidateStyle)
            val fits =
                (!constraints.hasBoundedWidth || size.width <= constraints.maxWidth) &&
                    (!constraints.hasBoundedHeight || size.height <= constraints.maxHeight)
            if (fits) {
                best = candidate
                low = candidate + 1
            } else {
                high = candidate - 1
            }
        }
        val chosen = best.coerceAtLeast(0)
        return style.copy(fontSize = (minFontSize.value + chosen * stepSize.value).sp)
    }
}

private fun TextMeasurer.singleLineWidth(text: String, style: TextStyle): Int =
    singleLineSize(text, style).width

private fun TextMeasurer.singleLineSize(text: String, style: TextStyle) =
    measure(
            text = text,
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        .size

private const val ELLIPSIS = "\u2026"

/** Plain-string equivalent of Android's one-line START/MIDDLE `TextUtils.ellipsize` split. */
internal fun synchronousEllipsis(
    text: String,
    overflow: TextOverflow,
    maxWidth: Int,
    measureWidth: (String) -> Int,
): String {
    require(overflow == TextOverflow.StartEllipsis || overflow == TextOverflow.MiddleEllipsis)
    if (measureWidth(text) <= maxWidth) return text
    if (maxWidth <= 0 || measureWidth(ELLIPSIS) > maxWidth) return ""

    val boundaries = graphemeBoundaries(text)
    if (overflow == TextOverflow.StartEllipsis) {
        var low = 0
        var high = boundaries.lastIndex
        while (low < high) {
            val kept = (low + high + 1) / 2
            val candidate = ELLIPSIS + text.substring(boundaries[boundaries.lastIndex - kept])
            if (measureWidth(candidate) <= maxWidth) low = kept else high = kept - 1
        }
        return ELLIPSIS + text.substring(boundaries[boundaries.lastIndex - low])
    }

    val textBudget = maxWidth - measureWidth(ELLIPSIS)
    val halfBudget = textBudget / 2
    var suffixCount = 0
    while (
        suffixCount < boundaries.lastIndex &&
            measureWidth(text.substring(boundaries[boundaries.lastIndex - suffixCount - 1])) <=
                halfBudget
    ) {
        suffixCount++
    }
    val suffix = text.substring(boundaries[boundaries.lastIndex - suffixCount])
    val prefixBudget = textBudget - measureWidth(suffix)
    var prefixCount = 0
    while (
        prefixCount + suffixCount < boundaries.lastIndex &&
            measureWidth(text.substring(0, boundaries[prefixCount + 1])) <= prefixBudget
    ) {
        prefixCount++
    }
    var candidate =
        text.substring(0, boundaries[prefixCount]) +
            ELLIPSIS +
            text.substring(boundaries[boundaries.lastIndex - suffixCount])
    while (measureWidth(candidate) > maxWidth && prefixCount > 0) {
        prefixCount--
        candidate =
            text.substring(0, boundaries[prefixCount]) +
                ELLIPSIS +
                text.substring(boundaries[boundaries.lastIndex - suffixCount])
    }
    return candidate
}

private val extendedGrapheme = Regex("\\X")

private fun graphemeBoundaries(text: String): List<Int> =
    buildList {
        add(0)
        extendedGrapheme.findAll(text).forEach { match -> add(match.range.last + 1) }
    }

private fun composeOverflow(overflow: Int): TextOverflow =
    when (overflow) {
        CoreText.OVERFLOW_CLIP -> TextOverflow.Clip
        CoreText.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
        CoreText.OVERFLOW_VISIBLE -> TextOverflow.Visible
        CoreText.OVERFLOW_START_ELLIPSIS -> TextOverflow.StartEllipsis
        CoreText.OVERFLOW_MIDDLE_ELLIPSIS -> TextOverflow.MiddleEllipsis
        else -> TextOverflow.Clip
    }

private fun javaPlayerMaxLines(overflow: Int, maxLines: Int): Int =
    if (
        maxLines > 1 &&
            (overflow == CoreText.OVERFLOW_CLIP || overflow == CoreText.OVERFLOW_VISIBLE)
    ) {
        Int.MAX_VALUE
    } else {
        maxLines
    }

/**
 * Map the document's font-family type / name onto a multiplatform [FontFamily] at
 * [weight]/[style].
 *
 * The built-in ids (0 default, 1 sans-serif, 2 serif, 3 monospace) resolve directly. A `google:`
 * family is a *downloadable font* request, and [GoogleFontTypefaceResolver] serves it from the
 * shared machine-local Google Fonts cache — the same file every other lane resolves that family to
 * — so a branded specimen renders in its real face here too. Everything the resolver can't serve
 * (no cache configured, an offline miss, a `device:` family, a bare local name) still falls back to
 * the nearest standard family: a substitution, not an error.
 */
/**
 * The document's font-variation axes as a Compose [FontVariation.Settings], or null when the text
 * declares none.
 *
 * Axis tags arrive as *text ids* — the document interns `"wght"` like any other string — so each is
 * resolved through the [context] before it is paired with its value. Tags and values are positional,
 * so an axis counts only when both halves are present: pairing a tag with a neighbour's value would
 * draw a real face at silently the wrong instance, which is worse than dropping it.
 */
private fun fontVariationSettings(
    axisTagIds: IntArray?,
    axisValues: FloatArray?,
    context: RemoteContext,
): FontVariation.Settings? {
    if (axisTagIds == null || axisValues == null) return null
    val axes =
        axisTagIds.asList().mapIndexedNotNull { index, tag ->
            val value = axisValues.getOrNull(index) ?: return@mapIndexedNotNull null
            axisName(tag, context)?.let { FontVariation.Setting(it, value) }
        }
    return if (axes.isEmpty()) null else FontVariation.Settings(*axes.toTypedArray())
}

/**
 * The axis name an [tag] int stands for, in either encoding the format uses.
 *
 * A `CoreText` style interns its axis names like any other string, so the int is a **text id** — a
 * captured `RemoteText` carrying `wght` writes `TextData(44, "wght")` and puts `44` in the array.
 * The paint bundle's `setTextAxis` op instead carries the **raw OpenType tag** packed into four
 * bytes (`0x77676874` = `wght`), which is how the baseline players decode that path. Reading the
 * text table first and falling back to unpacking the bytes covers both without having to know which
 * writer produced the document; anything that is neither is dropped rather than guessed at.
 */
private fun axisName(tag: Int, context: RemoteContext): String? =
    context.getText(tag)?.takeIf { it.isNotBlank() } ?: packedAxisName(tag)

/**
 * The four-character axis name packed into [tag]'s bytes (`0x77676874` → `wght`), or null when the
 * bytes aren't printable ASCII — a small text id, for instance, unpacks to control characters and is
 * not a tag. Split out (and `internal`) so the unpacking is testable without a `RemoteContext`.
 */
internal fun packedAxisName(tag: Int): String? {
    val packed =
        CharArray(4) { index -> ((tag shr (24 - index * 8)) and 0xff).toChar() }.concatToString()
    return packed.takeIf { name -> name.all { it in '!'..'~' } }
}

private fun standardFontFamily(
    fontFamilyType: Int,
    customName: String?,
    weight: FontWeight,
    style: FontStyle,
    variationSettings: FontVariation.Settings? = null,
): FontFamily {
    val name =
        when (fontFamilyType) {
            0 -> "sans-serif"
            1 -> "sans-serif"
            2 -> "serif"
            3 -> "monospace"
            else -> customName
        }
    GoogleFontTypefaceResolver.Default.composeFontFamily(
            family = name,
            weight = weight.weight,
            italic = style == FontStyle.Italic,
            settings = variationSettings,
        )
        ?.let {
            return it
        }
    val cleaned = name?.substringAfter("device:")?.substringAfter("google:")
    return when (cleaned) {
        "sans-serif" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
}
