package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode

/**
 * Minimal pure-JVM animated-GIF encoder. Used by [DesktopRecordingSession.encode] /
 * [AndroidRecordingSession.encode] to stitch the per-frame `frame-NNNNN.png` files the playback
 * loop writes into a single looping GIF.
 *
 * **Why first-class GIF.** APNG is the canonical recording artifact (pure-JVM [ApngEncoder]), but
 * GIF is what plays inline everywhere a human or agent looks at the result — chat clients and
 * GitHub's web comment renderer autoplay GIF but not APNG. MP4 / WEBM ([FfmpegEncoder]) give
 * smaller files but need a native `ffmpeg`. GIF shares APNG's "always available" property because
 * the JDK bundles a `javax.imageio` GIF writer plugin, so [DesktopHost.supportedRecordingFormats] /
 * [RobolectricHost.supportedRecordingFormats] advertise it unconditionally.
 *
 * **Frame source.** [encodeFromPngFrames] takes a list of PNG files (one per frame, all sharing the
 * same dimensions — guaranteed by the fixed-size raster surface the recording sessions write) and
 * writes them as a single looping GIF at `1000 / fps` ms per frame.
 *
 * Extracted from the original `TouchOverlayTestSupport.encodeFramesAsGif` test helper so the
 * recording surface ships GIF as a real encoder rather than a test-only artifact.
 *
 * **`ImageIO` boundary.** GIF read/write goes through `javax.imageio`, one of the sanctioned
 * `java.io.File` boundaries (see docs/AGENTS.md "File/IO goes through Okio … except a hard
 * third-party boundary"). The encoder keeps `File` local to those calls.
 */
object GifEncoder {

  /**
   * Encode [frames] (PNG files, contiguous, sharing one size) into a looping GIF at [fps] frames
   * per second, writing to [out]. Throws when [frames] is empty or a frame can't be read.
   */
  fun encodeFromPngFrames(frames: List<File>, fps: Int, out: File) {
    require(frames.isNotEmpty()) { "GifEncoder: at least one frame required" }
    require(fps in 1..120) { "GifEncoder: fps=$fps out of range [1, 120]" }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("GifEncoder: no GIF writer plugin registered in this JVM")

    out.parentFile?.mkdirs()
    if (out.exists()) out.delete()

    // GIF frame delay is expressed in centiseconds (1/100 s). Clamp to ≥ 2cs (20ms) so viewers that
    // treat very small delays as "as fast as possible" still animate at a sane rate, matching the
    // browser convention for sub-20ms GIF delays.
    val frameDelayCs = ((1000 / fps) / 10).coerceAtLeast(2)

    javax.imageio.stream.FileImageOutputStream(out).use { stream ->
      writer.output = stream
      val firstFrame =
        requireNotNull(ImageIO.read(frames[0])) {
          "GifEncoder: ImageIO.read returned null for ${frames[0].absolutePath}"
        }
      val imageType = ImageTypeSpecifier.createFromRenderedImage(firstFrame)
      val param = writer.defaultWriteParam
      val meta = writer.getDefaultImageMetadata(imageType, param)
      configureGifFrameMetadata(meta, frameDelayCs, loopForever = true)
      writer.prepareWriteSequence(null)
      writer.writeToSequence(IIOImage(firstFrame, null, meta), param)
      for (i in 1 until frames.size) {
        val frame =
          requireNotNull(ImageIO.read(frames[i])) {
            "GifEncoder: ImageIO.read returned null for ${frames[i].absolutePath}"
          }
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
      // NETSCAPE2.0 application extension with loop count 0 (= infinite). Emitted once, on the
      // first
      // frame, per the de-facto looping-GIF convention.
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
}
