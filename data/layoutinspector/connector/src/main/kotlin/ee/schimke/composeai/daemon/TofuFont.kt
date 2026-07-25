package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream

/**
 * Builds a **tofu font**: a real, valid TrueType face whose every mapped character draws the same
 * hollow rectangle — the "missing glyph" box.
 *
 * The `compose/figma-svg` export exists to hand a designer the *actual* typography a render drew.
 * When it can't name the face that was drawn, the honest failure is a visible one: silently emitting
 * the Material default produced a sticker sheet that looked plausible and was wrong — branded
 * previews shipped with `font-family="Roboto, sans-serif"` over PNGs drawn in Orbitron, and nobody
 * noticed for as long as the vectors merely looked like text.
 *
 * Why a font and not "leave the family off": an unmapped character makes the viewer walk its own
 * fallback chain and render the glyph in *some* face, which looks like ordinary text again. To force
 * the box we have to genuinely support the character and draw a box for it — so the cmap maps every
 * code point the export actually emits to one box glyph.
 *
 * The face is built per export over [codePoints] rather than shipped as a resource: mapping the
 * whole BMP to a single glyph needs either a 128 KB `glyphIdArray` or 65k composite glyphs, while a
 * sticker's real character set is typically under a hundred — a few hundred bytes of `cmap`.
 *
 * Emits the ten tables OTS (the browser font sanitiser) requires — `OS/2`, `cmap`, `glyf`, `head`,
 * `hhea`, `hmtx`, `loca`, `maxp`, `name`, `post` — because a face a browser rejects falls back to a
 * real one and defeats the whole point.
 */
object TofuFont {

  /** The family name the `<text>` and the `@font-face` agree on. */
  const val FAMILY: String = "ComposeAI Missing Font"

  private const val UNITS_PER_EM = 1000
  private const val ADVANCE = 700
  private const val ASCENDER = 800
  private const val DESCENDER = -200

  // The box outline, in font units. Outer contour counter-clockwise, inner clockwise, so the
  // non-zero winding rule leaves the middle hollow.
  private const val BOX_X_MIN = 80
  private const val BOX_Y_MIN = 0
  private const val BOX_X_MAX = 620
  private const val BOX_Y_MAX = 700

  /** Number of glyphs: 0 = `.notdef` (empty), 1 = the box every mapped character resolves to. */
  private const val NUM_GLYPHS = 2
  private const val BOX_GLYPH = 1

  /**
   * A TrueType face mapping every BMP code point in [codePoints] to the box glyph.
   *
   * Supplementary-plane code points are dropped: `cmap` format 4 addresses the BMP only, and a
   * character outside it falls back to the viewer's own font (it renders as itself rather than a
   * box). Stickers are overwhelmingly BMP, and adding a format 12 subtable to catch the remainder
   * is not worth the bytes until something needs it.
   */
  fun build(codePoints: Set<Int>): ByteArray {
    val mapped = codePoints.filter { it in 0x0000..0xFFFE }.distinct().sorted()
    val tables =
      linkedMapOf(
        "OS/2" to os2(mapped),
        "cmap" to cmap(mapped),
        "glyf" to glyf(),
        "head" to head(),
        "hhea" to hhea(),
        "hmtx" to hmtx(),
        "loca" to loca(),
        "maxp" to maxp(),
        "name" to name(),
        "post" to post(),
      )
    return assemble(tables)
  }

  /**
   * Packs [tables] into an sfnt: directory sorted by tag, each table 4-byte aligned, per-table
   * checksums, then `head.checkSumAdjustment` patched so the whole-font checksum is consistent.
   * Mirrors the packing `FontSubsetter.stripTables` does when it rebuilds a stripped face.
   */
  private fun assemble(tables: Map<String, ByteArray>): ByteArray {
    val entries = tables.entries.sortedBy { it.key }
    val n = entries.size
    val headerLen = 12 + n * 16
    val offsets = IntArray(n)
    var cursor = headerLen
    for (i in 0 until n) {
      offsets[i] = cursor
      cursor += align4(entries[i].value.size)
    }
    val out = ByteArray(cursor)
    writeInt(out, 0, 0x00010000) // sfnt version
    writeShort(out, 4, n)
    val entrySelector = if (n > 0) 31 - Integer.numberOfLeadingZeros(n) else 0
    val searchRange = (1 shl entrySelector) * 16
    writeShort(out, 6, searchRange)
    writeShort(out, 8, entrySelector)
    writeShort(out, 10, n * 16 - searchRange)

    var headOffset = -1
    for (i in 0 until n) {
      val (tag, data) = entries[i]
      val rec = 12 + i * 16
      System.arraycopy(tag.toByteArray(Charsets.ISO_8859_1), 0, out, rec, 4)
      System.arraycopy(data, 0, out, offsets[i], data.size)
      // `head.checkSumAdjustment` is checksummed as zero, so clear it before summing.
      if (tag == "head") {
        headOffset = offsets[i]
        writeInt(out, headOffset + 8, 0)
      }
      writeInt(out, rec + 4, checksum(out, offsets[i], data.size))
      writeInt(out, rec + 8, offsets[i])
      writeInt(out, rec + 12, data.size)
    }
    if (headOffset >= 0) {
      writeInt(out, headOffset + 8, 0xB1B0AFBA.toInt() - checksum(out, 0, out.size))
    }
    return out
  }

  /** `cmap` with one format-4 subtable, one segment per mapped code point. */
  private fun cmap(codePoints: List<Int>): ByteArray {
    // Every segment is a single character mapping to the box, so `idDelta` alone carries it and no
    // `glyphIdArray` is needed. The mandatory 0xFFFF terminator maps to `.notdef`.
    val starts = codePoints + 0xFFFF
    val deltas = codePoints.map { (BOX_GLYPH - it) and 0xFFFF } + 1
    val segCount = starts.size
    val subtableLen = 16 + segCount * 8
    val out = ByteArray(12 + subtableLen)
    writeShort(out, 0, 0) // cmap version
    writeShort(out, 2, 1) // one encoding record
    writeShort(out, 4, 3) // platform: Windows
    writeShort(out, 6, 1) // encoding: Unicode BMP
    writeInt(out, 8, 12) // subtable offset

    var p = 12
    writeShort(out, p, 4)
    writeShort(out, p + 2, subtableLen)
    writeShort(out, p + 4, 0) // language
    val segCountX2 = segCount * 2
    val sel = if (segCount > 0) 31 - Integer.numberOfLeadingZeros(segCount) else 0
    val range = (1 shl sel) * 2
    writeShort(out, p + 6, segCountX2)
    writeShort(out, p + 8, range)
    writeShort(out, p + 10, sel)
    writeShort(out, p + 12, segCountX2 - range)
    p += 14
    for (c in starts) {
      writeShort(out, p, c)
      p += 2
    }
    writeShort(out, p, 0) // reservedPad
    p += 2
    for (c in starts) {
      writeShort(out, p, c)
      p += 2
    }
    for (d in deltas) {
      writeShort(out, p, d)
      p += 2
    }
    // idRangeOffset: all zero — the delta is the whole mapping.
    return out
  }

  /** `glyf`: an empty `.notdef` followed by the box outline. */
  private fun glyf(): ByteArray {
    val xs = intArrayOf(BOX_X_MIN, BOX_X_MAX, BOX_X_MAX, BOX_X_MIN, 140, 140, 560, 560)
    val ys = intArrayOf(BOX_Y_MIN, BOX_Y_MIN, BOX_Y_MAX, BOX_Y_MAX, 60, 640, 640, 60)
    val out = ByteArrayOutputStream()
    out.writeShort(2) // two contours: outer + inner
    out.writeShort(BOX_X_MIN)
    out.writeShort(BOX_Y_MIN)
    out.writeShort(BOX_X_MAX)
    out.writeShort(BOX_Y_MAX)
    out.writeShort(3) // last point of the outer contour
    out.writeShort(7) // last point of the inner contour
    out.writeShort(0) // no instructions
    repeat(xs.size) { out.write(0x01) } // every point on-curve, 16-bit deltas
    var prev = 0
    for (x in xs) {
      out.writeShort(x - prev)
      prev = x
    }
    prev = 0
    for (y in ys) {
      out.writeShort(y - prev)
      prev = y
    }
    return out.toByteArray()
  }

  /** `loca`, short format: `.notdef` is empty, so it and the box share offset 0. */
  private fun loca(): ByteArray {
    val boxLen = glyf().size
    val out = ByteArray(6)
    writeShort(out, 0, 0) // glyph 0 start
    writeShort(out, 2, 0) // glyph 0 end == glyph 1 start (empty .notdef)
    writeShort(out, 4, boxLen / 2) // short loca stores offset/2
    return out
  }

  private fun head(): ByteArray {
    val out = ByteArray(54)
    writeInt(out, 0, 0x00010000) // version
    writeInt(out, 4, 0x00010000) // fontRevision
    writeInt(out, 8, 0) // checkSumAdjustment, patched by assemble()
    writeInt(out, 12, 0x5F0F3CF5) // magicNumber
    writeShort(out, 16, 0x000B) // flags
    writeShort(out, 18, UNITS_PER_EM)
    // created / modified stay zero (LONGDATETIME) so the bytes are reproducible across runs.
    writeShort(out, 36, BOX_X_MIN)
    writeShort(out, 38, BOX_Y_MIN)
    writeShort(out, 40, BOX_X_MAX)
    writeShort(out, 42, BOX_Y_MAX)
    writeShort(out, 44, 0) // macStyle
    writeShort(out, 46, 8) // lowestRecPPEM
    writeShort(out, 48, 2) // fontDirectionHint
    writeShort(out, 50, 0) // indexToLocFormat: short
    writeShort(out, 52, 0) // glyphDataFormat
    return out
  }

  private fun hhea(): ByteArray {
    val out = ByteArray(36)
    writeInt(out, 0, 0x00010000)
    writeShort(out, 4, ASCENDER)
    writeShort(out, 6, DESCENDER)
    writeShort(out, 8, 0) // lineGap
    writeShort(out, 10, ADVANCE) // advanceWidthMax
    writeShort(out, 12, BOX_X_MIN) // minLeftSideBearing
    writeShort(out, 14, ADVANCE - BOX_X_MAX) // minRightSideBearing
    writeShort(out, 16, BOX_X_MAX) // xMaxExtent
    writeShort(out, 18, 1) // caretSlopeRise
    writeShort(out, 20, 0) // caretSlopeRun
    writeShort(out, 22, 0) // caretOffset
    writeShort(out, 34, NUM_GLYPHS) // numberOfHMetrics
    return out
  }

  private fun hmtx(): ByteArray {
    val out = ByteArray(NUM_GLYPHS * 4)
    writeShort(out, 0, ADVANCE) // .notdef advance
    writeShort(out, 2, 0) // .notdef lsb
    writeShort(out, 4, ADVANCE) // box advance
    writeShort(out, 6, BOX_X_MIN) // box lsb
    return out
  }

  private fun maxp(): ByteArray {
    val out = ByteArray(32)
    writeInt(out, 0, 0x00010000)
    writeShort(out, 4, NUM_GLYPHS)
    writeShort(out, 6, 8) // maxPoints
    writeShort(out, 8, 2) // maxContours
    writeShort(out, 14, 1) // maxZones
    return out
  }

  /** `name` with the four IDs a sanitiser expects, as Windows/Unicode UTF-16BE. */
  private fun name(): ByteArray {
    val ids = listOf(1 to FAMILY, 2 to "Regular", 4 to FAMILY, 6 to FAMILY.replace(" ", ""))
    val storage = ByteArrayOutputStream()
    val records = ByteArrayOutputStream()
    for ((id, value) in ids) {
      val bytes = value.toByteArray(Charsets.UTF_16BE)
      records.writeShort(3) // platform: Windows
      records.writeShort(1) // encoding: Unicode BMP
      records.writeShort(0x0409) // language: en-US
      records.writeShort(id)
      records.writeShort(bytes.size)
      records.writeShort(storage.size())
      storage.write(bytes)
    }
    val out = ByteArrayOutputStream()
    out.writeShort(0) // format
    out.writeShort(ids.size)
    out.writeShort(6 + ids.size * 12) // storage offset
    out.write(records.toByteArray())
    out.write(storage.toByteArray())
    return out.toByteArray()
  }

  /** `post` version 3.0 — no glyph names, which is what a two-glyph face needs. */
  private fun post(): ByteArray {
    val out = ByteArray(32)
    writeInt(out, 0, 0x00030000)
    writeShort(out, 8, -100) // underlinePosition
    writeShort(out, 10, 50) // underlineThickness
    return out
  }

  private fun os2(codePoints: List<Int>): ByteArray {
    val out = ByteArray(96) // version 4
    writeShort(out, 0, 4)
    writeShort(out, 2, ADVANCE) // xAvgCharWidth
    writeShort(out, 4, 400) // usWeightClass: Regular
    writeShort(out, 6, 5) // usWidthClass: Medium
    writeShort(out, 8, 0) // fsType: installable
    writeShort(out, 10, 650) // ySubscriptXSize
    writeShort(out, 12, 600) // ySubscriptYSize
    writeShort(out, 16, 75) // ySubscriptYOffset
    writeShort(out, 18, 650) // ySuperscriptXSize
    writeShort(out, 20, 600) // ySuperscriptYSize
    writeShort(out, 24, 350) // ySuperscriptYOffset
    writeShort(out, 26, 50) // yStrikeoutSize
    writeShort(out, 28, 300) // yStrikeoutPosition
    System.arraycopy("CAIT".toByteArray(Charsets.ISO_8859_1), 0, out, 58, 4) // achVendID
    writeShort(out, 62, 0x0040) // fsSelection: REGULAR
    writeShort(out, 64, codePoints.firstOrNull() ?: 0) // usFirstCharIndex
    writeShort(out, 66, codePoints.lastOrNull() ?: 0) // usLastCharIndex
    writeShort(out, 68, ASCENDER) // sTypoAscender
    writeShort(out, 70, DESCENDER) // sTypoDescender
    writeShort(out, 74, ASCENDER) // usWinAscent
    writeShort(out, 76, -DESCENDER) // usWinDescent
    writeInt(out, 78, 1) // ulCodePageRange1: Latin-1
    writeShort(out, 86, 500) // sxHeight
    writeShort(out, 88, BOX_Y_MAX) // sCapHeight
    writeShort(out, 92, 32) // usBreakChar
    writeShort(out, 94, 1) // usMaxContext
    return out
  }

  private fun align4(n: Int): Int = (n + 3) and 3.inv()

  /** sfnt table checksum: sum of big-endian uint32 words, zero-padded to a 4-byte boundary. */
  private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
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

  private fun writeShort(out: ByteArray, at: Int, value: Int) {
    out[at] = (value ushr 8).toByte()
    out[at + 1] = value.toByte()
  }

  private fun writeInt(out: ByteArray, at: Int, value: Int) {
    out[at] = (value ushr 24).toByte()
    out[at + 1] = (value ushr 16).toByte()
    out[at + 2] = (value ushr 8).toByte()
    out[at + 3] = value.toByte()
  }

  private fun ByteArrayOutputStream.writeShort(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
  }
}
