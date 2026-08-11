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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.TextLayout
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteStringAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFactory
import androidx.compose.ui.text.googlefonts.GoogleFont
import ee.schimke.composeai.rcembedded.GoogleVariableFontFamilies
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

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

    val remoteContext = LocalRemoteContext.current
    val fontVariationSettings =
        if (data.fontAxis != null && data.fontAxisValues != null) {
            val settings =
                data.fontAxis.asList().mapIndexedNotNull { index, id ->
                    val name = remoteContext.getText(id)
                    if (name != null) {
                        FontVariation.Setting(name, data.fontAxisValues[index])
                    } else {
                        null
                    }
                }
            if (settings.isNotEmpty()) {
                FontVariation.Settings(*settings.toTypedArray())
            } else {
                null
            }
        } else {
            null
        }

    val fontWeight =
        if (paintState.isTypefaceSet) FontWeight(paintState.fontWeight)
        else FontWeight(data.fontWeightValue.toInt())
    val fontStyle =
        if (paintState.isTypefaceSet) paintState.fontStyle
        else {
            if (data.fontStyle == 1) FontStyle.Italic else FontStyle.Normal
        }

    val fontFamilyType = if (paintState.isTypefaceSet) paintState.fontFamily else data.type
    val customFontNameState = rememberCustomFontName(fontFamilyType, remoteContext)
    val fontFamily =
        resolveFontFamily(
            fontFamilyType,
            customFontNameState.value,
            fontWeight,
            fontStyle,
            data.fontAxis,
            data.fontAxisValues,
            LocalRemoteContext.current,
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
            if (data.justificationMode > 0) TextAlign.Justify else when (data.textAlignValue) {
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
        autoSize =
            if (data.autosize) {
                TextAutoSize.StepBased(
                    minFontSize = ((if (data.minFontSize > 0f) data.minFontSize else 4f) / density.density).sp,
                    maxFontSize = ((if (data.maxFontSize > 0f) data.maxFontSize else 400f) / density.density).sp,
                    stepSize = (0.5f / density.density).sp,
                )
            } else {
                null
            },
        overflow =
            when (data.overflow) {
                CoreText.OVERFLOW_CLIP -> TextOverflow.Clip
                CoreText.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
                CoreText.OVERFLOW_VISIBLE -> TextOverflow.Visible
                CoreText.OVERFLOW_START_ELLIPSIS -> TextOverflow.StartEllipsis
                CoreText.OVERFLOW_MIDDLE_ELLIPSIS -> TextOverflow.MiddleEllipsis
                else -> TextOverflow.Clip
            },
        maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
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
        style =
            TextStyle(
                lineBreak =
                    when (data.lineBreakStrategy) {
                        1 -> LineBreak.Paragraph
                        2 -> LineBreak.Heading
                        else -> LineBreak.Simple
                    },
                hyphens = if (data.hyphenationFrequency > 0) Hyphens.Auto else Hyphens.None,
            ),
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
    val customFontNameState = rememberCustomFontName(fontFamilyType, LocalRemoteContext.current)
    val fontFamily =
        resolveFontFamily(
            fontFamilyType,
            customFontNameState.value,
            fontWeight,
            fontStyle,
            null,
            null,
            LocalRemoteContext.current,
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
                TextLayout.TEXT_ALIGN_JUSTIFY -> TextAlign.Start
                TextLayout.TEXT_ALIGN_START -> TextAlign.Start
                TextLayout.TEXT_ALIGN_END -> TextAlign.End
                else -> TextAlign.Start
            },
        overflow =
            when (data.overflow) {
                TextLayout.OVERFLOW_CLIP -> TextOverflow.Clip
                TextLayout.OVERFLOW_ELLIPSIS -> TextOverflow.Ellipsis
                TextLayout.OVERFLOW_VISIBLE -> TextOverflow.Visible
                TextLayout.OVERFLOW_START_ELLIPSIS -> TextOverflow.StartEllipsis
                TextLayout.OVERFLOW_MIDDLE_ELLIPSIS -> TextOverflow.MiddleEllipsis
                else -> TextOverflow.Clip
            },
        maxLines = javaPlayerMaxLines(data.overflow, data.maxLines),
    )
}

/**
 * Match `AndroidPaintContext`'s `StaticLayout` result rather than Compose's stricter line cap.
 *
 * The AndroidX Java player only makes `maxLines` truncate a paragraph when an ellipsis mode is
 * selected. Clip/visible paragraphs with more than one requested line continue laying out and are
 * bounded by the component's clip rect instead. A one-line request stays one line because Java's
 * `CoreText` uses its unwrapped fast path for that case.
 */
private fun javaPlayerMaxLines(overflow: Int, maxLines: Int): Int =
    if (
        maxLines > 1 &&
            (overflow == CoreText.OVERFLOW_CLIP || overflow == CoreText.OVERFLOW_VISIBLE)
    ) {
        Int.MAX_VALUE
    } else {
        maxLines
    }

@Composable
private fun rememberCustomFontName(fontFamilyType: Int, context: RemoteContext): State<String?> {
    return remember(fontFamilyType) {
        derivedStateOf {
            when (fontFamilyType) {
                0 -> "default"
                1 -> "sans-serif"
                2 -> "serif"
                3 -> "monospace"
                else -> context.getText(fontFamilyType)
            }
        }
    }
}

private val GmsFontProvider =
    GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = GmsFontProviderCertificates,
    )

private fun resolveFontFamily(
    fontFamilyType: Int,
    fontName: String?,
    fontWeight: FontWeight,
    fontStyle: FontStyle,
    fontAxis: IntArray?,
    fontAxisValues: FloatArray?,
    context: RemoteContext,
): FontFamily {
    if (fontName != null) {
        when {
            fontName.startsWith("device:") -> {
                val familyName = fontName.substring("device:".length)
                return createDeviceFontFamily(
                    familyName,
                    fontWeight,
                    fontStyle,
                    fontAxis,
                    fontAxisValues,
                    context,
                )
            }
            fontName.startsWith("google:") -> {
                // Axes first, when the document carries any: the downloadable-font factory below
                // takes a weight and a style and has no `variationSettings` parameter, so it can
                // resolve this family but not vary it. `GoogleVariableFontFamilies` instances the
                // family's variable file at the requested axes; it returns null for an unvaried
                // request, or when there is no variable file to be had, and the factory path
                // (unchanged) takes over.
                GoogleVariableFontFamilies.Default.composeFontFamily(
                        family = fontName,
                        weight = fontWeight,
                        style = fontStyle,
                        axes = fontVariationAxes(fontAxis, fontAxisValues, context),
                    )
                    ?.let {
                        return it
                    }
                val actualName = fontName.substring("google:".length)
                val googleFont = GoogleFont(actualName)
                return FontFamily(
                    GoogleFontFactory(
                        googleFont = googleFont,
                        fontProvider = GmsFontProvider,
                        weight = fontWeight,
                        style = fontStyle,
                    )
                )
            }
        }
    }

    val standardName =
        fontName
            ?: when (fontFamilyType) {
                1 -> "sans-serif"
                2 -> "serif"
                3 -> "monospace"
                else -> "sans-serif"
            }

    val standardFontFamily =
        when (standardName) {
            "sans-serif" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            "monospace" -> FontFamily.Monospace
            else -> FontFamily.Default
        }

    if (fontAxis != null && fontAxisValues != null) {
        val settings =
            fontAxis.asList().mapIndexedNotNull { index, id ->
                val name = context.getText(id)
                if (name != null) {
                    FontVariation.Setting(name, fontAxisValues[index])
                } else {
                    null
                }
            }
        if (settings.isNotEmpty()) {
            return FontFamily(
                Font(
                    DeviceFontFamilyName(standardName),
                    weight = fontWeight,
                    style = fontStyle,
                    variationSettings = FontVariation.Settings(*settings.toTypedArray()),
                )
            )
        }
    }

    return standardFontFamily
}

/**
 * The document's font-variation axes as `(tag, value)` pairs, empty when it declares none.
 *
 * Tags and values are positional, so an axis counts only when *both* halves are present: pairing a
 * tag with a neighbour's value would draw a real face at silently the wrong instance, which is worse
 * than dropping it. Kept as pairs rather than a `FontVariation.Settings` because the resolver caches
 * on them, and `Settings` compares by identity for this purpose.
 */
private fun fontVariationAxes(
    axisTagIds: IntArray?,
    axisValues: FloatArray?,
    context: RemoteContext,
): List<Pair<String, Float>> {
    if (axisTagIds == null || axisValues == null) return emptyList()
    return axisTagIds.asList().mapIndexedNotNull { index, tag ->
        val value = axisValues.getOrNull(index) ?: return@mapIndexedNotNull null
        axisName(tag, context)?.let { it to value }
    }
}

/**
 * The axis name a [tag] int stands for, in either encoding the format uses.
 *
 * A `CoreText` style interns its axis names like any other string, so the int is a **text id** — a
 * captured `RemoteText` carrying `wght` writes `TextData(44, "wght")` and puts `44` in the array.
 * The paint bundle's `setTextAxis` op instead carries the **raw OpenType tag** packed into four
 * bytes (`0x77676874` = `wght`). Reading the text table first and falling back to unpacking the
 * bytes covers both without having to know which writer produced the document; anything that is
 * neither is dropped rather than guessed at. Mirrors the jvm player's seam.
 */
private fun axisName(tag: Int, context: RemoteContext): String? =
    context.getText(tag)?.takeIf { it.isNotBlank() }
        ?: CharArray(4) { index -> ((tag shr (24 - index * 8)) and 0xff).toChar() }
            .concatToString()
            .takeIf { name -> name.all { it in '!'..'~' } }

private fun createDeviceFontFamily(
    familyName: String,
    fontWeight: FontWeight,
    fontStyle: FontStyle,
    fontAxis: IntArray?,
    fontAxisValues: FloatArray?,
    context: RemoteContext,
): FontFamily {
    val settings =
        if (fontAxis != null && fontAxisValues != null) {
            fontAxis
                .asList()
                .mapIndexedNotNull { index, id ->
                    val name = context.getText(id)
                    if (name != null) {
                        FontVariation.Setting(name, fontAxisValues[index])
                    } else {
                        null
                    }
                }
                .let {
                    if (it.isNotEmpty()) FontVariation.Settings(*it.toTypedArray())
                    else FontVariation.Settings()
                }
        } else {
            FontVariation.Settings()
        }

    return FontFamily(
        Font(
            DeviceFontFamilyName(familyName),
            weight = fontWeight,
            style = fontStyle,
            variationSettings = settings,
        )
    )
}
