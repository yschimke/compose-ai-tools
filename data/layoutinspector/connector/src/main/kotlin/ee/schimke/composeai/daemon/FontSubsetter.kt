package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TTFSubsetter

/**
 * Shrinks an embedded TrueType/OpenType face to just the glyphs the `compose/figma-svg` export
 * actually draws, so the **exact** typeface the render loaded can ride along in the SVG at a few KB
 * instead of the full multi-hundred-KB font file.
 *
 * Two steps, both pure-JVM (FontBox), so it works on either backend and unit-tests without a
 * device:
 * 1. **Subset** — [TTFSubsetter] keeps only the requested code points' glyphs (plus `.notdef` and
 *    the composite parts they reference) and rebuilds `glyf`/`loca`/`cmap`/`hmtx`.
 * 2. **Strip layout/hinting tables** — FontBox copies `GPOS`/`GSUB`/`GDEF`/`kern` (pair kerning,
 *    ligatures) and the hinting program (`fpgm`/`prep`/`cvt`/`gasp`) verbatim, and for a UI font
 *    `GPOS` alone is often 60–70 KB — dwarfing the ~2 KB of actual outlines. Static SVG `<text>` is
 *    laid out glyph-by-glyph at a fixed size, so none of these tables affect the rendered result;
 *    dropping them takes a subset Roboto from ~80 KB to ~3 KB. The `glyf` outlines are untouched,
 *    so the shapes stay pixel-identical to the render.
 *
 * Best-effort: any parse/subset failure returns null so the caller falls back to embedding the full
 * bytes (or a named-family reference) rather than dropping the face.
 */
object FontSubsetter {

  /**
   * sfnt tables safe to drop for static, pre-shaped SVG text: OpenType layout + legacy kerning
   * (positioning/substitution we don't apply), the TrueType hinting program (irrelevant at the
   * SVG's fixed raster size), and device/metric hint tables. Everything else FontBox emits —
   * `glyf`, `loca`, `cmap`, `head`, `hhea`, `hmtx`, `maxp`, `name`, `post`, `OS/2` — is kept.
   */
  private val DROPPABLE: Set<String> =
    setOf(
      "GPOS",
      "GSUB",
      "GDEF",
      "BASE",
      "JSTF",
      "MATH", // OpenType layout
      "kern", // legacy pair kerning
      "fpgm",
      "prep",
      "cvt ",
      "gasp", // TrueType hinting
      "hdmx",
      "LTSH",
      "VDMX",
      "PCLT",
      "DSIG", // device metrics / signature
    )

  /**
   * Returns [fontBytes] subset to [codePoints] with layout/hinting tables stripped, or null when
   * the face can't be parsed/subset, no code points were requested, or the result didn't come out
   * smaller than the input (so the caller never trades exactness for a larger blob).
   */
  fun subset(fontBytes: ByteArray, codePoints: Set<Int>): ByteArray? {
    if (fontBytes.isEmpty() || codePoints.isEmpty()) return null
    return runCatching {
        val ttf = TTFParser(true).parse(ByteArrayInputStream(fontBytes))
        // TTFSubsetter rebuilds `glyf`/`loca`; a CFF/PostScript-outline `.otf` has no `glyf`, so
        // don't try — return null and let the caller embed the full face instead.
        if (ttf.tableMap["glyf"] == null) return@runCatching null
        val subsetter = TTFSubsetter(ttf)
        subsetter.addAll(codePoints)
        val subset = ByteArrayOutputStream().also { subsetter.writeToStream(it) }.toByteArray()
        val stripped = stripTables(subset, DROPPABLE)
        stripped.takeIf { it.size < fontBytes.size }
      }
      .getOrNull()
  }

  /**
   * Rebuilds an sfnt font keeping only the tables whose 4-char tag is **not** in [drop]. Rewrites
   * the table directory (sorted by tag, as the sfnt spec requires), repacks each kept table 4-byte
   * aligned, recomputes each table checksum, and zeroes `head.checkSumAdjustment` (the whole-file
   * checksum the stored value no longer matches after tables are removed — zero is the value tools
   * emit and readers accept). Pure byte surgery: the kept tables' contents are copied verbatim.
   */
  private fun stripTables(font: ByteArray, drop: Set<String>): ByteArray {
    val input = ByteBuffer.wrap(font)
    val numTables = input.getShort(4).toInt() and 0xFFFF
    data class Rec(val tag: String, val offset: Int, val length: Int)
    val kept = ArrayList<Rec>(numTables)
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      val tag = String(font, rec, 4, Charsets.ISO_8859_1)
      if (tag in drop) continue
      kept.add(Rec(tag, input.getInt(rec + 8), input.getInt(rec + 12)))
    }
    kept.sortBy { it.tag }

    val n = kept.size
    val headerLen = 12 + n * 16
    val newOffsets = IntArray(n)
    var cursor = headerLen
    for (i in 0 until n) {
      newOffsets[i] = cursor
      cursor += align4(kept[i].length)
    }
    val out = ByteArray(cursor)
    val ob = ByteBuffer.wrap(out)
    // Offset table.
    ob.putInt(0, input.getInt(0)) // sfnt version (0x00010000 / 'OTTO' / 'true')
    ob.putShort(4, n.toShort())
    val entrySelector = if (n > 0) (31 - Integer.numberOfLeadingZeros(n)) else 0
    val searchRange = (1 shl entrySelector) * 16
    ob.putShort(6, searchRange.toShort())
    ob.putShort(8, entrySelector.toShort())
    ob.putShort(10, (n * 16 - searchRange).toShort())
    // Records + table data.
    var headOutOffset = -1
    for (i in 0 until n) {
      val r = kept[i]
      val rec = 12 + i * 16
      System.arraycopy(r.tag.toByteArray(Charsets.ISO_8859_1), 0, out, rec, 4)
      System.arraycopy(font, r.offset, out, newOffsets[i], r.length)
      if (r.tag == "head") headOutOffset = newOffsets[i]
      ob.putInt(rec + 4, tableChecksum(out, newOffsets[i], r.length))
      ob.putInt(rec + 8, newOffsets[i])
      ob.putInt(rec + 12, r.length)
    }
    // The stored checkSumAdjustment no longer matches once tables are removed; zero it.
    if (headOutOffset >= 0 && headOutOffset + 12 <= out.size) ob.putInt(headOutOffset + 8, 0)
    return out
  }

  private fun align4(n: Int): Int = (n + 3) and 3.inv()

  /** sfnt table checksum: sum of the table's big-endian uint32 words, zero-padded to 4 bytes. */
  private fun tableChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var p = offset
    val end = offset + length
    while (p < end) {
      var word = 0
      for (b in 0 until 4) {
        word = (word shl 8) or (if (p + b < end) (data[p + b].toInt() and 0xFF) else 0)
      }
      sum += word
      p += 4
    }
    return sum
  }
}
