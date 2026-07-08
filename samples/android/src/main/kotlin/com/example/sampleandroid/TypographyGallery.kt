package com.example.sampleandroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.typography.FallbackCoverageSpecimen
import ee.schimke.composeai.preview.typography.FontFamilySpecimen
import ee.schimke.composeai.preview.typography.TypographySpecimen

/**
 * Gallery of the three `typography-preview-runtime` helper composables. Each `@Preview` here
 * exercises one helper, wrapped in a `MaterialTheme` + `Surface` so the rendered PNG carries the
 * standard M3 surface background / content-colour. No font assets — `FontFamily.SansSerif` is the
 * stock platform sans, so the gallery renders on a fresh consumer with no `res/font/` resources.
 */
@Preview(name = "Typography specimen", widthDp = 420, heightDp = 720)
@Composable
fun TypographySpecimenPreview() {
  MaterialTheme { Surface { TypographySpecimen(typography = Typography()) } }
}

@Preview(name = "FontFamily specimen", widthDp = 420, heightDp = 320)
@Composable
fun FontFamilySpecimenPreview() {
  MaterialTheme { Surface { FontFamilySpecimen(fontFamily = FontFamily.SansSerif) } }
}

@Preview(name = "Fallback coverage specimen", widthDp = 420, heightDp = 280)
@Composable
fun FallbackCoverageSpecimenPreview() {
  MaterialTheme { Surface { FallbackCoverageSpecimen() } }
}
