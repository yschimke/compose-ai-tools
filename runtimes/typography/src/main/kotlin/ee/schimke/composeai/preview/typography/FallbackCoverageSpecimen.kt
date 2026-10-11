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
 * Renders one canonical string per script — Latin, CJK, Arabic, Devanagari, Emoji — labelled, so
 * tofu or replacement glyphs from a broken fallback chain stand out.
 *
 * Loads no custom fonts: it checks the renderer's default fallback chain (Robolectric or Skia). To
 * check a custom family, provide it via `LocalTextStyle` around the call.
 *
 * Scripts follow the Google Fonts coverage check set: Latin (sanity), combined CJK (often one
 * fallback font), Arabic (RTL shaping), Devanagari (complex clusters) and colour emoji. 18sp makes
 * tofu distinguishable while fitting all five rows.
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
 * Script-coverage samples for [FallbackCoverageSpecimen], in stable order, each exercising a
 * different shaping or glyph dimension.
 */
internal val FallbackScripts: List<Pair<String, String>> =
  listOf(
    "Latin" to "The quick brown fox",
    "CJK" to "你好世界 / こんにちは / 안녕하세요",
    "Arabic" to "السلام عليكم",
    "Devanagari" to "नमस्ते दुनिया",
    "Emoji" to "👋🌍🚀✨🎨",
  )
