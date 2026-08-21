package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PngHeader] is what puts a real pixel size on every live `streamFrame`, which used to go out as
 * `0 × 0` (#4281). It reads bytes the frame path already holds, so the failure mode to guard is a
 * *wrong* size rather than a slow one: every caller has an unknown branch, and none has a "that
 * size looked odd" branch.
 */
class PngHeaderTest {

  @Test
  fun readsWidthAndHeightFromTheIhdr() {
    assertEquals(499 to 226, PngHeader.dimensions(png(499, 226)))
  }

  @Test
  fun readsASinglePixelImage() {
    assertEquals(1 to 1, PngHeader.dimensions(png(1, 1)))
  }

  @Test
  fun returnsNullForNullOrTruncatedInput() {
    assertNull(PngHeader.dimensions(null))
    assertNull(
      "a header-length prefix is the shortest readable input",
      PngHeader.dimensions(png(8, 8).copyOf(20)),
    )
    assertNull(PngHeader.dimensions(ByteArray(0)))
  }

  @Test
  fun returnsNullWhenTheSignatureIsNotPng() {
    // A WebP frame, once #4286 lands, must read as "size unknown" rather than as a misparsed PNG.
    val webpish = "RIFF....WEBPVP8 ".toByteArray() + ByteArray(32)
    assertNull(PngHeader.dimensions(webpish))
  }

  @Test
  fun returnsNullForASignatureWithoutAnIhdrChunk() {
    // The daemon's own test hosts emit exactly this: a PNG signature followed by arbitrary bytes.
    // Reading those as a size would put a fabricated width on the wire.
    val stub =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(16) { it.toByte() }
    assertNull(PngHeader.dimensions(stub))
  }

  @Test
  fun returnsNullOnANonsensicalDeclaredSize() {
    val zeroWidth = png(1, 1).copyOfRange(0, png(1, 1).size)
    // Overwrite the IHDR width field with zero.
    for (i in 16..19) zeroWidth[i] = 0
    assertNull(PngHeader.dimensions(zeroWidth))
  }

  /** A real, decodable [width] × [height] PNG — signature, IHDR, one IDAT of opaque black, IEND. */
  private fun png(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr =
      ByteArrayOutputStream().apply {
        writeInt(width)
        writeInt(height)
        write(8) // bit depth
        write(6) // colour type: RGBA
        write(0) // compression
        write(0) // filter
        write(0) // interlace
      }
    out.writeChunk("IHDR", ihdr.toByteArray())
    val raw =
      ByteArrayOutputStream()
        .apply {
          repeat(height) {
            write(0) // filter byte per scanline
            repeat(width) { write(byteArrayOf(0, 0, 0, 0xFF.toByte())) }
          }
        }
        .toByteArray()
    out.writeChunk("IDAT", deflate(raw))
    out.writeChunk("IEND", ByteArray(0))
    return out.toByteArray()
  }

  private fun ByteArrayOutputStream.writeInt(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
  }

  private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
    writeInt(data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    write(typeBytes)
    write(data)
    val crc =
      CRC32().apply {
        update(typeBytes)
        update(data)
      }
    writeInt(crc.value.toInt())
  }

  private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(bytes)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
  }
}
