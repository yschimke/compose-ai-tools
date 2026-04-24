package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Encodes a sequence of same-sized `BufferedImage` frames as an animated
 * GIF at [outputFile], looping forever at [frameDelayMs] per frame.
 *
 * Built on `javax.imageio`'s standard GIF writer plugin — no extra deps.
 * Two GIF-specific knobs are driven through the metadata tree that
 * `ImageWriter` exposes:
 *
 *  - `GraphicControlExtension` per frame carries the `delayTime`
 *    (hundredths of a second) and a `disposalMethod=none` so consecutive
 *    frames composite atop each other without flicker.
 *  - One `ApplicationExtensions / NETSCAPE2.0` record on the first frame
 *    signals infinite looping (`loopCount=0`). Without it most viewers
 *    play once and stop.
 *
 * GIF's palette is 256 colours per frame; for the UI-scroll case (flat
 * colours, anti-aliased text) the default `ImageIO` quantiser produces
 * acceptable output. If we ever need higher fidelity, NeuQuant / octree
 * dithering sits behind the same metadata plumbing.
 *
 * [ScrollMode.GIF] captures call this with one BufferedImage per scroll
 * step. Returns the written file, or `null` if [frames] is empty or the
 * GIF writer plugin isn't registered (never, on a standard JRE).
 */
internal object ScrollGifEncoder {
    const val DEFAULT_FRAME_DELAY_MS: Int = 80
    const val MIN_FRAME_DELAY_MS: Int = 20

    fun encode(
        frames: List<BufferedImage>,
        outputFile: File,
        frameDelayMs: Int = DEFAULT_FRAME_DELAY_MS,
    ): File? = encode(frames, outputFile, IntArray(frames.size) { frameDelayMs })

    /**
     * Variable per-frame cadence: [frameDelaysMs] must have one entry per
     * image in [frames]. Used by the scripted `ScrollMode.GIF` walk to give
     * hold-start / hold-end frames a longer dwell (e.g. 1000ms) than the
     * in-motion scroll frames (80ms) within a single GIF. Each frame's GCE
     * already gets its own `delayTime` attribute, so variable delay is just
     * a matter of plumbing the per-frame value through.
     */
    fun encode(
        frames: List<BufferedImage>,
        outputFile: File,
        frameDelaysMs: IntArray,
    ): File? {
        if (frames.isEmpty()) return null
        require(frameDelaysMs.size == frames.size) {
            "frameDelaysMs size ${frameDelaysMs.size} != frames size ${frames.size}"
        }
        val writer = ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
            ?: return null

        outputFile.parentFile?.mkdirs()
        FileImageOutputStream(outputFile).use { stream ->
            writer.output = stream
            val param: ImageWriteParam = writer.defaultWriteParam
            val first = frames.first()
            val imageType = ImageTypeSpecifier.createFromRenderedImage(first)
            val meta = writer.getDefaultImageMetadata(imageType, param)
            configureFrameMetadata(meta, toCentiseconds(frameDelaysMs[0]), loopForever = true)

            writer.prepareWriteSequence(null)
            writer.writeToSequence(IIOImage(first, null, meta), param)

            for (i in 1 until frames.size) {
                val frameMeta = writer.getDefaultImageMetadata(imageType, param)
                configureFrameMetadata(frameMeta, toCentiseconds(frameDelaysMs[i]), loopForever = false)
                writer.writeToSequence(IIOImage(frames[i], null, frameMeta), param)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return outputFile
    }

    // GIF timing resolution is 1/100s. Rounding down to the nearest 10ms
    // keeps our nominal delay honest; clamped to 20ms because many browsers
    // treat <20ms as "use default ~100ms", which silently breaks fast-cadence
    // encodes.
    private fun toCentiseconds(delayMs: Int): Int =
        (delayMs.coerceAtLeast(MIN_FRAME_DELAY_MS) / 10).coerceAtLeast(2)

    /**
     * Writes the per-frame `GraphicControlExtension` (delay + disposal) and,
     * on the first frame only, the `ApplicationExtensions / NETSCAPE2.0`
     * sub-block that switches on infinite looping.
     *
     * `IIOMetadata` is navigated through `javax_imageio_gif_image_1.0`'s tree
     * shape — the names and attribute keys here are what
     * `GIFImageMetadata` declares, not invented by us.
     */
    private fun configureFrameMetadata(
        meta: IIOMetadata,
        delayCentiseconds: Int,
        loopForever: Boolean,
    ) {
        val format = meta.nativeMetadataFormatName
        val root = meta.getAsTree(format) as IIOMetadataNode

        val gce = getOrCreateChild(root, "GraphicControlExtension")
        gce.setAttribute("disposalMethod", "none")
        gce.setAttribute("userInputFlag", "FALSE")
        gce.setAttribute("transparentColorFlag", "FALSE")
        gce.setAttribute("delayTime", delayCentiseconds.toString())
        gce.setAttribute("transparentColorIndex", "0")

        if (loopForever) {
            val appExts = getOrCreateChild(root, "ApplicationExtensions")
            val appExt = IIOMetadataNode("ApplicationExtension")
            appExt.setAttribute("applicationID", "NETSCAPE")
            appExt.setAttribute("authenticationCode", "2.0")
            // 3-byte sub-block: { 0x01, loopCount_lo, loopCount_hi } — 0 = forever.
            appExt.userObject = byteArrayOf(0x1, 0x0, 0x0)
            appExts.appendChild(appExt)
        }

        meta.setFromTree(format, root)
    }

    private fun getOrCreateChild(parent: IIOMetadataNode, name: String): IIOMetadataNode {
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeName.equals(name, ignoreCase = true)) {
                return child as IIOMetadataNode
            }
            child = child.nextSibling
        }
        val created = IIOMetadataNode(name)
        parent.appendChild(created)
        return created
    }
}
