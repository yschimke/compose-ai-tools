package ee.schimke.composeai.renderer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

// A top-level `val` of type `TextStyle` — compiles to a `private static final TextStyle` backing
// field on this file's synthetic `TextStyleValueReflectionProbeTestKt` class, the shape
// `@TypographyCatalog` auto-discovery reflects out of a consumer's compiled code at render time.
// Unlike `Color`, `TextStyle` is an ordinary class, so the field holds the object directly — no
// value-class unboxing — which is exactly the branch `reflectTextStyle` must get right.
val ProbeDisplay: TextStyle = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Medium)

// The same, but declared inside an `object` — a `private static final TextStyle` reached off the
// `INSTANCE` singleton rather than as a plain static. Pins the non-static receiver branch.
object ProbeTypeScale {
  val Caption: TextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
}

/**
 * Sibling of [ColorValueReflectionProbeTest] for the `@TypographyCatalog` path. Pins the two ways a
 * `TextStyle` token can be declared — a file-level `val` (static backing field, `null` receiver) and
 * an `object` member (`INSTANCE` receiver) — so a regression in [CatalogValueReflection.reflectTextStyle]
 * surfaces here as a fast JVM failure instead of deep in the Robolectric render.
 */
class TextStyleValueReflectionProbeTest {

  @Test
  fun `reads a top-level TextStyle backing field`() {
    val style =
      CatalogValueReflection.reflectTextStyle(
        "ee.schimke.composeai.renderer.TextStyleValueReflectionProbeTestKt",
        "ProbeDisplay",
      )
    assertEquals(57f, style.fontSize.value)
    assertEquals(FontWeight.Medium, style.fontWeight)
  }

  @Test
  fun `reads a TextStyle declared inside an object via the INSTANCE receiver`() {
    val style =
      CatalogValueReflection.reflectTextStyle(
        "ee.schimke.composeai.renderer.ProbeTypeScale",
        "Caption",
      )
    assertEquals(11f, style.fontSize.value)
    assertEquals(FontWeight.Normal, style.fontWeight)
  }
}
