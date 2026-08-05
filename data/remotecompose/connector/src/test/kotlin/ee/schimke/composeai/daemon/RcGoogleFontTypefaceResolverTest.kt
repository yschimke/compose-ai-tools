package ee.schimke.composeai.daemon

import ee.schimke.composeai.fonts.google.GoogleFontKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure rules behind the view player's downloadable-font resolver: which family names opt
 * into a Google Fonts fetch, and how a document's axis tag/value arrays become the settings string
 * `Typeface.Builder` takes.
 *
 * Both are plain string work on purpose — the parts that need a real `Typeface` (and so a
 * Robolectric sandbox) are the platform's, not ours, and this is where a wrong answer would be
 * invisible: a mis-paired axis renders a real face at the wrong instance, which looks like a
 * rendering quirk rather than a bug.
 */
class RcGoogleFontTypefaceResolverTest {

  @Test
  fun `only a google-prefixed family opts into a fetch`() {
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey(null, 400, false))
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey("Orbitron", 400, false))
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey("device:Roboto", 400, false))
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey("sans-serif", 400, false))
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey("google:", 400, false))
    assertNull(RcGoogleFontTypefaceResolver.googleFontKey("google:   ", 400, false))
  }

  @Test
  fun `the prefix is stripped and the request carries weight and slant`() {
    assertEquals(
      GoogleFontKey("Orbitron", 500, true),
      RcGoogleFontTypefaceResolver.googleFontKey("google:Orbitron", 500, true),
    )
    assertEquals(
      GoogleFontKey("Space Grotesk", 400, false),
      RcGoogleFontTypefaceResolver.googleFontKey("  google:Space Grotesk  ", 400, false),
    )
    assertEquals(
      GoogleFontKey("Lobster Two", 700, false),
      RcGoogleFontTypefaceResolver.googleFontKey("GOOGLE:Lobster Two", 700, false),
    )
  }

  @Test
  fun `axis tags and values become the platform settings string`() {
    assertEquals(
      "'wght' 700.0,'wdth' 25.0",
      RcGoogleFontTypefaceResolver.variationSettings(
        arrayOf("wght", "wdth"),
        floatArrayOf(700f, 25f),
      ),
    )
  }

  @Test
  fun `an axis missing either half is dropped rather than mis-paired`() {
    assertNull(RcGoogleFontTypefaceResolver.variationSettings(null, floatArrayOf(700f)))
    assertNull(RcGoogleFontTypefaceResolver.variationSettings(arrayOf("wght"), null))
    assertNull(RcGoogleFontTypefaceResolver.variationSettings(emptyArray(), floatArrayOf()))
    // A tag with no value must not take the next axis's value.
    assertEquals(
      "'wght' 700.0",
      RcGoogleFontTypefaceResolver.variationSettings(
        arrayOf("wght", "wdth"),
        floatArrayOf(700f),
      ),
    )
    assertNull(RcGoogleFontTypefaceResolver.variationSettings(arrayOf(" "), floatArrayOf(700f)))
  }
}
