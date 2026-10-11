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
 * Renders every Material 3 type role in [typography] as a labelled row (`displayLarge` …
 * `labelSmall`), so regressions in a theme's `Typography` show up as a pixel diff. Fifteen rows in
 * the order of the M3 type-scale table
 * (https://m3.material.io/styles/typography/type-scale-tokens). Sample text is an English pangram;
 * use `@Preview(locale = …)` for other scripts.
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
 * Material 3 role order for [TypographySpecimen], as an ordered list so the PNG is deterministic.
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
 * One specimen row: a fixed 140dp label column (fits the longest role name) in a small fixed style,
 * then the sample text in [style].
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
 * Fixed small monospace label style shared by the specimens, independent of the theme's own
 * `labelSmall`.
 */
internal val LabelStyle: TextStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)

internal const val SAMPLE_PANGRAM: String = "The quick brown fox"
