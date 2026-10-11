package ee.schimke.composeai.preview.typography

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders [fontFamily] across [weights] as a labelled weight ladder, so a missing weight (which
 * silently falls back to the nearest one) shows up as a same-weight twin row.
 *
 * @param fontFamily the family to specimen: a stock constant (`FontFamily.SansSerif`, …) or a
 *   `FontFamily(Font(...))` from `res/font/` or a Google Fonts provider.
 * @param sampleText the text in every row; override with a localised pangram to exercise
 *   diacritics or non-Latin scripts.
 * @param weights the weights to render; defaults to Light / Normal / Medium / SemiBold / Bold. Pass
 *   a wider list for a variable font.
 */
@Composable
fun FontFamilySpecimen(
  fontFamily: FontFamily,
  sampleText: String = SAMPLE_PANGRAM_LONG,
  weights: List<FontWeight> = DefaultWeights,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (weight in weights) {
      SpecimenRow(
        label = weightLabel(weight),
        style = TextStyle(fontFamily = fontFamily, fontWeight = weight, fontSize = 18.sp),
        text = sampleText,
      )
    }
  }
}

/**
 * Default weight ladder for [FontFamilySpecimen]: five entries, so it fits a default preview
 * height.
 */
internal val DefaultWeights: List<FontWeight> =
  listOf(
    FontWeight.Light,
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
  )

/**
 * [weight]'s `FontWeight` companion name for row labels, or the numeric value (e.g. `w350`) for
 * custom weights.
 */
internal fun weightLabel(weight: FontWeight): String =
  when (weight) {
    FontWeight.Thin -> "Thin"
    FontWeight.ExtraLight -> "ExtraLight"
    FontWeight.Light -> "Light"
    FontWeight.Normal -> "Normal"
    FontWeight.Medium -> "Medium"
    FontWeight.SemiBold -> "SemiBold"
    FontWeight.Bold -> "Bold"
    FontWeight.ExtraBold -> "ExtraBold"
    FontWeight.Black -> "Black"
    else -> "w${weight.weight}"
  }

internal const val SAMPLE_PANGRAM_LONG: String = "The quick brown fox jumps over the lazy dog"
