package com.example.designcatalogwearm3

import androidx.wear.compose.material3.Typography
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins that a selected theme / font actually reaches the Wear type scale.
 *
 * This exists because the failure it guards is **silent**. The obvious spelling —
 * `Typography(defaultFontFamily = family)` — compiles, reads correctly, and does nothing: it
 * applies through `TextStyle.withDefaultFontFamily`, which only fills in a role that has *no*
 * family, and every Wear `TypographyTokens` role already declares one
 * (`Font(DeviceFontFamilyName("roboto-flex"), variationSettings = …)`). So both the
 * `@WearThemeCatalog` themes and the `knob.theme.font` override rendered in the stock face while
 * looking, in code and in review, exactly as if they didn't.
 *
 * A rendered sheet doesn't catch it either: the Wear theme specimen lays its type rows out below 21
 * colour swatches, past the bottom of the fixed 400×800 canvas, so a theme's typeface is not in its
 * own published PNG at all. These assertions are the only place the wiring is checked.
 */
class WearCatalogTypographyTest {

  @Test
  fun `KotlinConf pairs JetBrains Mono titles with Inter body`() {
    val typography = wearCatalogTypography("KotlinConf")

    assertThat(typography.displayLarge.fontFamily).isEqualTo(JetBrainsMono)
    assertThat(typography.titleLarge.fontFamily).isEqualTo(JetBrainsMono)
    assertThat(typography.numeralLarge.fontFamily).isEqualTo(JetBrainsMono)
    assertThat(typography.bodyLarge.fontFamily).isEqualTo(Inter)
    assertThat(typography.labelSmall.fontFamily).isEqualTo(Inter)
  }

  @Test
  fun `Google Sans Flex theme puts one face on the whole scale`() {
    val typography = wearCatalogTypography("Google Sans Flex")

    assertThat(typography.titleLarge.fontFamily).isEqualTo(GoogleSansFlex)
    assertThat(typography.bodyLarge.fontFamily).isEqualTo(GoogleSansFlex)
    assertThat(typography.labelSmall.fontFamily).isEqualTo(GoogleSansFlex)
  }

  @Test
  fun `every declared font name resolves to a distinct family`() {
    val families = WEAR_FONT_NAMES.map { wearCatalogFont(it) }

    assertThat(families).containsNoDuplicates()
  }

  @Test
  fun `palette-only themes and the default font leave the stock scale untouched`() {
    // Roboto Flex already IS the Wear default, reached as a device font carrying the expressive
    // variable axes — re-pointing it at a same-named downloadable family would drop those axes and
    // change the pixels of an un-themed render.
    for (name in listOf("Roboto Flex", "M3", "Coral", "Teal")) {
      assertThat(wearCatalogTypography(name)).isEqualTo(Typography())
    }
  }
}
