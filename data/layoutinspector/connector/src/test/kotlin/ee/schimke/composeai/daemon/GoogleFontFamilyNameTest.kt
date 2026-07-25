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

  @Test
  fun namesAFaceWhoseMethodsCannotBeEnumerated() {
    // The regression that shipped a whole branded sticker sheet as Roboto. `Class.getMethods()`
    // resolves every method's parameter and return types, so a face whose signatures reference a
    // class the current loader can't see makes the *method* route unusable — and gating the whole
    // extraction on it lost the family for every downloadable face in the render. The name is still
    // right there in a field, which is what the font recorder reads.
    class HostileMethods {
      @Suppress("unused") val name: String = "Orbitron"

      @Suppress("unused")
      fun toFontRequest(): Any = throw NoClassDefFoundError("androidx/core/provider/FontRequest")

      override fun toString(): String = "Font(GoogleFont(\"Orbitron\", bestEffort=true))"
    }
    assertEquals("Orbitron", googleFontFamilyName(HostileMethods()))
  }

  @Test
  fun namesAFaceThatOnlyDeclaresItsFamilyInToString() {
    // No `name` field, no `getName()` — only the label. Still nameable, so still not Roboto.
    class OnlyToString {
      override fun toString(): String = "Font(GoogleFont(\"JetBrains Mono\"), weight=400)"
    }
    assertEquals("JetBrains Mono", googleFontFamilyName(OnlyToString()))
  }

  @Test
  fun stillNullForAnUnrelatedFaceWithANameField() {
    // Widening the detection must not start labelling arbitrary Font subtypes as Google fonts.
    class BundledFace {
      @Suppress("unused") val name: String = "Some Bundled Face"

      override fun toString(): String = "ResourceFont(resId=2131296257, weight=400)"
    }
    assertNull(googleFontFamilyName(BundledFace()))
  }

  @Test
  fun extractsTheDisplayNameFromADesktopGoogleFontIdentity() {
    // A vendored desktop face's identity is the GoogleFont label; the figma-svg must name the
    // family
    // ("Orbitron"), not the raw blob, so `?mode=web` can @import it.
    assertEquals(
      "Orbitron",
      googleFontNameFromIdentity(
        "Font(GoogleFont(\"Orbitron\", bestEffort=true), weight=400, style=Normal)"
      ),
    )
    assertEquals(
      "Space Grotesk",
      googleFontNameFromIdentity(
        "Font(GoogleFont(\"Space Grotesk\", bestEffort=true), weight=700, style=Normal)"
      ),
    )
  }

  @Test
  fun nullForANonGoogleFontIdentity() {
    // A plain desktop file path / resource identity must pass through untouched (null ⇒ keep it).
    assertNull(googleFontNameFromIdentity("/usr/share/fonts/truetype/Roboto-Regular.ttf"))
    assertNull(googleFontNameFromIdentity("res/font/2131296257"))
  }
}
