package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApngEncoderTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun encodes_png_frames_as_apng_with_expected_control_chunks() {
    val frames =
      listOf(
        writeFrame("frame-0.png", Color.RED),
        writeFrame("frame-1.png", Color.GREEN),
        writeFrame("frame-2.png", Color.BLUE),
      )
    val out = File(tmp.newFolder("out"), "demo.apng")

    ApngEncoder.encodeFromPngFrames(
      frames = frames,
      delayNumerator = 1,
      delayDenominator = 30,
      loopCount = 0,
      out = out,
    )

    val chunks = chunks(out)
    assertEquals("IHDR", chunks.first().type)
    assertEquals(1, chunks.count { it.type == "acTL" })
    assertEquals(frames.size, chunks.count { it.type == "fcTL" })
    assertEquals(1, chunks.count { it.type == "IDAT" })
    assertEquals(frames.size - 1, chunks.count { it.type == "fdAT" })
    assertEquals("IEND", chunks.last().type)

    val acTl = ByteBuffer.wrap(chunks.single { it.type == "acTL" }.data)
    assertEquals(frames.size, acTl.int)
    assertEquals(0, acTl.int)
    assertTrue("APNG should be non-empty", out.length() > 0)
  }

  @Test
  fun rejects_empty_frame_list() {
    assertThrows(IllegalArgumentException::class.java) {
      ApngEncoder.encodeFromPngFrames(
        frames = emptyList(),
        delayNumerator = 1,
        delayDenominator = 30,
        loopCount = 0,
        out = File(tmp.root, "empty.apng"),
      )
    }
  }

  @Test
  fun rejects_mismatched_frame_dimensions() {
    val frames = listOf(writeFrame("a.png", Color.RED, 8, 8), writeFrame("b.png", Color.BLUE, 9, 8))

    assertThrows(IllegalArgumentException::class.java) {
      ApngEncoder.encodeFromPngFrames(
        frames = frames,
        delayNumerator = 1,
        delayDenominator = 30,
        loopCount = 0,
        out = File(tmp.root, "mismatch.apng"),
      )
    }
  }

  @Test
  fun rejects_non_png_frame() {
    val bad = File(tmp.root, "bad.png").apply { writeText("not a png") }

    assertThrows(IllegalArgumentException::class.java) {
      ApngEncoder.encodeFromPngFrames(
        frames = listOf(bad),
        delayNumerator = 1,
        delayDenominator = 30,
        loopCount = 0,
        out = File(tmp.root, "bad.apng"),
      )
    }
  }

  private fun writeFrame(name: String, color: Color, width: Int = 8, height: Int = 8): File {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, width, height)
    g.dispose()
    return File(tmp.root, name).also { ImageIO.write(img, "png", it) }
  }

  private fun chunks(file: File): List<Chunk> {
    val bytes = file.readBytes()
    val buf = ByteBuffer.wrap(bytes)
    val signature = ByteArray(8)
    buf.get(signature)
    val chunks = mutableListOf<Chunk>()
    while (buf.remaining() >= 12) {
      val length = buf.int
      val typeBytes = ByteArray(4).also { buf.get(it) }
      val data = ByteArray(length).also { buf.get(it) }
      buf.int
      chunks += Chunk(String(typeBytes, Charsets.US_ASCII), data)
    }
    return chunks
  }

  private data class Chunk(val type: String, val data: ByteArray)
}
