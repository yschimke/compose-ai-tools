package ee.schimke.composeai.cli

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Guards the issue-#209 contract: GIFs are hashed by their first + last frames only, so a
 * non-deterministic scrolling preview (whose mid-walk frames vary run-to-run with `LazyColumn`
 * materialisation) doesn't show up as "Changed" in the PR diff bot just because of encoder noise
 * between the bookend frames.
 */
class PreviewSha256Test {
  private val tempDir = createTempDirectory("preview-sha-test").toFile()

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  @Test
  fun `gif hash ignores middle frames`() {
    val a = writeGif("a.gif", listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW))
    val b = writeGif("b.gif", listOf(Color.RED, Color.MAGENTA, Color.CYAN, Color.YELLOW))

    assertEquals(previewSha256(a), previewSha256(b))
  }

  @Test
  fun `gif hash changes when first frame changes`() {
    val redFirst = writeGif("red-first.gif", listOf(Color.RED, Color.GRAY, Color.BLUE))
    val greenFirst = writeGif("green-first.gif", listOf(Color.GREEN, Color.GRAY, Color.BLUE))

    assertNotEquals(previewSha256(redFirst), previewSha256(greenFirst))
  }

  @Test
  fun `gif hash changes when last frame changes`() {
    val blueLast = writeGif("blue-last.gif", listOf(Color.RED, Color.GRAY, Color.BLUE))
    val yellowLast = writeGif("yellow-last.gif", listOf(Color.RED, Color.GRAY, Color.YELLOW))

    assertNotEquals(previewSha256(blueLast), previewSha256(yellowLast))
  }

  @Test
  fun `single-frame gif hashes without crashing`() {
    val one = writeGif("one.gif", listOf(Color.RED))
    val same = writeGif("same.gif", listOf(Color.RED))
    val other = writeGif("other.gif", listOf(Color.BLUE))

    assertEquals(previewSha256(one), previewSha256(same))
    assertNotEquals(previewSha256(one), previewSha256(other))
  }

  @Test
  fun `png hash uses the full file bytes`() {
    val a = writePng("a.png", Color.RED)
    val b = writePng("b.png", Color.RED)
    val c = writePng("c.png", Color.BLUE)

    assertEquals(previewSha256(a), previewSha256(b))
    assertNotEquals(previewSha256(a), previewSha256(c))
  }

  private fun writePng(name: String, color: Color): File {
    val img = solidImage(color)
    val file = File(tempDir, name)
    ImageIO.write(img, "png", file)
    return file
  }

  private fun writeGif(name: String, colors: List<Color>): File {
    val file = File(tempDir, name)
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    FileImageOutputStream(file).use { out ->
      writer.output = out
      val param = writer.defaultWriteParam
      writer.prepareWriteSequence(null)
      for (c in colors) {
        writer.writeToSequence(IIOImage(solidImage(c), null, null), param)
      }
      writer.endWriteSequence()
    }
    writer.dispose()
    return file
  }

  private fun solidImage(color: Color): BufferedImage {
    val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
      g.color = color
      g.fillRect(0, 0, img.width, img.height)
    } finally {
      g.dispose()
    }
    return img
  }
}
