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

import androidx.compose.material3.Text
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.TextLayout
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteStringAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import ee.schimke.composeai.rcembedded.jvm.GoogleFontTypefaceResolver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/*
 * The jvm counterpart of `RcPlayerTextLayout.kt`'s two `RcPlayerText` composables. The size, weight,
 * slant, colour, alignment, overflow, decoration, letter-spacing and line-height handling are all
 * verbatim — they use only multiplatform Compose text APIs and desktop `material3.Text`, so text
 * layout matches Android. What differs is **font family resolution**: Android resolves `google:`
 * families through GMS downloadable fonts and `device:` / variation-axis families through the
 * platform device-font loader (`DeviceFontFamilyName`), neither of which exists off Android.
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
    val fontSizeSp = with(LocalDensity.current) { fontSize.toSp() }

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

    val textDecoration =
        when {
            data.underline && data.strikethrough ->
                TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            data.underline -> TextDecoration.Underline
            data.strikethrough -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }

    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSizeSp,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        textAlign =
            when (data.textAlignValue) {
                CoreText.TEXT_ALIGN_LEFT -> TextAlign.Left
                CoreText.TEXT_ALIGN_RIGHT -> TextAlign.Right
                CoreText.TEXT_ALIGN_CENTER -> TextAlign.Center
                CoreText.TEXT_ALIGN_JUSTIFY -> TextAlign.Justify
                CoreText.TEXT_ALIGN_START -> TextAlign.Start
                CoreText.TEXT_ALIGN_END -> TextAlign.End
                else -> TextAlign.Start
            },
        overflow =
            when (data.overflow) {
                CoreText.OVERFLOW_CLIP -> TextOverflow.Clip
                CoreText.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
                CoreText.OVERFLOW_VISIBLE -> TextOverflow.Visible
                else -> TextOverflow.Clip
            },
        maxLines = data.maxLines,
        letterSpacing = data.letterSpacing.em,
        lineHeight =
            if (data.lineHeightMultiplier != 1f || data.lineHeightAdd != 0f) {
                with(LocalDensity.current) {
                    (data.fontSizeValue * data.lineHeightMultiplier + data.lineHeightAdd).toSp()
                }
            } else {
                TextUnit.Unspecified
            },
        textDecoration = textDecoration,
    )
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

    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSizeSp,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        textAlign =
            when (data.textAlignValue) {
                TextLayout.TEXT_ALIGN_LEFT -> TextAlign.Left
                TextLayout.TEXT_ALIGN_RIGHT -> TextAlign.Right
                TextLayout.TEXT_ALIGN_CENTER -> TextAlign.Center
                TextLayout.TEXT_ALIGN_JUSTIFY -> TextAlign.Justify
                TextLayout.TEXT_ALIGN_START -> TextAlign.Start
                TextLayout.TEXT_ALIGN_END -> TextAlign.End
                else -> TextAlign.Start
            },
        overflow =
            when (data.overflow) {
                TextLayout.OVERFLOW_CLIP -> TextOverflow.Clip
                TextLayout.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
                TextLayout.OVERFLOW_VISIBLE -> TextOverflow.Visible
                else -> TextOverflow.Clip
            },
        maxLines = data.maxLines,
    )
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
        axisTagIds.asList().mapIndexedNotNull { index, tagId ->
            val value = axisValues.getOrNull(index) ?: return@mapIndexedNotNull null
            context.getText(tagId)?.takeIf { it.isNotBlank() }?.let { FontVariation.Setting(it, value) }
        }
    return if (axes.isEmpty()) null else FontVariation.Settings(*axes.toTypedArray())
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
