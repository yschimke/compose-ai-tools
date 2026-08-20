package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal, real image bytes for the image lane's tests — no fixture files on disk. */
object ServeImageFixtures {

  /**
   * A genuine [width] × [height] PNG: signature, IHDR, a zlib-compressed IDAT of opaque grey rows,
   * IEND. Real bytes rather than a hand-written header, so the store's sniff and any decoder a test
   * reaches for both see a PNG.
   */
  fun png(width: Int = 4, height: Int = 3): ByteArray {
    val raw = ByteArrayOutputStream()
    repeat(height) {
      raw.write(0) // filter type: none
      repeat(width) {
        raw.write(0x80)
        raw.write(0x80)
        raw.write(0x80)
      }
    }
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
    out.write(byteArrayOf(0x0D, 0x0A, 0x1A, 0x0A))
    out.writeChunk(
      "IHDR",
      ByteArrayOutputStream()
        .apply {
          writeIntBE(width)
          writeIntBE(height)
          write(8) // bit depth
          write(2) // colour type: truecolour
          write(0) // compression
          write(0) // filter
          write(0) // interlace
        }
        .toByteArray(),
    )
    out.writeChunk("IDAT", deflate(raw.toByteArray()))
    out.writeChunk("IEND", ByteArray(0))
    return out.toByteArray()
  }

  /** The same PNG with an `acTL` chunk ahead of `IDAT`, i.e. an APNG. */
  fun apng(width: Int = 4, height: Int = 3): ByteArray {
    val still = png(width, height)
    // Splice the animation-control chunk in immediately before the first IDAT.
    val idat = still.indexOfChunk("IDAT")
    val actl =
      ByteArrayOutputStream()
        .apply {
          writeChunk(
            "acTL",
            ByteArrayOutputStream()
              .apply {
                writeIntBE(1) // frames
                writeIntBE(0) // plays: infinite
              }
              .toByteArray(),
          )
        }
        .toByteArray()
    return still.copyOfRange(0, idat) + actl + still.copyOfRange(idat, still.size)
  }

  /** A 6 × 5 GIF89a header — enough for the sniff and the logical screen descriptor. */
  fun gif(width: Int = 6, height: Int = 5): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write("GIF89a".toByteArray(Charsets.US_ASCII))
        write(width and 0xFF)
        write((width shr 8) and 0xFF)
        write(height and 0xFF)
        write((height shr 8) and 0xFF)
        write(0xF0) // global colour table flag
        write(0) // background colour index
        write(0) // pixel aspect ratio
        write(byteArrayOf(0x3B)) // trailer
      }
      .toByteArray()

  /** A lossy (`VP8 `) WebP header declaring [width] × [height]. */
  fun webp(width: Int = 8, height: Int = 6): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(30)
        write("WEBP".toByteArray(Charsets.US_ASCII))
        write("VP8 ".toByteArray(Charsets.US_ASCII))
        writeIntLE(10)
        write(byteArrayOf(0x30, 0x01, 0x00)) // frame tag
        write(byteArrayOf(0x9D.toByte(), 0x01, 0x2A)) // start code
        write(width and 0xFF)
        write((width shr 8) and 0x3F)
        write(height and 0xFF)
        write((height shr 8) and 0x3F)
      }
      .toByteArray()

  /** A JPEG with a real SOF0 frame header declaring [width] × [height]. */
  fun jpeg(width: Int = 12, height: Int = 9): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        write(byteArrayOf(0xFF.toByte(), 0xC0.toByte())) // SOF0
        writeShortBE(11) // segment length
        write(8) // sample precision
        writeShortBE(height)
        writeShortBE(width)
        write(1) // components
        write(1)
        write(0x11)
        write(0)
        write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
      }
      .toByteArray()

  // ---- helpers -----------------------------------------------------------------------------

  private fun ByteArrayOutputStream.writeIntBE(value: Int) {
    write((value shr 24) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
  }

  private fun ByteArrayOutputStream.writeIntLE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
  }

  private fun ByteArrayOutputStream.writeShortBE(value: Int) {
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
  }

  private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
    writeIntBE(data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    write(typeBytes)
    write(data)
    val crc =
      CRC32().apply {
        update(typeBytes)
        update(data)
      }
    writeIntBE(crc.value.toInt())
  }

  private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer))
    }
    deflater.end()
    return out.toByteArray()
  }

  /** Offset of the length field of the first chunk of [type]. */
  private fun ByteArray.indexOfChunk(type: String): Int {
    val marker = type.toByteArray(Charsets.US_ASCII)
    for (i in 8..size - marker.size) {
      if (marker.indices.all { this[i + it] == marker[it] }) return i - 4
    }
    error("no $type chunk")
  }
}
