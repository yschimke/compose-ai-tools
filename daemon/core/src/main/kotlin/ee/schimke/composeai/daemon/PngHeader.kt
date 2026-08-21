package ee.schimke.composeai.daemon

/**
 * Width and height read straight out of a PNG's `IHDR` chunk — the first 24 bytes of the file, no
 * decode.
 *
 * Exists so the live-frame path can label a `streamFrame` with the pixel size it actually carries.
 * The frame bytes are already in memory by then ([JsonRpcServer] hashes them for dedup), so the
 * dimensions cost a couple of shifts; decoding the image to ask a `BufferedImage` the same question
 * would cost more than everything else the frame path does.
 */
internal object PngHeader {

  /** `\x89PNG\r\n\x1a\n` — the 8-byte PNG signature. */
  private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  /**
   * The first chunk of a well-formed PNG is always `IHDR`, at offset 12 (8 signature + 4 length).
   */
  private val IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)

  private const val IHDR_TYPE_OFFSET = 12

  /** Offset of the IHDR chunk's `width` field: 8 signature + 4 length + 4 type. */
  private const val WIDTH_OFFSET = 16

  /** Smallest prefix that can answer the question: through the IHDR height field. */
  private const val MIN_LENGTH = WIDTH_OFFSET + 8

  /**
   * `width to height`, or null when [bytes] is absent, too short, not a PNG, carries something
   * other than `IHDR` as its first chunk, or declares a nonsensical size. Null means "don't claim a
   * size" — every caller has a zero/unknown branch already, and a wrong size is worse than an
   * absent one. The `IHDR` check is what makes that true of a signature-only stub as well: without
   * it, whatever eight bytes follow read as a plausible width and height.
   */
  fun dimensions(bytes: ByteArray?): Pair<Int, Int>? {
    if (bytes == null || bytes.size < MIN_LENGTH) return null
    for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
    for (i in IHDR.indices) if (bytes[IHDR_TYPE_OFFSET + i] != IHDR[i]) return null
    val width = readInt(bytes, WIDTH_OFFSET)
    val height = readInt(bytes, WIDTH_OFFSET + 4)
    if (width <= 0 || height <= 0) return null
    return width to height
  }

  private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)
}
