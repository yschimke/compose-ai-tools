package ee.schimke.composeai.daemon

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Minimal pure-JVM Animated PNG encoder. Used by [DesktopRecordingSession.encode] to assemble the
 * per-frame PNGs produced by the playback loop into a single APNG file the agent (or a webview /
 * browser) can play back.
 *
 * **Wire shape.** APNG is the standard PNG container with two chunk types added per the
 * [APNG spec](https://wiki.mozilla.org/APNG_Specification):
 *
 * - `acTL` (animation control): emitted once after the IHDR chunk; carries `numFrames` and
 *   `numPlays` (loop count).
 * - `fcTL` (frame control): one per animation frame, carries `width`, `height`, `xOffset`,
 *   `yOffset`, `delayNum/delayDen`, `disposeOp`, `blendOp`. The first `fcTL` precedes the `IDAT`
 *   chunk that carries the first frame's pixels; subsequent `fcTL`s precede the `fdAT` chunks for
 *   later frames.
 * - `fdAT` (frame data): same compressed pixel data as IDAT but with a leading 4-byte sequence
 *   number. Chained one or more per frame (we emit one).
 *
 * **Frame source.** [encodeFromPngFrames] takes a list of PNG files (one per frame, all with
 * identical width × height — enforced by [DesktopRecordingSession]'s scaled-or-natural frame
 * writer) and:
 *
 * 1. Reads the first frame's `IHDR` to learn width/height/colour type — that becomes the APNG's own
 *    IHDR.
 * 2. Copies each frame's `IDAT` chunks into either an output IDAT (frame 0) or `fdAT` chunks
 *    (frames 1..N-1), wrapping each frame in a fresh `fcTL`.
 * 3. Writes a single `IEND` to close.
 *
 * **What this isn't.** Not a re-encoder — we don't decode pixels and re-deflate them. We rely on
 * Skiko emitting per-frame PNGs with the same IHDR shape, then surgically copy IDAT bytes into fdAT
 * chunks. This keeps the encoder small (~150 LOC) and fast (no zlib round-trip), at the cost of
 * trusting the input frames to share an IHDR. [DesktopRecordingSession] guarantees this via the
 * fixed `frameWidthPx × frameHeightPx` raster surface.
 *
 * **Loop count.** `0` means "infinite" per APNG spec — that's the default.
 */
object ApngEncoder {

  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 PNG\r\n SUB \n
  private const val CHUNK_TYPE_IHDR = "IHDR"
  private const val CHUNK_TYPE_IDAT = "IDAT"
  private const val CHUNK_TYPE_IEND = "IEND"
  private const val CHUNK_TYPE_ACTL = "acTL"
  private const val CHUNK_TYPE_FCTL = "fcTL"
  private const val CHUNK_TYPE_FDAT = "fdAT"

  fun encodeFromPngFrames(
    frames: List<File>,
    delayNumerator: Short,
    delayDenominator: Short,
    loopCount: Int,
    out: File,
  ) {
    require(frames.isNotEmpty()) { "ApngEncoder: at least one frame required" }
    require(delayDenominator > 0) { "ApngEncoder: delayDenominator must be > 0" }
    require(loopCount >= 0) { "ApngEncoder: loopCount must be ≥ 0 (0 = infinite)" }

    out.parentFile?.mkdirs()
    RandomAccessFile(out, "rw").use { raf ->
      raf.setLength(0)
      raf.write(PNG_SIGNATURE)

      // Read the first frame's IHDR + IDATs to seed the output IHDR + frame 0 fcTL+IDAT.
      val firstFrame = readPngChunks(frames[0])
      val ihdr =
        firstFrame.firstOrNull { it.type == CHUNK_TYPE_IHDR }
          ?: error("ApngEncoder: ${frames[0]} has no IHDR chunk")
      writeChunk(raf, CHUNK_TYPE_IHDR, ihdr.data)

      val (frameWidth, frameHeight) = parseIhdrSize(ihdr.data)

      // acTL — animation control. Goes between IHDR and the first IDAT.
      val acTl = ByteBuffer.allocate(8).putInt(frames.size).putInt(loopCount).array()
      writeChunk(raf, CHUNK_TYPE_ACTL, acTl)

      var sequenceNumber = 0

      // Frame 0 — fcTL with seq=0, then frame's IDAT chunks copied verbatim.
      writeFcTl(
        raf,
        sequenceNumber = sequenceNumber++,
        width = frameWidth,
        height = frameHeight,
        delayNum = delayNumerator,
        delayDen = delayDenominator,
      )
      for (chunk in firstFrame.filter { it.type == CHUNK_TYPE_IDAT }) {
        writeChunk(raf, CHUNK_TYPE_IDAT, chunk.data)
      }

      // Frames 1..N — fcTL + fdAT(seq, idatPayload) per frame.
      for (frameIndex in 1 until frames.size) {
        val frameChunks = readPngChunks(frames[frameIndex])
        val frameIhdr =
          frameChunks.firstOrNull { it.type == CHUNK_TYPE_IHDR }
            ?: error("ApngEncoder: ${frames[frameIndex]} has no IHDR chunk")
        val (w, h) = parseIhdrSize(frameIhdr.data)
        require(w == frameWidth && h == frameHeight) {
          "ApngEncoder: frame $frameIndex (${frames[frameIndex]}) size ${w}x$h does not match " +
            "frame 0 size ${frameWidth}x$frameHeight — frames must share IHDR"
        }
        writeFcTl(
          raf,
          sequenceNumber = sequenceNumber++,
          width = w,
          height = h,
          delayNum = delayNumerator,
          delayDen = delayDenominator,
        )
        for (chunk in frameChunks.filter { it.type == CHUNK_TYPE_IDAT }) {
          val fdAt = ByteBuffer.allocate(4 + chunk.data.size)
          fdAt.putInt(sequenceNumber++)
          fdAt.put(chunk.data)
          writeChunk(raf, CHUNK_TYPE_FDAT, fdAt.array())
        }
      }

      writeChunk(raf, CHUNK_TYPE_IEND, ByteArray(0))
    }
  }

  private fun writeFcTl(
    raf: RandomAccessFile,
    sequenceNumber: Int,
    width: Int,
    height: Int,
    delayNum: Short,
    delayDen: Short,
  ) {
    // fcTL chunk: 26 bytes
    //   seq (4) + width (4) + height (4) + xOffset (4) + yOffset (4)
    //   + delayNum (2) + delayDen (2) + disposeOp (1) + blendOp (1)
    // disposeOp = 0 (NONE — leave the framebuffer as is for the next frame).
    // blendOp = 0 (SOURCE — overwrite the framebuffer with this frame's pixels).
    val payload =
      ByteBuffer.allocate(26)
        .putInt(sequenceNumber)
        .putInt(width)
        .putInt(height)
        .putInt(0)
        .putInt(0)
        .putShort(delayNum)
        .putShort(delayDen)
        .put(0.toByte())
        .put(0.toByte())
        .array()
    writeChunk(raf, CHUNK_TYPE_FCTL, payload)
  }

  private fun writeChunk(raf: RandomAccessFile, type: String, data: ByteArray) {
    require(type.length == 4) { "PNG chunk type must be 4 ASCII chars; got '$type'" }
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    raf.writeInt(data.size)
    raf.write(typeBytes)
    raf.write(data)
    raf.writeInt(crc.value.toInt())
  }

  private data class PngChunk(val type: String, val data: ByteArray)

  private fun readPngChunks(file: File): List<PngChunk> {
    val bytes = file.readBytes()
    require(bytes.size > PNG_SIGNATURE.size) {
      "ApngEncoder: ${file.absolutePath} is too small to be a PNG"
    }
    for (i in PNG_SIGNATURE.indices) {
      require(bytes[i] == PNG_SIGNATURE[i]) {
        "ApngEncoder: ${file.absolutePath} is not a valid PNG (signature mismatch at byte $i)"
      }
    }
    val chunks = mutableListOf<PngChunk>()
    val buf = ByteBuffer.wrap(bytes)
    buf.position(PNG_SIGNATURE.size)
    while (buf.remaining() >= 12) {
      val length = buf.int
      val typeBytes = ByteArray(4).also { buf.get(it) }
      val type = String(typeBytes, Charsets.US_ASCII)
      val data = ByteArray(length).also { buf.get(it) }
      buf.int // skip CRC; trust the source PNG was well-formed (Skiko)
      chunks.add(PngChunk(type, data))
      if (type == CHUNK_TYPE_IEND) break
    }
    return chunks
  }

  private fun parseIhdrSize(ihdrData: ByteArray): Pair<Int, Int> {
    require(ihdrData.size >= 8) { "ApngEncoder: IHDR chunk too short (${ihdrData.size} bytes)" }
    val buf = ByteBuffer.wrap(ihdrData)
    val width = buf.int
    val height = buf.int
    return width to height
  }
}
