package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TTFSubsetter
import org.apache.pdfbox.io.RandomAccessReadBuffer

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
   * Printable ASCII (U+0020–U+007E): `a–z`, `A–Z`, `0–9`, space, and the common punctuation. Union
   * this into the drawn code points so every Latin sticker asks for the **same** subset — one
   * computation the caller can cache and reuse across the whole catalog — and so a *browser*-based
   * SVG viewer/editor (where the embedded face is the only font) can still show an edited label.
   * (Figma resolves fonts by family **name**, not from the embedded `@font-face`, so it isn't bound
   * by the subset either way — see the note in `resolveFonts`.) Non-ASCII characters a sticker
   * actually draws are added on top, so nothing rendered is lost.
   */
  val PRINTABLE_ASCII: Set<Int> = (0x20..0x7E).toSet()

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
    // Complex scripts (Arabic/Indic/…) and combining marks are shaped at render time from the
    // font's `GSUB`/`GPOS`/`GDEF` tables — which this path strips, and which FontBox anyway copies
    // with pre-subset glyph IDs. Rather than emit a font that would shape wrong, don't subset such
    // text: return null so the caller embeds the full, intact face. Simple scripts (Latin, Greek,
    // Cyrillic, CJK, …) render one glyph per code point with no reordering, so stripping is safe.
    if (requiresShaping(codePoints)) return null
    return runCatching {
      val ttf = TTFParser(true).parse(RandomAccessReadBuffer(fontBytes))
      // TTFSubsetter rebuilds `glyf`/`loca`; a CFF/PostScript-outline `.otf` has no `glyf`, so
      // don't try — return null and let the caller embed the full face instead.
      if (ttf.tableMap["glyf"] == null) return@runCatching null
      val subsetter = TTFSubsetter(ttf)
      subsetter.addAll(codePoints)
      val subset = ByteArrayOutputStream().also { subsetter.writeToStream(it) }.toByteArray()
      // FontBox rebuilds `glyf`/`loca` and copies every other table VERBATIM — including `gvar`,
      // which on a variable font is the overwhelming majority of the file and is indexed by the
      // ORIGINAL glyph ids. Left alone it is both enormous and wrong: Roboto Flex subset to one
      // sticker's 98 glyphs still carried all 1.58 MB of deltas, 96.5% of the emitted face, so the
      // export shipped 4.4 MB of SVG per comparison where 100 KB would do.
      val repacked =
        repairMalformedOs2Table(subset).let { face ->
          subsetGvar(fontBytes, face, subsetter.gidMap)?.let { gvar ->
            repackTables(face, DROPPABLE, mapOf("gvar" to gvar))
          } ?: stripTables(face, DROPPABLE)
        }
      repacked.takeIf { it.size < fontBytes.size }
    }
      .getOrNull()
  }

  /**
   * FontBox can emit an `OS/2` table whose version field declares v4 while the table record still
   * has the v0 length (78 bytes). Strict sfnt parsers then read past the table and reject the face
   * (#3047). When the bytes are internally shorter than their declared version requires, downgrade
   * only the version field to the newest version that actually fits the table. The table contents
   * remain honest, and [stripTables] recomputes the table and whole-font checksums immediately
   * afterwards.
   */
  private fun repairMalformedOs2Table(font: ByteArray): ByteArray {
    val record = tableRecord(font, "OS/2") ?: return font
    if (record.length < OS2_V0_LENGTH) return font
    val declared = ByteBuffer.wrap(font).getShort(record.offset).toInt() and 0xFFFF
    val fitted = os2VersionThatFits(record.length) ?: return font
    if (declared <= fitted) return font
    val repaired = font.copyOf()
    ByteBuffer.wrap(repaired).putShort(record.offset, fitted.toShort())
    return repaired
  }

  /**
   * Rebuilds an sfnt font keeping only the tables whose 4-char tag is **not** in [drop]. Rewrites
   * the table directory (sorted by tag, as the sfnt spec requires), repacks each kept table 4-byte
   * aligned, recomputes each table checksum, and rewrites `head.checkSumAdjustment` so the
   * whole-font checksum stays consistent after tables are removed. Pure byte surgery: the kept
   * tables' contents are copied verbatim.
   */
  private fun stripTables(font: ByteArray, drop: Set<String>): ByteArray =
    repackTables(font, drop, emptyMap())

  /**
   * Repacks [font] without the tables in [drop] and with each tag in [replace] carrying those bytes
   * instead of its own. One packer for both jobs: a replaced table changes length, so the directory
   * offsets, the per-table checksums and `head.checkSumAdjustment` all have to be recomputed
   * exactly as a drop does, and having two of these would mean two places to get sfnt checksums
   * wrong.
   */
  private fun repackTables(
    font: ByteArray,
    drop: Set<String>,
    replace: Map<String, ByteArray>,
  ): ByteArray {
    val input = ByteBuffer.wrap(font)
    val numTables = input.getShort(4).toInt() and 0xFFFF
    val kept = ArrayList<Rec>(numTables)
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      val tag = String(font, rec, 4, Charsets.ISO_8859_1)
      if (tag in drop) continue
      val subst = replace[tag]
      if (subst != null) kept.add(Rec(tag, -1, subst.size))
      else kept.add(Rec(tag, input.getInt(rec + 8), input.getInt(rec + 12)))
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
      val subst = replace[r.tag]
      if (subst != null) System.arraycopy(subst, 0, out, newOffsets[i], subst.size)
      else System.arraycopy(font, r.offset, out, newOffsets[i], r.length)
      // `head.checkSumAdjustment` (bytes 8..11) must be treated as 0 when its own table checksum is
      // computed and the whole-font checksum is taken (sfnt spec), so zero it *before*
      // checksumming.
      if (r.tag == "head" && r.length >= 12) {
        headOutOffset = newOffsets[i]
        ob.putInt(headOutOffset + 8, 0)
      }
      ob.putInt(rec + 4, tableChecksum(out, newOffsets[i], r.length))
      ob.putInt(rec + 8, newOffsets[i])
      ob.putInt(rec + 12, r.length)
    }
    // Now that every table (head included, adjustment=0) is in place, write the real whole-font
    // adjustment: 0xB1B0AFBA minus the sum of the entire file as uint32. Readers recompute head's
    // checksum with this field treated as 0, so writing it here keeps a consistent sfnt checksum.
    if (headOutOffset >= 0) {
      val fontChecksum = tableChecksum(out, 0, out.size)
      ob.putInt(headOutOffset + 8, 0xB1B0AFBA.toInt() - fontChecksum)
    }
    return out
  }

  /**
   * `gvar` rebuilt for a subset face, or null when there is nothing to do (no `gvar`, or it cannot
   * be read).
   *
   * `gvar` holds the per-glyph outline deltas a variable font interpolates between its axes, in an
   * array indexed by GLYPH ID. FontBox's subsetter renumbers glyphs and copies `gvar` across
   * untouched, so every delta afterwards belongs to whichever glyph now holds its old id — wrong,
   * and carrying the whole original table besides. On Roboto Flex that is 1.58 MB of the 1.64 MB
   * emitted face.
   *
   * The rebuild is a re-index, not a re-encode: each retained glyph's variation blob is copied
   * byte-for-byte from the original into its new slot, and the shared-tuple array is copied whole.
   * That is exact because subsetting does not touch outlines — a glyph keeps its points and their
   * order, so the deltas that described it still do. Anything this cannot honour (a `gvar` version
   * it does not know, an axis count disagreeing with `fvar`, a truncated table) returns null and
   * the caller keeps the intact face rather than emitting a plausible-looking wrong one.
   *
   * Long offsets are always written. The short form stores `offset / 2` in a `uint16`, which cannot
   * address a data array past 128 KB, and the saving over a hundred-odd glyphs is a few hundred
   * bytes against the megabyte this removes.
   */
  private fun subsetGvar(
    original: ByteArray,
    subset: ByteArray,
    newToOldGid: Map<Int, Int>,
  ): ByteArray? = runCatching {
    val src = tableRecord(original, "gvar") ?: return@runCatching null
    if (tableRecord(subset, "gvar") == null) return@runCatching null
    val b = ByteBuffer.wrap(original)
    val base = src.offset
    if (b.getShort(base).toInt() != 1) return@runCatching null // majorVersion
    val axisCount = b.getShort(base + 4).toInt() and 0xFFFF
    val sharedTupleCount = b.getShort(base + 6).toInt() and 0xFFFF
    val sharedTuplesOffset = b.getInt(base + 8)
    val oldGlyphCount = b.getShort(base + 12).toInt() and 0xFFFF
    val longOffsets = (b.getShort(base + 14).toInt() and 0x0001) == 1
    val dataArrayOffset = b.getInt(base + 16)
    val offsetsAt = base + GVAR_HEADER_LENGTH

    fun offsetAt(index: Int): Int =
      if (longOffsets) b.getInt(offsetsAt + index * 4)
      else (b.getShort(offsetsAt + index * 2).toInt() and 0xFFFF) * 2

    // `maxp.numGlyphs` of the SUBSET is the authority on how many slots the new table needs: the
    // GID map only lists glyphs that were mapped, and a face can retain an unmapped one.
    val maxp = tableRecord(subset, "maxp") ?: return@runCatching null
    val newGlyphCount = ByteBuffer.wrap(subset).getShort(maxp.offset + 4).toInt() and 0xFFFF
    val oldForNew = HashMap<Int, Int>(newToOldGid.size * 2)
    newToOldGid.forEach { (new, old) -> oldForNew[new] = old }

    val blobs = ArrayList<ByteArray>(newGlyphCount)
    for (newGid in 0 until newGlyphCount) {
      val oldGid = oldForNew[newGid]
      if (oldGid == null || oldGid < 0 || oldGid >= oldGlyphCount) {
        blobs.add(ByteArray(0))
        continue
      }
      val from = offsetAt(oldGid)
      val to = offsetAt(oldGid + 1)
      // A zero-length entry is the encoding for "this glyph does not vary", and is normal.
      if (to <= from) {
        blobs.add(ByteArray(0))
        continue
      }
      blobs.add(original.copyOfRange(base + dataArrayOffset + from, base + dataArrayOffset + to))
    }

    val sharedTuplesLength = sharedTupleCount * axisCount * 2
    val newOffsetsLength = (newGlyphCount + 1) * 4
    val newSharedTuplesOffset = GVAR_HEADER_LENGTH + newOffsetsLength
    val newDataArrayOffset = newSharedTuplesOffset + sharedTuplesLength
    val out = ByteArray(newDataArrayOffset + blobs.sumOf { align4(it.size) })
    val ob = ByteBuffer.wrap(out)
    ob.putShort(0, 1) // majorVersion
    ob.putShort(2, 0) // minorVersion
    ob.putShort(4, axisCount.toShort())
    ob.putShort(6, sharedTupleCount.toShort())
    ob.putInt(8, newSharedTuplesOffset)
    ob.putShort(12, newGlyphCount.toShort())
    ob.putShort(14, 1) // flags: long offsets
    ob.putInt(16, newDataArrayOffset)
    if (sharedTuplesLength > 0) {
      System.arraycopy(
        original,
        base + sharedTuplesOffset,
        out,
        newSharedTuplesOffset,
        sharedTuplesLength,
      )
    }
    var cursor = 0
    for (i in 0 until newGlyphCount) {
      ob.putInt(offsetsAt(i), cursor)
      val blob = blobs[i]
      if (blob.isNotEmpty()) {
        System.arraycopy(blob, 0, out, newDataArrayOffset + cursor, blob.size)
      }
      cursor += align4(blob.size)
    }
    ob.putInt(offsetsAt(newGlyphCount), cursor)
    out
  }
    .getOrNull()

  /** Byte position of the `index`th entry in the rebuilt (always long) offset array. */
  private fun offsetsAt(index: Int): Int = GVAR_HEADER_LENGTH + index * 4

  /** `gvar` header: version, axisCount, sharedTupleCount+offset, glyphCount, flags, data offset. */
  private const val GVAR_HEADER_LENGTH = 20

  private data class Rec(val tag: String, val offset: Int, val length: Int)

  private fun tableRecord(font: ByteArray, tag: String): Rec? {
    if (font.size < 12) return null
    val input = ByteBuffer.wrap(font)
    val numTables = input.getShort(4).toInt() and 0xFFFF
    for (i in 0 until numTables) {
      val rec = 12 + i * 16
      if (rec + 16 > font.size) return null
      val recTag = String(font, rec, 4, Charsets.ISO_8859_1)
      if (recTag == tag) return Rec(recTag, input.getInt(rec + 8), input.getInt(rec + 12))
    }
    return null
  }

  private fun os2VersionThatFits(length: Int): Int? =
    when {
      length >= OS2_V2_LENGTH -> 4
      length >= OS2_V1_LENGTH -> 1
      length >= OS2_V0_LENGTH -> 0
      else -> null
    }

  private const val OS2_V0_LENGTH = 78
  private const val OS2_V1_LENGTH = 86
  private const val OS2_V2_LENGTH = 96

  /**
   * Scripts that lay out one glyph per code point with no reordering, mark stacking, or mandatory
   * ligatures — so a glyf-only subset (with the shaping tables dropped) renders them exactly.
   * Everything outside this set, and any combining mark, is treated as needing shaping.
   */
  private val SIMPLE_SCRIPTS: Set<Character.UnicodeScript> =
    setOf(
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.GREEK,
      Character.UnicodeScript.CYRILLIC,
      Character.UnicodeScript.ARMENIAN,
      Character.UnicodeScript.GEORGIAN,
      Character.UnicodeScript.HAN,
      Character.UnicodeScript.HIRAGANA,
      Character.UnicodeScript.KATAKANA,
      Character.UnicodeScript.HANGUL,
      Character.UnicodeScript.BOPOMOFO,
    )

  /**
   * True when any code point needs complex text shaping (combining mark or a non-simple script).
   */
  private fun requiresShaping(codePoints: Set<Int>): Boolean = codePoints.any { cp ->
    when (Character.getType(cp)) {
      Character.NON_SPACING_MARK.toInt(),
      Character.COMBINING_SPACING_MARK.toInt(),
      Character.ENCLOSING_MARK.toInt() -> true
      else -> runCatching { Character.UnicodeScript.of(cp) }.getOrNull() !in SIMPLE_SCRIPTS
    }
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
