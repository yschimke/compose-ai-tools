package com.example.sampleandroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.color.ColorSchemeSpecimen
import ee.schimke.composeai.preview.color.ColorSpecimen

/**
 * Gallery of the `color-preview-runtime` helper composables. Each `@Preview` here exercises one
 * helper, wrapped in a `MaterialTheme` + `Surface` so the rendered PNG carries the standard M3
 * surface background / content-colour. No colour resources — the schemes come from the stock
 * `lightColorScheme()` / `darkColorScheme()` factories, so the gallery renders on a fresh consumer.
 *
 * The light + dark scheme previews are separate `@Preview`s (rather than one `uiMode`-driven pair)
 * so both palettes land as their own committed PNG in the sample gallery — the sister to
 * `TypographyGallery`'s specimen sheets.
 */
@Preview(name = "ColorScheme specimen — light", widthDp = 360, heightDp = 1100)
@Composable
fun ColorSchemeSpecimenLightPreview() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Surface { ColorSchemeSpecimen(colorScheme = MaterialTheme.colorScheme) }
  }
}

@Preview(name = "ColorScheme specimen — dark", widthDp = 360, heightDp = 1100)
@Composable
fun ColorSchemeSpecimenDarkPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface { ColorSchemeSpecimen(colorScheme = MaterialTheme.colorScheme) }
  }
}

@Preview(name = "Named colour palette specimen", widthDp = 360, heightDp = 320)
@Composable
fun NamedColorPaletteSpecimenPreview() {
  MaterialTheme {
    Surface {
      ColorSpecimen(
        colors =
          listOf(
            "brand/coral" to Color(0xFFFF6F61),
            "brand/teal" to Color(0xFF008080),
            "brand/gold" to Color(0xFFFFD700),
            "brand/ink" to Color(0xFF1A1A2E),
            "brand/scrim" to Color(0x80000000),
          )
      )
    }
  }
}
