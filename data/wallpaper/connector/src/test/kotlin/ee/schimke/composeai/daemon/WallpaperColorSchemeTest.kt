package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Smoke tests for the wallpaper-seed → ColorScheme derivation. The heavy lifting (HCT, tonal
 * palettes) lives in `com.materialkolor:material-kolor`; these tests pin our connector's
 * style/contrast plumbing and the `WallpaperPaletteStyle` ↔ upstream-enum mapping rather than
 * re-asserting Google's algorithm.
 */
class WallpaperColorSchemeTest {

  @Test
  fun light_and_dark_variants_differ_for_the_same_seed() {
    val seed = Color(0xFF3366FF)
    val light = WallpaperColorScheme.from(seed, isDark = false)
    val dark = WallpaperColorScheme.from(seed, isDark = true)
    assertNotEquals(light.primary, dark.primary)
    assertNotEquals(light.background, dark.background)
  }

  @Test
  fun different_palette_styles_produce_different_schemes() {
    val seed = Color(0xFF3366FF)
    val tonal =
      WallpaperColorScheme.from(seed, isDark = false, style = WallpaperPaletteStyle.TONAL_SPOT)
    val vibrant =
      WallpaperColorScheme.from(seed, isDark = false, style = WallpaperPaletteStyle.VIBRANT)
    val mono =
      WallpaperColorScheme.from(seed, isDark = false, style = WallpaperPaletteStyle.MONOCHROME)
    assertNotEquals(
      "tonalSpot vs vibrant should differ on at least one role",
      tonal.primary,
      vibrant.primary,
    )
    assertNotEquals(
      "tonalSpot vs monochrome should differ on at least one role",
      tonal.primary,
      mono.primary,
    )
  }

  @Test
  fun high_contrast_changes_role_luminance() {
    val seed = Color(0xFF3366FF)
    val standard = WallpaperColorScheme.from(seed, isDark = false, contrastLevel = 0.0)
    val high = WallpaperColorScheme.from(seed, isDark = false, contrastLevel = 1.0)
    val standardRoles =
      listOf(
        standard.primary,
        standard.onSurface,
        standard.onSurfaceVariant,
        standard.outline,
        standard.surfaceVariant,
      )
    val highRoles =
      listOf(high.primary, high.onSurface, high.onSurfaceVariant, high.outline, high.surfaceVariant)
    assertNotEquals(
      "high-contrast scheme should differ from the default on at least one role",
      standardRoles,
      highRoles,
    )
  }

  @Test
  fun palette_style_enum_maps_to_upstream_one_to_one() {
    assertEquals(PaletteStyle.TonalSpot, WallpaperPaletteStyle.TONAL_SPOT.toMaterialKolor())
    assertEquals(PaletteStyle.Neutral, WallpaperPaletteStyle.NEUTRAL.toMaterialKolor())
    assertEquals(PaletteStyle.Vibrant, WallpaperPaletteStyle.VIBRANT.toMaterialKolor())
    assertEquals(PaletteStyle.Expressive, WallpaperPaletteStyle.EXPRESSIVE.toMaterialKolor())
    assertEquals(PaletteStyle.Rainbow, WallpaperPaletteStyle.RAINBOW.toMaterialKolor())
    assertEquals(PaletteStyle.FruitSalad, WallpaperPaletteStyle.FRUIT_SALAD.toMaterialKolor())
    assertEquals(PaletteStyle.Monochrome, WallpaperPaletteStyle.MONOCHROME.toMaterialKolor())
    assertEquals(PaletteStyle.Fidelity, WallpaperPaletteStyle.FIDELITY.toMaterialKolor())
    assertEquals(PaletteStyle.Content, WallpaperPaletteStyle.CONTENT.toMaterialKolor())
    assertEquals(
      "every protocol enum entry must map to an upstream value",
      WallpaperPaletteStyle.entries.size,
      PaletteStyle.entries.size,
    )
  }
}
