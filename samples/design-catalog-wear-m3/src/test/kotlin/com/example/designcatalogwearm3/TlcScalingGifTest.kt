package com.example.designcatalogwearm3

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assembles the **scroll-out-and-back GIF** from the `CardScroll0..6` frames the compose-preview
 * plugin renders — the same card scrolled from centred to mostly off the top edge. Pure JVM: it only
 * reads the plugin's PNGs and encodes a GIF, so there's no Robolectric here;
 * `composePreview { renderBeforeUnitTests }` guarantees the frames exist first (mirrors
 * `:samples:wear`'s `LongScrollPreviewPixelTest`).
 */
class TlcScalingGifTest {

  private val renders = File("build/compose-previews/renders")

  @Test
  fun `stitches the scroll frames into an out-and-back GIF`() {
    // CardScroll0..6, in order (centre -> mostly off the top).
    val frameFiles =
      (renders.listFiles { f -> f.name.matches(FRAME_NAME) } ?: emptyArray())
        .sortedBy { it.name }
    assertTrue(
      "expected CardScroll* frames in $renders (did composePreviewRenderAll run?)",
      frameFiles.size >= 5,
    )
    val frames = frameFiles.map { ImageIO.read(it) }

    val w = frames.first().width
    val h = frames.first().height
    assertTrue("frames must share a size for the GIF", frames.all { it.width == w && it.height == h })

    // It really scrolls out: the card's footprint shrinks from centred to the mostly-off last frame.
    assertTrue("centre vs mostly-off frame must differ", framesDiffer(frames.first(), frames.last()))
    assertTrue(
      "card footprint must shrink as it scrolls off",
      cardPixelCount(frames.first()) > cardPixelCount(frames.last()),
    )

    // Play the frames out then back (drop the duplicated endpoints on the way back), looping.
    val bounce = frames + frames.reversed().drop(1).dropLast(1)
    val gif = File(renders, "CardScaling_scroll.gif")
    encodeLoopingGif(bounce, gif, frameDelayMs = 90)
    assertTrue("GIF must be written", gif.exists() && gif.length() > 0)
  }

  private fun framesDiffer(a: BufferedImage, b: BufferedImage): Boolean {
    for (y in 0 until a.height) for (x in 0 until a.width) {
      if (a.getRGB(x, y) != b.getRGB(x, y)) return true
    }
    return false
  }

  /** Count of non-near-black pixels — the card's footprint over the black device background. */
  private fun cardPixelCount(img: BufferedImage): Long {
    var count = 0L
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val argb = img.getRGB(x, y)
      val r = (argb shr 16) and 0xff
      val g = (argb shr 8) and 0xff
      val b = argb and 0xff
      if (maxOf(r, g, b) > 24) count++
    }
    return count
  }

  /**
   * Encode [frames] as an infinitely-looping animated GIF at [frameDelayMs] per frame — minimal
   * `javax.imageio` GIF writer (same approach as the renderer's `ScrollGifEncoder`).
   */
  private fun encodeLoopingGif(frames: List<BufferedImage>, outputFile: File, frameDelayMs: Int) {
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    FileImageOutputStream(outputFile).use { stream ->
      writer.output = stream
      val params = writer.defaultWriteParam
      val typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)
      val metadata = writer.getDefaultImageMetadata(typeSpecifier, params)
      val nativeFormat = metadata.nativeMetadataFormatName
      val root = metadata.getAsTree(nativeFormat) as IIOMetadataNode

      childNode(root, "GraphicControlExtension").apply {
        setAttribute("disposalMethod", "none")
        setAttribute("userInputFlag", "FALSE")
        setAttribute("transparentColorFlag", "FALSE")
        setAttribute("delayTime", (frameDelayMs / 10).coerceAtLeast(1).toString())
        setAttribute("transparentColorIndex", "0")
      }
      val netscape =
        IIOMetadataNode("ApplicationExtension").apply {
          setAttribute("applicationID", "NETSCAPE")
          setAttribute("authenticationCode", "2.0")
          userObject = byteArrayOf(0x1, 0x0, 0x0) // sub-block id + loopCount=0 (forever)
        }
      childNode(root, "ApplicationExtensions").appendChild(netscape)
      metadata.setFromTree(nativeFormat, root)

      writer.prepareWriteSequence(null)
      frames.forEach { writer.writeToSequence(IIOImage(it, null, metadata), params) }
      writer.endWriteSequence()
    }
    writer.dispose()
  }

  private fun childNode(root: IIOMetadataNode, name: String): IIOMetadataNode {
    for (i in 0 until root.length) {
      val node = root.item(i) as IIOMetadataNode
      if (node.nodeName.equals(name, ignoreCase = true)) return node
    }
    return IIOMetadataNode(name).also { root.appendChild(it) }
  }

  private companion object {
    val FRAME_NAME = Regex("""CardScroll\d+_Large_Round\.png""")
  }
}
