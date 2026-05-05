package ee.schimke.composeai.daemon

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Lightweight seed-color → Material 3 [ColorScheme] derivation used by the wallpaper data
 * extension.
 *
 * The algorithm intentionally avoids a Material Color Utilities dependency. Primary, secondary, and
 * tertiary roles are derived from the seed in HSL space (secondary at +30° hue, tertiary at +60°),
 * with light/dark luminance ramps tuned to roughly match the Material 3 tonal palette buckets.
 * Surface and neutral roles fall back to [lightColorScheme] / [darkColorScheme] defaults — clients
 * that need a fully wallpaper-derived neutral palette can layer a `material3Theme` override on top
 * of this one.
 */
object WallpaperColorScheme {
  /**
   * Derive a Material 3 [ColorScheme] from a seed color.
   *
   * @param seed wallpaper seed color (any alpha is dropped — only the hue/saturation matter).
   * @param isDark whether the dark variant should be returned. Surface tokens follow.
   */
  fun from(seed: Color, isDark: Boolean): ColorScheme {
    val (h, s, _) = seed.toHsl()
    // Force a minimum saturation so achromatic seeds (white/black/grey) still produce a visible
    // tint instead of collapsing to the neutral defaults.
    val chroma = s.coerceAtLeast(0.35f)

    return if (isDark) {
      darkColorScheme(
        primary = hsl(h, chroma, 0.78f),
        onPrimary = hsl(h, chroma * 0.5f, 0.18f),
        primaryContainer = hsl(h, chroma * 0.7f, 0.32f),
        onPrimaryContainer = hsl(h, chroma * 0.4f, 0.90f),
        secondary = hsl(h + 30f, chroma * 0.7f, 0.78f),
        onSecondary = hsl(h + 30f, chroma * 0.4f, 0.18f),
        secondaryContainer = hsl(h + 30f, chroma * 0.5f, 0.32f),
        onSecondaryContainer = hsl(h + 30f, chroma * 0.3f, 0.90f),
        tertiary = hsl(h + 60f, chroma * 0.7f, 0.78f),
        onTertiary = hsl(h + 60f, chroma * 0.4f, 0.18f),
        tertiaryContainer = hsl(h + 60f, chroma * 0.5f, 0.32f),
        onTertiaryContainer = hsl(h + 60f, chroma * 0.3f, 0.90f),
      )
    } else {
      lightColorScheme(
        primary = hsl(h, chroma, 0.40f),
        onPrimary = hsl(h, chroma * 0.4f, 0.98f),
        primaryContainer = hsl(h, chroma * 0.6f, 0.86f),
        onPrimaryContainer = hsl(h, chroma * 0.6f, 0.18f),
        secondary = hsl(h + 30f, chroma * 0.7f, 0.42f),
        onSecondary = hsl(h + 30f, chroma * 0.3f, 0.98f),
        secondaryContainer = hsl(h + 30f, chroma * 0.4f, 0.86f),
        onSecondaryContainer = hsl(h + 30f, chroma * 0.5f, 0.18f),
        tertiary = hsl(h + 60f, chroma * 0.7f, 0.42f),
        onTertiary = hsl(h + 60f, chroma * 0.3f, 0.98f),
        tertiaryContainer = hsl(h + 60f, chroma * 0.4f, 0.86f),
        onTertiaryContainer = hsl(h + 60f, chroma * 0.5f, 0.18f),
      )
    }
  }
}

internal data class Hsl(val hue: Float, val saturation: Float, val luminance: Float)

internal fun Color.toHsl(): Hsl {
  val r = red
  val g = green
  val b = blue
  val max = maxOf(r, g, b)
  val min = minOf(r, g, b)
  val l = (max + min) / 2f
  if (abs(max - min) < 1e-6f) return Hsl(0f, 0f, l)
  val d = max - min
  val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
  val h =
    when (max) {
      r -> ((g - b) / d + if (g < b) 6f else 0f)
      g -> ((b - r) / d + 2f)
      else -> ((r - g) / d + 4f)
    } * 60f
  return Hsl(h, s, l)
}

internal fun hsl(hueDegrees: Float, saturation: Float, luminance: Float): Color {
  val h = ((hueDegrees % 360f) + 360f) % 360f / 360f
  val s = saturation.coerceIn(0f, 1f)
  val l = luminance.coerceIn(0f, 1f)
  if (s == 0f) return Color(l, l, l)
  val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
  val p = 2f * l - q
  return Color(
    red = hueToRgb(p, q, h + 1f / 3f),
    green = hueToRgb(p, q, h),
    blue = hueToRgb(p, q, h - 1f / 3f),
  )
}

private fun hueToRgb(p: Float, q: Float, hue: Float): Float {
  val t = ((hue % 1f) + 1f) % 1f
  return when {
    t < 1f / 6f -> p + (q - p) * 6f * t
    t < 1f / 2f -> q
    t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
    else -> p
  }
}

internal fun Color.toHexArgb(): String = "#%08X".format(toArgb())
