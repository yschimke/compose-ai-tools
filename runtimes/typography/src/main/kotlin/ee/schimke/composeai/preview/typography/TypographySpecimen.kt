package ee.schimke.composeai.preview.typography

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders every Material 3 type role in [typography] as a labelled row — `displayLarge`,
 * `displayMedium`, …, `labelSmall` — so visual regressions in a custom Material 3 theme's
 * `Typography` surface as a pixel diff in the surrounding `@Preview`.
 *
 * Pairs with a normal `@Preview` (stacked or single). Authors who wrap a Material 3 `Typography`
 * value in this helper get a one-PNG audit of every role at the size, weight, and family that
 * `MaterialTheme.typography` is configured to use. The output matches the Material 3 reference
 * table at <https://m3.material.io/styles/typography/type-scale-tokens> — fifteen rows in the
 * standard descending order (display → headline → title → body → label, each at large / medium /
 * small).
 *
 * Sample text is the canonical English pangram so consumers can eyeball ascender / descender /
 * kerning behaviour across the scale. Localised pangrams aren't surfaced here on purpose — the
 * existing `@Preview(locale = …)` knob already fans the same composable out across locales when the
 * consumer needs per-script samples.
 */
@Composable
fun TypographySpecimen(typography: Typography, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for ((label, style) in typographyRoles(typography)) {
      SpecimenRow(label = label, style = style, text = SAMPLE_PANGRAM)
    }
  }
}

/**
 * Material 3 role order for [TypographySpecimen]. Returned as a stable `List<Pair<...>>` rather
 * than a `Map` so the row sequence is deterministic across calls (the rendered PNG diffs cleanly).
 * Matches the descending size order in the M3 reference table — fifteen entries, three sizes per
 * role family, in the order display → headline → title → body → label.
 */
private fun typographyRoles(typography: Typography): List<Pair<String, TextStyle>> =
  listOf(
    "displayLarge" to typography.displayLarge,
    "displayMedium" to typography.displayMedium,
    "displaySmall" to typography.displaySmall,
    "headlineLarge" to typography.headlineLarge,
    "headlineMedium" to typography.headlineMedium,
    "headlineSmall" to typography.headlineSmall,
    "titleLarge" to typography.titleLarge,
    "titleMedium" to typography.titleMedium,
    "titleSmall" to typography.titleSmall,
    "bodyLarge" to typography.bodyLarge,
    "bodyMedium" to typography.bodyMedium,
    "bodySmall" to typography.bodySmall,
    "labelLarge" to typography.labelLarge,
    "labelMedium" to typography.labelMedium,
    "labelSmall" to typography.labelSmall,
  )

/**
 * One specimen row: a fixed-width label column on the left, the sample text rendered at [style] on
 * the right. The label uses a small, role-agnostic size so a `displayLarge` row's label doesn't
 * itself span the whole row — labels are wayfinding, not content. The label column width (140.dp)
 * is tuned to hold the longest M3 role name (`displayMedium` / `headlineMedium` / `labelMedium`)
 * without wrapping at typical preview densities.
 */
@Composable
internal fun SpecimenRow(label: String, style: TextStyle, text: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    Text(text = label, modifier = Modifier.width(140.dp).padding(end = 8.dp), style = LabelStyle)
    Text(text = text, style = style)
  }
}

/**
 * The fixed label style for specimen rows. Hard-coded to a small monospace size so the label column
 * reads consistently across `TypographySpecimen`, `FontFamilySpecimen`, and
 * `FallbackCoverageSpecimen` — including when the specimen is rendered against a `Typography` whose
 * own `labelSmall` has been heavily customised. Monospace keeps the column visually aligned even
 * when role names of different lengths share the column.
 */
internal val LabelStyle: TextStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)

internal const val SAMPLE_PANGRAM: String = "The quick brown fox"
