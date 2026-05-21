package ee.schimke.composeai.daemon

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Shared helpers for the `TouchOverlay*RecordingTest` integration tests — pixel-coverage assertions
 * and an animated-GIF encoder that stitches recorded `frame-NNNNN.png` files into a single GIF the
 * PR comment can embed. Extracted from the original `TouchOverlayPinchRecordingTest` so the second
 * test (`TouchOverlayDrawingRecordingTest`) doesn't fork the boilerplate; both share the same GIF
 * timing and metadata so the artifacts read consistently.
 */
internal object TouchOverlayTestSupport {

  /**
   * Pixel-coverage helper: fraction of pixels in [image] within [perChannelTolerance] of
   * [expectedRgb] on every channel. Inlined here (rather than reusing `:harness:PixelDiff`) to
   * avoid a circular dep — same reasoning the broader codebase uses for `RenderEngineTest`'s inline
   * copy.
   */
  fun pixelMatchPctApprox(
    image: BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matched = 0L
    var total = 0L
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          kotlin.math.abs(r - expR) <= perChannelTolerance &&
            kotlin.math.abs(g - expG) <= perChannelTolerance &&
            kotlin.math.abs(b - expB) <= perChannelTolerance
        ) {
          matched++
        }
        total++
      }
    }
    return if (total == 0L) 0.0 else matched.toDouble() / total.toDouble()
  }

  /** Read a PNG file into a [BufferedImage] for pixel assertions. */
  fun readPng(f: File): BufferedImage =
    requireNotNull(ImageIO.read(f)) { "ImageIO.read returned null for ${f.absolutePath}" }

  /**
   * Minimal animated-GIF encoder for the artifact upload — uses Java's bundled `javax.imageio` GIF
   * writer plugin. Reads `frame-NNNNN.png` files from [framesDir], writes [outputFile] looping
   * forever at `1000 / fps` ms per frame.
   *
   * Used instead of APNG for PR-comment embedding because GitHub's web comment renderer doesn't
   * autoplay APNG inline (but always does GIF). APNG is still produced as the primary artifact via
   * `session.encode(RecordingFormat.APNG)`; the GIF is a thumbnail for human review.
   */
  fun encodeFramesAsGif(framesDir: File, frameCount: Int, outputFile: File, fps: Int) {
    require(frameCount > 0) { "frameCount must be > 0; got $frameCount" }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("No GIF writer plugin registered")
    outputFile.parentFile?.mkdirs()
    val frameDelayCs = ((1000 / fps) / 10).coerceAtLeast(2)
    FileImageOutputStream(outputFile).use { stream ->
      writer.output = stream
      val firstFrame = readPng(File(framesDir, "frame-00000.png"))
      val imageType = ImageTypeSpecifier.createFromRenderedImage(firstFrame)
      val param = writer.defaultWriteParam
      val meta = writer.getDefaultImageMetadata(imageType, param)
      configureGifFrameMetadata(meta, frameDelayCs, loopForever = true)
      writer.prepareWriteSequence(null)
      writer.writeToSequence(IIOImage(firstFrame, null, meta), param)
      for (i in 1 until frameCount) {
        val frame = readPng(File(framesDir, "frame-${"%05d".format(i)}.png"))
        val frameMeta = writer.getDefaultImageMetadata(imageType, param)
        configureGifFrameMetadata(frameMeta, frameDelayCs, loopForever = false)
        writer.writeToSequence(IIOImage(frame, null, frameMeta), param)
      }
      writer.endWriteSequence()
    }
    writer.dispose()
  }

  private fun configureGifFrameMetadata(meta: IIOMetadata, delayCs: Int, loopForever: Boolean) {
    val formatName = meta.nativeMetadataFormatName
    val root = meta.getAsTree(formatName) as IIOMetadataNode
    val gce = root.getOrCreateChild("GraphicControlExtension")
    gce.setAttribute("disposalMethod", "none")
    gce.setAttribute("userInputFlag", "FALSE")
    gce.setAttribute("transparentColorFlag", "FALSE")
    gce.setAttribute("delayTime", delayCs.toString())
    gce.setAttribute("transparentColorIndex", "0")
    if (loopForever) {
      val appExts = root.getOrCreateChild("ApplicationExtensions")
      val appExt = IIOMetadataNode("ApplicationExtension")
      appExt.setAttribute("applicationID", "NETSCAPE")
      appExt.setAttribute("authenticationCode", "2.0")
      appExt.userObject = byteArrayOf(0x1, 0x0, 0x0)
      appExts.appendChild(appExt)
    }
    meta.setFromTree(formatName, root)
  }

  private fun IIOMetadataNode.getOrCreateChild(name: String): IIOMetadataNode {
    var child = firstChild
    while (child != null) {
      if (child.nodeName.equals(name, ignoreCase = true)) return child as IIOMetadataNode
      child = child.nextSibling
    }
    val created = IIOMetadataNode(name)
    appendChild(created)
    return created
  }

  /**
   * Resolves the repo-root `build/touch-overlay-artifacts` directory, where recording-integration
   * tests drop their GIF artifacts for the PR-upload step. `user.dir` is the module's working dir
   * when run from gradle — walk up two levels to reach the repo root.
   */
  val ARTIFACT_DIR: File =
    File(System.getProperty("user.dir"))
      .parentFile
      .parentFile
      .resolve("build/touch-overlay-artifacts")
}
