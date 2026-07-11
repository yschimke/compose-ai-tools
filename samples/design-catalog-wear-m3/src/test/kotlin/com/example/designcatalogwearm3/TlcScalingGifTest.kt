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
 * Assembles the **down-and-up scaling GIF** a `@TlcScalingPreview` promises, from the
 * `CardScalingSweep` frames the compose-preview plugin renders — one per [TlcScaleLevels] value
 * (full → most scaled). Pure JVM: it only reads the plugin's PNGs and encodes a GIF, so there's no
 * Robolectric here; `composePreview { renderBeforeUnitTests }` guarantees the frames exist first
 * (mirrors `:samples:wear`'s `LongScrollPreviewPixelTest`).
 *
 * It also checks the sweep really scales: the frames share a size and the card's footprint shrinks
 * from the full frame to the most-scaled one.
 */
class TlcScalingGifTest {

  private val renders = File("build/compose-previews/renders")

  @Test
  fun `stitches the sweep frames into a down-and-up scaling GIF`() {
    // The plugin names @PreviewParameter frames `CardScalingSweep_Large_Round_<level>.png`; sort by
    // the trailing level so they run full (0.0) -> most scaled (1.0).
    val frameFiles =
      (renders.listFiles { f -> f.name.matches(FRAME_NAME) } ?: emptyArray())
        .sortedBy { it.name.substringAfterLast('_').removeSuffix(".png").toFloat() }
    assertTrue(
      "expected CardScalingSweep frames in $renders (did composePreviewRenderAll run?)",
      frameFiles.size >= 3,
    )
    val frames = frameFiles.map { ImageIO.read(it) }

    val w = frames.first().width
    val h = frames.first().height
    assertTrue("frames must share a size for the GIF", frames.all { it.width == w && it.height == h })

    assertTrue("full vs most-scaled frame must differ", framesDiffer(frames.first(), frames.last()))
    val footprint = frames.map(::cardPixelCount)
    assertTrue(
      "card footprint must shrink from full to most scaled (got $footprint)",
      footprint.first() > footprint.last(),
    )

    // Play the frames down then back up (drop the duplicated endpoints on the way up), looping.
    val bounce = frames + frames.reversed().drop(1).dropLast(1)
    val gif = File(renders, "CardScalingSweep_scaling.gif")
    encodeLoopingGif(bounce, gif, frameDelayMs = 140)
    assertTrue("GIF must be written", gif.exists() && gif.length() > 0)
  }

  /** True if the two same-sized frames differ in any pixel. */
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
   * `javax.imageio` GIF writer (same approach as the renderer's `ScrollGifEncoder`): a per-frame
   * `GraphicControlExtension` carries the delay, and a `NETSCAPE2.0` application extension sets
   * `loopCount = 0` (forever).
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
    val FRAME_NAME = Regex("""CardScalingSweep_Large_Round_[0-9.]+\.png""")
  }
}
