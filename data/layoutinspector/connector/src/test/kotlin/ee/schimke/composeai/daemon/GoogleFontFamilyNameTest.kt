package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [googleFontFamilyName] extracts the family name of a downloadable Google-Fonts face so the
 * `compose/figma-svg` export names the branded family (Orbitron / Space Grotesk / JetBrains Mono …)
 * instead of collapsing every downloadable face to the Roboto default. The real face is a
 * `GoogleFontImpl` (an `AndroidFont` out of reach of this platform-agnostic module), so the
 * extraction is structural — a stand-in with the same `toFontRequest()` + `getName()` shape stands
 * in for it here.
 */
class GoogleFontFamilyNameTest {
  /** Mirrors `GoogleFontImpl`'s surface: a `getName()` family name + a `toFontRequest()` marker. */
  @Suppress("unused")
  class FakeGoogleFont(val name: String) {
    fun toFontRequest(): Any = Any()
  }

  @Test
  fun extractsNameFromAGoogleFontShapedFace() {
    assertEquals("Orbitron", googleFontFamilyName(FakeGoogleFont("Orbitron")))
    assertEquals("Space Grotesk", googleFontFamilyName(FakeGoogleFont("Space Grotesk")))
  }

  @Test
  fun nullForAFaceWithoutTheGoogleFontRequestShape() {
    // A `getName()` alone (no `toFontRequest()`) must not be mistaken for a Google font — that
    // would
    // mis-label unrelated Font subtypes.
    class NamedButNotGoogle {
      @Suppress("unused") fun getName(): String = "Roboto"
    }
    assertNull(googleFontFamilyName(NamedButNotGoogle()))
  }

  @Test
  fun nullWhenTheNameIsBlank() {
    assertNull(googleFontFamilyName(FakeGoogleFont("")))
    assertNull(googleFontFamilyName(FakeGoogleFont("   ")))
  }
}
