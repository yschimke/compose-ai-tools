package ee.schimke.composeai.daemon

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle

/**
 * Wallpaper-seed → Material 3 [ColorScheme] derivation, backed by Material Color Utilities (KMP
 * port via `com.materialkolor:material-kolor`).
 *
 * The upstream library implements Google's HCT-based tonal-palette algorithm — the same one
 * Android's wallpaper-derived dynamic theming uses — so the resulting scheme matches what users see
 * when "Themed icons" is on. Style and contrast level pass through to expose the same knobs the
 * Android wallpaper picker exposes.
 */
object WallpaperColorScheme {
  /**
   * Derive a Material 3 [ColorScheme] from a seed color.
   *
   * @param seed wallpaper seed color (alpha is ignored — only hue/saturation matter).
   * @param isDark whether to return the dark variant.
   * @param style palette algorithm. Defaults to [WallpaperPaletteStyle.TONAL_SPOT] — the same
   *   default the Android wallpaper picker uses.
   * @param contrastLevel Material 3 contrast level in `[-1.0, 1.0]`. Defaults to `0.0` (standard).
   */
  fun from(
    seed: Color,
    isDark: Boolean,
    style: WallpaperPaletteStyle = WallpaperPaletteStyle.TONAL_SPOT,
    contrastLevel: Double = 0.0,
  ): ColorScheme =
    dynamicColorScheme(
      seedColor = seed,
      isDark = isDark,
      style = style.toMaterialKolor(),
      contrastLevel = contrastLevel,
    )
}

internal fun WallpaperPaletteStyle.toMaterialKolor(): PaletteStyle =
  when (this) {
    WallpaperPaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    WallpaperPaletteStyle.NEUTRAL -> PaletteStyle.Neutral
    WallpaperPaletteStyle.VIBRANT -> PaletteStyle.Vibrant
    WallpaperPaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
    WallpaperPaletteStyle.RAINBOW -> PaletteStyle.Rainbow
    WallpaperPaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    WallpaperPaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
    WallpaperPaletteStyle.FIDELITY -> PaletteStyle.Fidelity
    WallpaperPaletteStyle.CONTENT -> PaletteStyle.Content
  }

internal fun Color.toHexArgb(): String = "#%08X".format(toArgb())
