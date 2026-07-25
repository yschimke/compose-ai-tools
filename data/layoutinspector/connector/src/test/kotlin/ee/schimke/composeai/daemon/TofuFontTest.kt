package ee.schimke.composeai.daemon

import java.awt.Font
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tofu face is only useful if a font engine *accepts* it: a malformed sfnt is silently ignored
 * by browsers (and by Figma), the viewer falls back to a real font, and the export goes back to
 * quietly rendering plausible-but-wrong text — the exact failure the tofu is there to make visible.
 * So these assertions are about the bytes parsing and the characters mapping, not about looks.
 */
class TofuFontTest {

  private fun parse(bytes: ByteArray): Font =
    Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))

  @Test
  fun `builds a face a font engine accepts`() {
    val font = parse(TofuFont.build(FontSubsetter.PRINTABLE_ASCII))
    assertEquals(TofuFont.FAMILY, font.family)
  }

  @Test
  fun `maps every requested code point so nothing falls through to a real font`() {
    // A character the face does not support makes the viewer walk its own fallback chain and draw
    // the real glyph, which looks like ordinary text. Supporting it is what forces the box.
    val font = parse(TofuFont.build(FontSubsetter.PRINTABLE_ASCII))
    val unmapped = FontSubsetter.PRINTABLE_ASCII.filterNot { font.canDisplay(it) }
    assertTrue("every requested code point must be mapped, unmapped=$unmapped", unmapped.isEmpty())
  }

  @Test
  fun `maps non-latin code points too`() {
    val cyrillic = setOf('Д'.code, 'ж'.code)
    val font = parse(TofuFont.build(FontSubsetter.PRINTABLE_ASCII + cyrillic))
    assertTrue(cyrillic.all { font.canDisplay(it) })
  }

  @Test
  fun `does not map characters it was not asked for`() {
    // The cmap is built per export, so an unrequested character stays unmapped rather than
    // silently widening the face.
    val font = parse(TofuFont.build(setOf('A'.code)))
    assertTrue(font.canDisplay('A'.code))
    assertTrue(!font.canDisplay('B'.code))
  }

  @Test
  fun `every mapped character draws the same box glyph`() {
    val font = parse(TofuFont.build(FontSubsetter.PRINTABLE_ASCII))
    val ctx = java.awt.font.FontRenderContext(null, false, false)
    val a = font.createGlyphVector(ctx, "A").getGlyphOutline(0).bounds
    val z = font.createGlyphVector(ctx, "z").getGlyphOutline(0).bounds
    assertEquals("A and z must draw the identical box", a, z)
    assertTrue("the box must have area", a.width > 0 && a.height > 0)
  }

  @Test
  fun `drops supplementary plane code points rather than emitting a malformed cmap`() {
    // cmap format 4 addresses the BMP only; an astral code point must be skipped, not truncated
    // into a bogus segment that could corrupt the table.
    val font = parse(TofuFont.build(setOf('A'.code, 0x1F600)))
    assertTrue(font.canDisplay('A'.code))
    assertTrue(!font.canDisplay(0x1F600))
  }

  @Test
  fun `is small enough to inline in every export`() {
    // The face rides base64 inside each SVG, so a regression that made it kilobytes-per-character
    // would bloat a whole catalog.
    val bytes = TofuFont.build(FontSubsetter.PRINTABLE_ASCII)
    assertTrue("tofu face was ${bytes.size} B", bytes.size < 2_000)
  }

  @Test
  fun `is byte-reproducible for the same code points`() {
    // Exports are diffed; the face must not churn between runs.
    val a = TofuFont.build(FontSubsetter.PRINTABLE_ASCII)
    val b = TofuFont.build(FontSubsetter.PRINTABLE_ASCII.reversed().toSet())
    assertTrue(a.contentEquals(b))
    assertNotEquals(a.toList(), TofuFont.build(setOf('A'.code)).toList())
  }
}
