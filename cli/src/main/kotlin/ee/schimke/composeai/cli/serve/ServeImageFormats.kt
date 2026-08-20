package ee.schimke.composeai.cli.serve

/**
 * A **raster image format** the serve host will ingest for the image lane ([ServeImageStore]).
 *
 * The same registry shape as [ServeDocFormats], minus the player: an image is not *played*, it is
 * handed back with its content type and drawn by whatever renders the page it was pasted into (a PR
 * body, a chat message, an `<img>`). So the per-format facts are just the sniff, the canonical
 * extension, and the wire content type — and adding a format stays one entry here rather than a
 * branch in the store or the route.
 *
 * **Raster only, deliberately.** Every entry decodes to pixels and nothing else. SVG is the
 * conspicuous omission and it is not an oversight: an SVG is a document that can carry `<script>`
 * and external references, so serving one from this origin would hand an uploader active content on
 * the host's own name — exactly what [ServeDocStore]'s "not a general file drop" rule exists to
 * prevent. A vector surface that needs sharing belongs in the document lane behind a real format
 * entry, not here.
 *
 * @param id stable wire id (`png`, `gif`, …) — appears in the upload response.
 * @param label human name.
 * @param extension canonical file extension, **including the dot**. The permalink ends in it, so
 *   that a client which decides by suffix (a markdown renderer, an image proxy, a saved file) sees
 *   the same format the content type declares.
 * @param contentType what `GET /i/<id><extension>` responds with.
 * @param detect content sniff — true when the bytes really are this format. Runs before anything
 *   else touches the upload, so a mislabelled or hostile file is rejected on shape, not on name.
 * @param size intrinsic pixel dimensions, best-effort: null when the header is truncated or the
 *   variant isn't one this reader walks. Only ever displayed and reported, never a gate.
 */
data class ServeImageFormat(
  val id: String,
  val label: String,
  val extension: String,
  val contentType: String,
  val detect: (ByteArray) -> Boolean,
  val size: (ByteArray) -> ServeDocSize?,
)

/** The known image formats, and the sniffing that maps uploaded bytes onto one. */
object ServeImageFormats {

  /**
   * PNG — what every `compose-preview` render lane emits, so it is the format this lane exists for.
   * An animated PNG (`acTL` before the first `IDAT`) is still a PNG on the wire and is served as
   * `image/apng`, the same distinction [ServeHttpServer]'s motion route draws: both decode, but
   * only the second tells a browser there is more here than one frame.
   */
  val PNG =
    ServeImageFormat(
      id = "png",
      label = "PNG",
      extension = ".png",
      contentType = "image/png",
      detect = ::isPng,
      size = ::pngSize,
    )

  /** GIF — the other thing the motion lane hands out, and what a chat client animates for free. */
  val GIF =
    ServeImageFormat(
      id = "gif",
      label = "GIF",
      extension = ".gif",
      contentType = "image/gif",
      detect = ::isGif,
      size = ::gifSize,
    )

  /** WebP — smaller than either for the same capture; all three variants (lossy, lossless, X). */
  val WEBP =
    ServeImageFormat(
      id = "webp",
      label = "WebP",
      extension = ".webp",
      contentType = "image/webp",
      detect = ::isWebp,
      size = ::webpSize,
    )

  /** JPEG — not a render output, but what a hand-taken screenshot of a device usually is. */
  val JPEG =
    ServeImageFormat(
      id = "jpeg",
      label = "JPEG",
      extension = ".jpg",
      contentType = "image/jpeg",
      detect = ::isJpeg,
      size = ::jpegSize,
    )

  /** Every known format, in the order the upload path sniffs them. */
  val ALL: List<ServeImageFormat> = listOf(PNG, GIF, WEBP, JPEG)

  fun byId(id: String): ServeImageFormat? = ALL.firstOrNull { it.id == id }

  /**
   * The format [bytes] are, or null when they're not a known image. Content-sniffed — the uploaded
   * filename never decides the format.
   */
  fun detect(bytes: ByteArray): ServeImageFormat? = ALL.firstOrNull { it.detect(bytes) }

  /** Human list of what an upload may be, for the error a rejected upload gets back. */
  fun knownSummary(): String = ALL.joinToString(", ") { "${it.label} (${it.extension})" }

  /**
   * The content type to answer a `GET` for [bytes] of [format] with. Everything is [format]'s
   * declared type except an animated PNG, which is `image/apng` — a property of the *document*, not
   * of the format, which is why it is decided here and not by a second registry entry.
   */
  fun contentTypeOf(format: ServeImageFormat, bytes: ByteArray): String =
    if (format == PNG && isAnimatedPng(bytes)) "image/apng" else format.contentType

  // ---- PNG ---------------------------------------------------------------------------------

  private val PNG_SIGNATURE =
    byteArrayOf(
      0x89.toByte(),
      'P'.code.toByte(),
      'N'.code.toByte(),
      'G'.code.toByte(),
      0x0D,
      0x0A,
      0x1A,
      0x0A,
    )

  private fun isPng(bytes: ByteArray): Boolean {
    // Signature + the IHDR chunk that must follow it — a bare signature with nothing behind it is
    // not an image, and the size reader below would walk off the end of it.
    if (bytes.size < 24) return false
    if (!bytes.startsWith(PNG_SIGNATURE)) return false
    return bytes.ascii(12, 4) == "IHDR"
  }

  private fun pngSize(bytes: ByteArray): ServeDocSize? {
    if (!isPng(bytes)) return null
    val width = bytes.intAt(16)
    val height = bytes.intAt(20)
    return if (width > 0 && height > 0) ServeDocSize(width, height) else null
  }

  /**
   * Whether this PNG is an APNG: an `acTL` chunk ahead of the first `IDAT`. Walks the chunk table
   * rather than scanning for the four bytes anywhere, so pixel data that happens to spell `acTL`
   * can't promote a still image. Any malformed length stops the walk and answers "still".
   */
  private fun isAnimatedPng(bytes: ByteArray): Boolean {
    if (!isPng(bytes)) return false
    var offset = 8
    while (offset + 8 <= bytes.size) {
      val length = bytes.intAt(offset)
      if (length < 0) return false
      when (bytes.ascii(offset + 4, 4)) {
        "acTL" -> return true
        // Frame data never precedes the animation control chunk, so reaching it settles the
        // question without reading the (large) rest of the file.
        "IDAT",
        "IEND" -> return false
      }
      // length + the 4-byte type + the 4-byte CRC, guarded against an overflowing declared length.
      val next = offset.toLong() + 12L + length.toLong()
      if (next > bytes.size) return false
      offset = next.toInt()
    }
    return false
  }

  // ---- GIF ---------------------------------------------------------------------------------

  private fun isGif(bytes: ByteArray): Boolean {
    if (bytes.size < 10) return false
    val header = bytes.ascii(0, 6)
    return header == "GIF87a" || header == "GIF89a"
  }

  private fun gifSize(bytes: ByteArray): ServeDocSize? {
    if (!isGif(bytes)) return null
    // The logical screen descriptor is little-endian, unlike every other header here.
    val width = bytes.shortLE(6)
    val height = bytes.shortLE(8)
    return if (width > 0 && height > 0) ServeDocSize(width, height) else null
  }

  // ---- WebP --------------------------------------------------------------------------------

  private fun isWebp(bytes: ByteArray): Boolean =
    bytes.size >= 16 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"

  /**
   * WebP keeps its dimensions in a different place in each of its three variants, so the chunk
   * label after the `WEBP` tag decides which reader runs. An unknown future variant yields null
   * rather than a guess.
   */
  private fun webpSize(bytes: ByteArray): ServeDocSize? {
    if (!isWebp(bytes)) return null
    return when (bytes.ascii(12, 4)) {
      // Lossy: a VP8 key frame's 3-byte start code, then 14-bit width and height.
      "VP8 " ->
        if (bytes.size < 30) null
        else ServeDocSize(bytes.shortLE(26) and 0x3FFF, bytes.shortLE(28) and 0x3FFF)
      // Lossless: 14-bit width and height packed into the 4 bytes after the signature byte, minus
      // one each.
      "VP8L" ->
        if (bytes.size < 25) null
        else {
          val packed = bytes.intLE(21)
          ServeDocSize((packed and 0x3FFF) + 1, ((packed shr 14) and 0x3FFF) + 1)
        }
      // Extended: 24-bit canvas width and height, minus one each.
      "VP8X" ->
        if (bytes.size < 30) null else ServeDocSize(bytes.int24LE(24) + 1, bytes.int24LE(27) + 1)
      else -> null
    }?.takeIf { it.width > 0 && it.height > 0 }
  }

  // ---- JPEG --------------------------------------------------------------------------------

  private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0xFF.toByte() &&
      bytes[1] == 0xD8.toByte() &&
      bytes[2] == 0xFF.toByte()

  /**
   * Walk the marker segments to the start-of-frame that declares the image's size. Total by
   * construction: a truncated or malformed segment table ends the walk with null, since this only
   * feeds a reported number.
   */
  private fun jpegSize(bytes: ByteArray): ServeDocSize? {
    if (!isJpeg(bytes)) return null
    var offset = 2
    while (offset + 4 <= bytes.size) {
      if (bytes[offset] != 0xFF.toByte()) return null
      val marker = bytes[offset + 1].toInt() and 0xFF
      // Padding fill bytes between segments; skip one at a time rather than mis-reading a length.
      if (marker == 0xFF) {
        offset++
        continue
      }
      // Standalone markers carry no length payload at all.
      if (marker == 0xD8 || (marker in 0xD0..0xD9)) {
        offset += 2
        continue
      }
      val length = bytes.shortBE(offset + 2)
      if (length < 2) return null
      // SOF0..SOF15 declare the frame, except the four that are not frame headers (DHT, JPG, DAC,
      // and the restart-interval marker share the range).
      if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        if (offset + 9 > bytes.size) return null
        val height = bytes.shortBE(offset + 5)
        val width = bytes.shortBE(offset + 7)
        return if (width > 0 && height > 0) ServeDocSize(width, height) else null
      }
      // Start of scan: everything past here is entropy-coded data, not segments.
      if (marker == 0xDA) return null
      offset += 2 + length
    }
    return null
  }

  // ---- byte helpers ------------------------------------------------------------------------

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
  }

  private fun ByteArray.ascii(offset: Int, length: Int): String? {
    if (offset < 0 || offset + length > size) return null
    return String(this, offset, length, Charsets.US_ASCII)
  }

  private fun ByteArray.byteAt(offset: Int): Int = this[offset].toInt() and 0xFF

  private fun ByteArray.intAt(offset: Int): Int =
    if (offset + 4 > size) -1
    else
      (byteAt(offset) shl 24) or
        (byteAt(offset + 1) shl 16) or
        (byteAt(offset + 2) shl 8) or
        byteAt(offset + 3)

  private fun ByteArray.shortBE(offset: Int): Int =
    if (offset + 2 > size) -1 else (byteAt(offset) shl 8) or byteAt(offset + 1)

  private fun ByteArray.shortLE(offset: Int): Int =
    if (offset + 2 > size) -1 else (byteAt(offset + 1) shl 8) or byteAt(offset)

  private fun ByteArray.int24LE(offset: Int): Int =
    if (offset + 3 > size) -1
    else (byteAt(offset + 2) shl 16) or (byteAt(offset + 1) shl 8) or byteAt(offset)

  private fun ByteArray.intLE(offset: Int): Int =
    if (offset + 4 > size) -1
    else
      (byteAt(offset + 3) shl 24) or
        (byteAt(offset + 2) shl 16) or
        (byteAt(offset + 1) shl 8) or
        byteAt(offset)
}
