package ee.schimke.composeai.daemon

import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The pure rules behind the view player's downloadable-font resolver: which family names opt into a
 * Google Fonts fetch, how a document's axis tag/value arrays become the settings string
 * `Typeface.Builder` takes, and which *file* those settings are applied to.
 *
 * All of it is plain string and file-selection work on purpose — the parts that need a real
 * `Typeface` (and so a Robolectric sandbox) are the platform's, not ours, and this is where a wrong
 * answer would be invisible: a mis-paired axis renders a real face at the wrong instance, and axes
 * applied to a *baked* file render every value as the same face with nothing to say so. Both look
 * like a rendering quirk rather than a bug.
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

  @Test
  fun `a document naming no weight axis still draws at the paint's weight`() {
    // The static file the base typeface comes from encodes its weight; the variable file defaults
    // to 400 and has to be told, or a `wdth` run at weight 700 comes back regular.
    assertEquals(
      "'wdth' 25.0,'wght' 700",
      RcGoogleFontTypefaceResolver.withWeightAxis("'wdth' 25.0", 700),
    )
  }

  @Test
  fun `a document's own weight axis wins over the paint's weight`() {
    // Restating the paint weight over the document's would flatten the very ramp this path draws.
    assertEquals(
      "'wght' 100.0",
      RcGoogleFontTypefaceResolver.withWeightAxis("'wght' 100.0", 400),
    )
    assertEquals(
      "'wght' 1000.0,'wdth' 151.0",
      RcGoogleFontTypefaceResolver.withWeightAxis("'wght' 1000.0,'wdth' 151.0", 400),
    )
  }

  @Test
  fun `the variable file is fetched once per family, not once per weight`() {
    // A `wght` ramp asks for four instances of one ~1.7 MB file; probing per weight would fetch it
    // four times. Keyed by (family, italic) because one variable file serves every weight.
    val variable = File("robotoflex-variable.ttf")
    val requests = mutableListOf<Pair<String, Boolean>>()
    val files = GoogleVariableFiles(FakeGoogleFonts(variable) { requests += it })

    assertSame(variable, files.fileFor(GoogleFontKey("Roboto Flex", 100, false)))
    assertSame(variable, files.fileFor(GoogleFontKey("Roboto Flex", 700, false)))
    assertSame(variable, files.fileFor(GoogleFontKey("Roboto Flex", 1000, false)))
    files.fileFor(GoogleFontKey("Roboto Flex", 400, true))

    assertEquals(listOf("Roboto Flex" to false, "Roboto Flex" to true), requests)
  }

  @Test
  fun `a family with no variable file is asked once and then remembered`() {
    // Lobster Two is static-only: a miss, not an error. Re-asking per text op would re-probe the
    // network on every run of a document that draws it.
    val requests = mutableListOf<Pair<String, Boolean>>()
    val files = GoogleVariableFiles(FakeGoogleFonts(variable = null) { requests += it })

    assertNull(files.fileFor(GoogleFontKey("Lobster Two", 400, false)))
    assertNull(files.fileFor(GoogleFontKey("Lobster Two", 700, false)))

    assertEquals(listOf("Lobster Two" to false), requests)
  }

  @Test
  fun `a source that throws is a miss, not a failed render`() {
    val files =
      GoogleVariableFiles(
        object : GoogleFontSource {
          override fun load(key: GoogleFontKey): File? = null

          override fun loadVariable(family: String, italic: Boolean): File =
            throw IllegalStateException("cache directory vanished")
        }
      )

    assertNull(files.fileFor(GoogleFontKey("Roboto Flex", 400, false)))
  }

  private class FakeGoogleFonts(
    private val variable: File?,
    private val onRequest: (Pair<String, Boolean>) -> Unit,
  ) : GoogleFontSource {
    override fun load(key: GoogleFontKey): File? = null

    override fun loadVariable(family: String, italic: Boolean): File? {
      onRequest(family to italic)
      return variable
    }
  }
}
