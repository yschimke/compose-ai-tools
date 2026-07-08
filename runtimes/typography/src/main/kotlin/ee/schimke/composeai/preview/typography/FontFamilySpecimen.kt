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
 * Renders the supplied [fontFamily] across [weights] as a labelled weight ladder so consumers can
 * verify a custom family ships every weight they target (a missing weight silently falls back to
 * the nearest available weight on-device and renders as a same-weight twin row here — easy to spot
 * in a PNG diff).
 *
 * Pairs with a normal `@Preview`. Author one wrapper per family of interest (`Roboto`, a Google
 * Font, an OEM brand family) and the helper produces one row per [FontWeight] — each row labelled
 * with the weight token name (`Light` / `Normal` / `Medium` / `SemiBold` / `Bold`) and the
 * [sampleText] rendered at that weight.
 *
 * Defaults to the five most commonly shipped weights so the helper works out of the box for both
 * stock platform families (`FontFamily.SansSerif` etc., which the system synthesises every weight
 * for) and custom families with a typical Light → Bold range.
 *
 * @param fontFamily the family to specimen. Use the stock `FontFamily.SansSerif` / `Serif` /
 *   `Monospace` / `Cursive` constants if you don't have a custom family in mind, or a
 *   `FontFamily(Font(...))` built from the consumer's `res/font/` resources / Google Fonts provider
 *   for a real family check.
 * @param sampleText the pangram rendered in every row. Defaults to the canonical English pangram;
 *   override with a localised pangram (e.g. German "Falsches Üben von Xylophonmusik quält jeden
 *   größeren Zwerg.") to exercise diacritics or non-Latin scripts at each weight.
 * @param weights the weights to render. Defaults to `Light / Normal / Medium / SemiBold / Bold` —
 *   the five tokens most custom families ship variants for. Pass a wider list (`Thin`,
 *   `ExtraLight`, `ExtraBold`, `Black`) when speciming a variable font.
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
 * Default weight ladder for [FontFamilySpecimen]. Kept narrow (five entries) so a `@Preview` with
 * no `heightDp` override still fits the full ladder without scrolling. Consumers speciming a
 * variable font or a family with extreme weights should pass an explicit list.
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
 * Maps a [FontWeight] to its M3 / token name so the specimen row labels read like the
 * `androidx.compose.ui.text.font.FontWeight` companion constants. Unknown / custom weights fall
 * through to the numeric value (e.g. `w350`) so the label always renders something deterministic.
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
