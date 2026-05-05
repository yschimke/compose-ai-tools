package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperColorSchemeTest {

  @Test
  fun light_and_dark_variants_differ_for_the_same_seed() {
    val seed = Color(0xFF3366FF)
    val light = WallpaperColorScheme.from(seed, isDark = false)
    val dark = WallpaperColorScheme.from(seed, isDark = true)
    assertNotEquals(light.primary, dark.primary)
    // Light primary is darker than dark primary in luminance.
    val lightL = light.primary.toHsl().luminance
    val darkL = dark.primary.toHsl().luminance
    assertTrue("light L=$lightL dark L=$darkL", lightL < darkL)
  }

  @Test
  fun seed_drives_primary_hue() {
    val red = WallpaperColorScheme.from(Color(0xFFFF0000), isDark = false).primary.toHsl()
    val blue = WallpaperColorScheme.from(Color(0xFF0000FF), isDark = false).primary.toHsl()
    // A red seed should produce a primary near 0°/360°; blue near 240°.
    assertTrue("red hue=${red.hue}", red.hue < 30f || red.hue > 330f)
    assertTrue("blue hue=${blue.hue}", blue.hue in 200f..280f)
  }

  @Test
  fun secondary_and_tertiary_rotate_off_primary() {
    val scheme = WallpaperColorScheme.from(Color(0xFF3366FF), isDark = false)
    val primary = scheme.primary.toHsl().hue
    val secondary = scheme.secondary.toHsl().hue
    val tertiary = scheme.tertiary.toHsl().hue
    fun rotation(a: Float, b: Float): Float {
      val d = ((b - a) % 360f + 360f) % 360f
      return d
    }
    assertEquals(30f, rotation(primary, secondary), 5f)
    assertEquals(60f, rotation(primary, tertiary), 5f)
  }

  @Test
  fun achromatic_seed_still_produces_visible_chroma() {
    val grey = WallpaperColorScheme.from(Color(0xFF808080), isDark = false).primary.toHsl()
    // Grey would normally collapse to saturation 0. The algorithm clamps the chroma floor so the
    // derived primary is still visibly tinted.
    assertTrue("primary saturation=${grey.saturation}", grey.saturation > 0.2f)
  }

  @Test
  fun hsl_round_trips_through_color_constructor() {
    val original = Color(0xFF80C040)
    val (h, s, l) = original.toHsl()
    val rebuilt = hsl(h, s, l)
    fun close(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.01f
    assertTrue(close(rebuilt.red, original.red))
    assertTrue(close(rebuilt.green, original.green))
    assertTrue(close(rebuilt.blue, original.blue))
  }
}
