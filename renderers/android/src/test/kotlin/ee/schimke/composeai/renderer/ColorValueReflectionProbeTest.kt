package ee.schimke.composeai.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

// Top-level `val`s of type `Color` (an `@JvmInline value class` over `ULong`, itself over `Long`).
// These compile to `private static final long` backing fields on this file's synthetic
// `ColorValueReflectionProbeTestKt` class — the exact shape the catalog auto-discovery will reflect
// out of a consumer's compiled code at render time. `ProbeAlpha` is semi-transparent so the probe
// also proves the alpha byte round-trips (a naive int reinterpretation would lose it).
val ProbeOpaque: Color = Color(0xFF3366CC)
val ProbeAlpha: Color = Color(0x80112233)

/**
 * De-risks the one genuinely tricky part of colour-catalog auto-discovery: reading a top-level
 * `Color` property's *value* by reflection at render time. Because `Color` is a value class, its
 * backing field is the erased `long`, and turning that back into a `Color` needs the synthetic
 * `Color.box-impl(J)` factory rather than any public constructor. This test pins that mechanism
 * (mirrored by `CatalogValueReflection`) so a regression surfaces here as a fast JVM failure instead
 * of deep in the Robolectric render.
 */
class ColorValueReflectionProbeTest {

  private val ownerFqn = "ee.schimke.composeai.renderer.ColorValueReflectionProbeTestKt"

  @Test
  fun `reads a value-class Color backing field and reboxes it`() {
    assertEquals(0xFF3366CC.toInt(), reflectColorArgb(ownerFqn, "ProbeOpaque"))
  }

  @Test
  fun `preserves the alpha byte through the reflect-and-rebox round trip`() {
    assertEquals(0x80112233.toInt(), reflectColorArgb(ownerFqn, "ProbeAlpha"))
  }

  private fun reflectColorArgb(ownerClassFqn: String, member: String): Int {
    val owner = Class.forName(ownerClassFqn)
    val field = owner.getDeclaredField(member).apply { isAccessible = true }
    val rawUlongBits: Long = field.getLong(null)
    val boxImpl = Color::class.java.getDeclaredMethod("box-impl", Long::class.javaPrimitiveType)
    val color = boxImpl.invoke(null, rawUlongBits) as Color
    return color.toArgb()
  }
}
