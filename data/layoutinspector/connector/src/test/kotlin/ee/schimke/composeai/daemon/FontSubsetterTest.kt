package ee.schimke.composeai.daemon

import java.awt.Font
import java.io.ByteArrayInputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSubsetterTest {

  /** DroidSansMono (Apache-2.0), the smallest catalog face — a real glyf-based TTF to subset. */
  private fun fixtureFont(): ByteArray =
    javaClass.getResourceAsStream("/fonts/DroidSansMono.ttf")!!.use { it.readBytes() }

  private fun codePoints(text: String): Set<Int> = text.codePoints().toArray().toSet()

  @Test
  fun subsetShrinksTheFaceToAFractionYetStillRendersTheUsedGlyphs() {
    val full = fixtureFont()
    val subset = FontSubsetter.subset(full, codePoints("Filled card"))!!
    // The 108 KB face collapses to a few KB once only the drawn glyphs (and no layout/hinting
    // tables) are kept — the whole point of the embed-without-bloat path.
    assertTrue(
      "subset (${subset.size} B) must be far smaller than full (${full.size} B)",
      subset.size < full.size / 8,
    )
    // Still a valid font that can draw every requested glyph — the outlines are untouched.
    val font = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(subset))
    for (c in "Filled card") {
      if (c == ' ') continue
      assertTrue("subset must still display '$c'", font.canDisplay(c))
    }
  }

  @Test
  fun subsetOnlyCarriesTheRequestedGlyphsSoADifferentCharsetIsAbsent() {
    // A subset for "AB" must not be able to draw a CJK glyph the face never needed here.
    val subset = FontSubsetter.subset(fixtureFont(), codePoints("AB"))!!
    val font = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(subset))
    assertTrue(font.canDisplay('A'))
    assertTrue("unused glyph must be dropped", !font.canDisplay('中'))
  }

  @Test
  fun subsetIsBestEffortAndReturnsNullRatherThanThrowing() {
    assertNull("empty font bytes", FontSubsetter.subset(ByteArray(0), codePoints("A")))
    assertNull("no code points requested", FontSubsetter.subset(fixtureFont(), emptySet()))
    assertNull(
      "unparseable bytes",
      FontSubsetter.subset(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), codePoints("A")),
    )
  }
}
