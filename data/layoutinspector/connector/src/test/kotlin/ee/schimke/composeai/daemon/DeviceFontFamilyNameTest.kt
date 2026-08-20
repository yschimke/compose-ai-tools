package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.fonts.SystemFontFamilies
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device-family route into a resolved face's name (issue #4327).
 *
 * `WearThemeTokenCaptureTest` proves this against a real Wear render, which is the claim that
 * matters; this pins the two *reflection contracts* that render can only exercise one of. Compose's
 * `DeviceFontFamilyNameFont` is a private class, so the field read is the fast path and the printed
 * form is the fallback for a build where the field isn't reachable — and only a fake can be built
 * with one shape and not the other.
 */
class DeviceFontFamilyNameTest {

  /** The field shape: a private `familyName` holding the slug, as Compose declares it. */
  private class FieldFont(@Suppress("unused") private val familyName: String) {
    override fun toString(): String = "Font(unrelated)"
  }

  /** The printed shape: no reachable field, the slug only in `toString()`. */
  private class PrintedFont(private val slug: String) {
    override fun toString(): String = "Font(familyName=\"$slug\", weight=FontWeight(weight=500))"
  }

  private class OtherFont {
    override fun toString(): String = "Font(resId=2130837504)"
  }

  @Test
  fun `reads the family from the declared field`() {
    assertEquals("Roboto Flex", deviceFontFamilyName(FieldFont("roboto-flex")))
  }

  @Test
  fun `falls back to the printed form`() {
    assertEquals("Google Sans Flex", deviceFontFamilyName(PrintedFont("google-sans-flex")))
  }

  /**
   * A slug the alias table doesn't cover is reported as itself. Reverse-slugifying would invent a
   * display name the family may not have, and the render fell back to the platform's own face
   * anyway — the slug is the honest report.
   */
  @Test
  fun `an unmapped slug passes through unchanged`() {
    assertEquals("some-vendor-face", deviceFontFamilyName(PrintedFont("some-vendor-face")))
  }

  @Test
  fun `any other font shape names no device family`() {
    assertNull(deviceFontFamilyName(OtherFont()))
  }

  /**
   * A family the renderer failed to seed was NOT drawn — the platform fell back to Roboto — so
   * naming it `Roboto Flex` would report a typeface nothing rendered and hide that fallback from
   * the very comparison meant to catch it.
   */
  @Test
  fun `an unseeded family reports the slug, not the face it did not draw`() {
    SystemFontFamilies.recordSeeding(attempted = listOf("roboto-flex"), seeded = emptyList())
    assertEquals("roboto-flex", deviceFontFamilyName(FieldFont("roboto-flex")))

    // A later pass with a warm cache clears it again.
    SystemFontFamilies.recordSeeding(
      attempted = listOf("roboto-flex"),
      seeded = listOf("roboto-flex"),
    )
    assertEquals("Roboto Flex", deviceFontFamilyName(FieldFont("roboto-flex")))
  }

  @After
  fun resetSeeding() {
    SystemFontFamilies.recordSeeding(
      attempted = SystemFontFamilies.DISPLAY_NAMES.keys,
      seeded = SystemFontFamilies.DISPLAY_NAMES.keys,
    )
  }
}
