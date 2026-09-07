package ee.schimke.composeai.daemon

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `gvar` — the per-glyph outline deltas a variable font interpolates between its axes — is indexed
 * by GLYPH ID, and FontBox's subsetter renumbers glyphs while copying every table it does not
 * rebuild across verbatim. So a subset variable face used to carry the *whole* original `gvar`,
 * both enormous and indexed for the wrong glyphs.
 *
 * The size is not a nicety here. `remote-m3` draws in Roboto Flex, whose `gvar` is 88.6% of the
 * file, so every one of the catalog's 474 comparison SVGs embedded 1.58 MB of deltas for glyphs it
 * does not draw — 4.4 MB per SVG, twice over, once per weight.
 */
class FontSubsetterVariableTest {

  private fun fixture(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/fonts/$name")).use { it.readBytes() }

  /** Roboto Flex cut to Latin, variations intact — a real variable face small enough to commit. */
  private val variable: ByteArray
    get() = fixture("roboto-flex-latin.ttf")

  private fun tableLength(font: ByteArray, tag: String): Int? {
    val b = ByteBuffer.wrap(font)
    val numTables = b.getShort(4).toInt() and 0xFFFF
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      if (String(font, rec, 4, Charsets.ISO_8859_1) == tag) return b.getInt(rec + 12)
    }
    return null
  }

  private fun uint16(font: ByteArray, at: Int) =
    ByteBuffer.wrap(font).getShort(at).toInt() and 0xFFFF

  private fun tableOffset(font: ByteArray, tag: String): Int? {
    val b = ByteBuffer.wrap(font)
    val numTables = b.getShort(4).toInt() and 0xFFFF
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      if (String(font, rec, 4, Charsets.ISO_8859_1) == tag) return b.getInt(rec + 8)
    }
    return null
  }

  @Test
  fun `a variable face sheds the deltas of the glyphs it no longer has`() {
    val original = variable
    val subset = checkNotNull(FontSubsetter.subset(original, "Label text".map { it.code }.toSet()))

    val before = checkNotNull(tableLength(original, "gvar"))
    val after = checkNotNull(tableLength(subset, "gvar"))
    assertTrue(
      "gvar must shrink with the glyph count, was $before now $after",
      after < before / 2,
    )
    assertTrue(
      "the face must shrink overall: ${original.size} -> ${subset.size}",
      subset.size < original.size / 2,
    )
  }

  @Test
  fun `the rebuilt gvar is indexed for the subset, not the original`() {
    val subset = checkNotNull(FontSubsetter.subset(variable, "Label text".map { it.code }.toSet()))

    val maxp = checkNotNull(tableOffset(subset, "maxp"))
    val gvar = checkNotNull(tableOffset(subset, "gvar"))
    // `gvar.glyphCount` disagreeing with `maxp.numGlyphs` is the exact shape of the bug: deltas
    // addressed by the ids they had before the subsetter renumbered them.
    assertEquals(
      "gvar.glyphCount must match maxp.numGlyphs",
      uint16(subset, maxp + 4),
      uint16(subset, gvar + 12),
    )
  }

  @Test
  fun `the axes survive, or the weights the document asks for cannot be drawn`() {
    val subset = checkNotNull(FontSubsetter.subset(variable, "Label text".map { it.code }.toSet()))

    // Dropping fvar/gvar would shrink the face further and silently flatten every weight to the
    // default instance — the wrong-and-plausible failure this whole export path exists to avoid.
    assertTrue("fvar must survive", (tableLength(subset, "fvar") ?: 0) > 0)
    assertTrue("gvar must survive", (tableLength(subset, "gvar") ?: 0) > 0)
  }

  @Test
  fun `a static face is unaffected`() {
    val original = fixture("DroidSansMono.ttf")
    val subset = checkNotNull(FontSubsetter.subset(original, "Label text".map { it.code }.toSet()))

    assertEquals("a static face has no gvar to rebuild", null, tableLength(subset, "gvar"))
    assertTrue("and must still subset", subset.size < original.size)
  }
}
