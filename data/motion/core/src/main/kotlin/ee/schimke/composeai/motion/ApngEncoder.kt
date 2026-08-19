package ee.schimke.composeai.motion

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Minimal pure-JVM Animated PNG encoder.
 *
 * Lives here rather than in a renderer because both renderers need it and neither can see the
 * other: the desktop backend stitches Skiko's per-frame PNGs (`renderLottieApng`, the motion
 * captures), and the Robolectric backend stitches Roborazzi's (`handleInteractionCapture`). An
 * `@InteractionPreview` is published from whichever backend the module happens to build with, so
 * two copies of this encoder would mean one component's capture could differ in container bytes
 * from its sibling's for no reason a reader could see. `:daemon:core` keeps its own separate copy
 * on purpose — it is a different lane, and depending on a render module from there shadowed the
 * renderer's own test fixtures.
 *
 * **Why APNG.** These captures carry anti-aliased edges over transparency — a Lottie companion
 * against no background, a state layer mid-fade, a shape mid-morph. GIF's 1-bit alpha cannot hold
 * that edge (it churned run-to-run) and its 1/100s delay quantisation cannot express 60fps. APNG is
 * a standard PNG container with full 8-bit alpha and rational frame delays, so the RGBA frames
 * travel through unchanged at the rate they were authored for.
 *
 * **Wire shape** (per the [APNG spec](https://wiki.mozilla.org/APNG_Specification)): the standard
 * PNG stream plus `acTL` (once, after IHDR), and per frame an `fcTL`; frame 0's pixels stay in
 * `IDAT`, later frames go in `fdAT` chunks carrying a leading sequence number. We copy each source
 * frame's `IDAT` bytes verbatim rather than re-deflating — so all frames must share an IHDR (same
 * width × height × colour type), which the fixed-size capture surface guarantees.
 */
object ApngEncoder {

  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 PNG\r\n SUB \n
  private const val CHUNK_TYPE_IHDR = "IHDR"
  private const val CHUNK_TYPE_IDAT = "IDAT"
  private const val CHUNK_TYPE_IEND = "IEND"
  private const val CHUNK_TYPE_ACTL = "acTL"
  private const val CHUNK_TYPE_FCTL = "fcTL"
  private const val CHUNK_TYPE_FDAT = "fdAT"

  /**
   * Stitch [frames] (contiguous PNG files sharing one IHDR) into a looping APNG at [out], each
   * frame held for [delayNumerator]/[delayDenominator] seconds; [loopCount] `0` = infinite.
   */
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

      val firstFrame = readPngChunks(frames[0])
      val ihdr =
        firstFrame.firstOrNull { it.type == CHUNK_TYPE_IHDR }
          ?: error("ApngEncoder: ${frames[0]} has no IHDR chunk")
      writeChunk(raf, CHUNK_TYPE_IHDR, ihdr.data)

      val (frameWidth, frameHeight) = parseIhdrSize(ihdr.data)

      // acTL — animation control, between IHDR and the first IDAT.
      val acTl = ByteBuffer.allocate(8).putInt(frames.size).putInt(loopCount).array()
      writeChunk(raf, CHUNK_TYPE_ACTL, acTl)

      var sequenceNumber = 0

      // Frame 0 — fcTL(seq=0) then the frame's IDATs copied verbatim.
      writeFcTl(raf, sequenceNumber++, frameWidth, frameHeight, delayNumerator, delayDenominator)
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
        writeFcTl(raf, sequenceNumber++, w, h, delayNumerator, delayDenominator)
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
    // fcTL: seq(4) w(4) h(4) xOff(4) yOff(4) delayNum(2) delayDen(2) disposeOp(1) blendOp(1).
    // disposeOp = 0 (NONE), blendOp = 0 (SOURCE — overwrite the framebuffer with this frame).
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
    require(bytes.size > PNG_SIGNATURE.size) { "ApngEncoder: ${file.absolutePath} too small" }
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
      buf.int // skip CRC; the source PNG (Skiko) is trusted well-formed.
      chunks.add(PngChunk(type, data))
      if (type == CHUNK_TYPE_IEND) break
    }
    return chunks
  }

  private fun parseIhdrSize(ihdrData: ByteArray): Pair<Int, Int> {
    require(ihdrData.size >= 8) { "ApngEncoder: IHDR chunk too short (${ihdrData.size} bytes)" }
    val buf = ByteBuffer.wrap(ihdrData)
    return buf.int to buf.int
  }
}
