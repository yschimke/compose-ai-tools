package ee.schimke.composeai.data.pseudolocale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocaleDirection] is the single source of truth for "does this tag mirror?" across all four
 * render paths — daemon desktop, daemon Android, the Robolectric plugin path and the desktop batch
 * renderers — so its answers are pinned here rather than re-derived per renderer.
 */
class LocaleDirectionTest {

  @Test
  fun rtlLanguagesMirror() {
    assertTrue(LocaleDirection.isRtl("ar"))
    assertTrue(LocaleDirection.isRtl("he"))
    assertTrue(LocaleDirection.isRtl("fa"))
    assertTrue(LocaleDirection.isRtl("ur"))
    assertTrue(LocaleDirection.isRtl("ps"))
    assertTrue(LocaleDirection.isRtl("dv"))
    assertTrue(LocaleDirection.isRtl("ckb"))
  }

  @Test
  fun legacyIso639CodesMirrorLikeTheirModernSpellings() {
    assertTrue(LocaleDirection.isRtl("iw")) // Hebrew
    assertTrue(LocaleDirection.isRtl("ji")) // Yiddish
    assertTrue(LocaleDirection.isRtl("yi"))
  }

  @Test
  fun ltrLanguagesDoNotMirror() {
    assertFalse(LocaleDirection.isRtl("en"))
    assertFalse(LocaleDirection.isRtl("de"))
    assertFalse(LocaleDirection.isRtl("ja"))
    assertFalse(LocaleDirection.isRtl("az")) // Latin by default; see the Arab-script case below
  }

  @Test
  fun regionAndVariantSubtagsAreIgnored() {
    assertTrue(LocaleDirection.isRtl("ar-EG"))
    assertTrue(LocaleDirection.isRtl("he-IL"))
    assertFalse(LocaleDirection.isRtl("en-GB"))
    // Underscore form, as a `java.util.Locale.toString()` would spell it.
    assertTrue(LocaleDirection.isRtl("ar_EG"))
    // A 4-character *variant* (5-8 alphanumeric is the real rule, but be defensive) must not be
    // read as a script: `de` stays LTR whatever trails it.
    assertFalse(LocaleDirection.isRtl("de-DE-1901"))
  }

  /**
   * The script subtag is the direct answer where the language code is only a proxy for one. A
   * language-only lookup gets both of these backwards.
   */
  @Test
  fun explicitScriptBeatsTheLanguageDefault() {
    // RTL language, LTR script → LTR.
    assertFalse(LocaleDirection.isRtl("ar-Latn"))
    assertFalse(LocaleDirection.isRtl("sd-Deva")) // Sindhi in Devanagari
    assertFalse(LocaleDirection.isRtl("ug-Cyrl")) // Uyghur in Cyrillic
    assertFalse(LocaleDirection.isRtl("ar-Latn-EG")) // …with a region after the script

    // LTR language, RTL script → RTL.
    assertTrue(LocaleDirection.isRtl("az-Arab")) // Azerbaijani in Arabic script
    assertTrue(LocaleDirection.isRtl("pa-Arab")) // Shahmukhi Punjabi
    assertTrue(LocaleDirection.isRtl("ks-Arab"))
    assertTrue(LocaleDirection.isRtl("ff-Adlm")) // Fula in Adlam
    assertTrue(LocaleDirection.isRtl("ar-Arab-EG")) // agreeing script is still RTL

    // Case-insensitive, as BCP-47 tags are.
    assertTrue(LocaleDirection.isRtl("AZ-ARAB"))
    assertFalse(LocaleDirection.isRtl("AR-LATN"))
  }

  @Test
  fun blankAndNullTagsDoNotMirror() {
    assertFalse(LocaleDirection.isRtl(null))
    assertFalse(LocaleDirection.isRtl(""))
    assertFalse(LocaleDirection.isRtl("   "))
  }
}
