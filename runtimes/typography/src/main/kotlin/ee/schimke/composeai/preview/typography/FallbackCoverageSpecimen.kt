package ee.schimke.composeai.preview.typography

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a small fixed set of canonical strings designed to surface missing glyphs or wrong font
 * fallback in the active text stack. One row per script — Latin, CJK, Arabic, Devanagari, Emoji
 * — labelled with the script name so a "□□□" tofu or "?" replacement glyph in the rendered PNG
 * makes the broken script obvious at a glance.
 *
 * Pairs with a normal `@Preview`. The helper deliberately does NOT load any custom fallback fonts
 * — the rendered output reflects the platform's default text stack as configured by the renderer
 * (Robolectric for Android `@Preview`, Skia for CMP Desktop). The intent is "does the
 * out-of-the-box fallback chain cover the scripts my product ships?" rather than "does this
 * specific custom font cover them" — for the latter, wrap the helper call in a
 * `CompositionLocalProvider(LocalTextStyle provides …)` with the custom family applied.
 *
 * Script choice mirrors the canonical Google Fonts script-coverage check set:
 *
 *  - Latin — `The quick brown fox`, the standard English pangram. Sanity row; every fallback chain
 *    handles Latin.
 *  - CJK — combined Chinese / Japanese / Korean greetings (`你好世界 / こんにちは / 안녕하세요`). The
 *    three scripts often share a single fallback font on Android (`NotoSansCJK`); a tofu in any
 *    segment flags a renderer-side fallback gap.
 *  - Arabic — `السلام عليكم`, "peace be upon you". Right-to-left script with shaping / ligatures
 *    — surfaces both glyph-availability AND shaper-correctness regressions.
 *  - Devanagari — `नमस्ते दुनिया`, "hello world". Complex Indic script with combining marks; tests
 *    HarfBuzz cluster handling in addition to plain glyph coverage.
 *  - Emoji — `👋🌍🚀✨🎨`. Each codepoint needs the colour-emoji fallback; broken fallback shows
 *    monochrome outlines or tofu depending on the renderer's font path.
 *
 * Sample text is rendered at a fixed 18.sp through the row's [TextStyle] default — large enough
 * that a tofu rectangle is visually distinct from a real glyph, small enough that all five rows
 * fit a typical preview height without truncation.
 */
@Composable
fun FallbackCoverageSpecimen(modifier: Modifier = Modifier) {
  val style = TextStyle(fontSize = 18.sp)
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for ((label, text) in FallbackScripts) {
      SpecimenRow(label = label, style = style, text = text)
    }
  }
}

/**
 * Canonical script-coverage samples for [FallbackCoverageSpecimen]. Stable order so the rendered
 * PNG diffs cleanly across runs; each entry deliberately exercises a distinct shaping / glyph
 * dimension (LTR vs RTL, simple vs complex cluster, monochrome glyph vs colour emoji).
 */
internal val FallbackScripts: List<Pair<String, String>> =
  listOf(
    "Latin" to "The quick brown fox",
    "CJK" to "你好世界 / こんにちは / 안녕하세요",
    "Arabic" to "السلام عليكم",
    "Devanagari" to "नमस्ते दुनिया",
    "Emoji" to "👋🌍🚀✨🎨",
  )
