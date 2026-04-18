package ee.schimke.composeai.renderer

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One captured slice — the on-disk PNG plus the cumulative scroll offset
 * (in layout pixels) the scrollable reported at the time of capture.
 */
internal data class SliceCapture(val scrolledLayoutPx: Float, val file: File)

/**
 * Stitches per-viewport slices (see [driveScrollByViewport]) into a single
 * tall PNG at [outputFile].
 *
 * Geometry:
 *
 * Slice 0 was captured at scroll offset 0 — it covers output rows
 * `[0, sliceH)`. For each subsequent slice at offset `s_i`:
 *  - delta = round((s_i - s_{i-1}) × pxPerLayoutPx)
 *  - Source rows `[sliceH - delta, sliceH)` (the bottom `delta` rows).
 *  - Dest rows `[y_prev, y_prev + delta)`, where `y_prev` starts at
 *    `sliceH` and advances by `delta` per slice.
 *
 * `pxPerLayoutPx` is the ratio between captured PNG pixel height and the
 * layout-pixel viewport height — `captureRoboImage` under Roborazzi writes
 * at the Robolectric qualifier density, which often equals layout units 1:1
 * but can diverge. Computing the ratio from slice 0 keeps the stitcher
 * honest about any difference.
 *
 * Output height = `sliceH + lastOffsetInImagePx`.
 *
 * Returns the written file, or `null` if [slices] is empty.
 */
internal fun stitchSlices(
    slices: List<SliceCapture>,
    viewportLayoutPx: Int,
    outputFile: File,
): File? {
    if (slices.isEmpty()) return null

    val firstImage = ImageIO.read(slices[0].file)
        ?: error("Failed to read first slice PNG: ${slices[0].file}")
    val width = firstImage.width
    val sliceH = firstImage.height
    val pxPerLayoutPx = sliceH.toDouble() / viewportLayoutPx.toDouble()

    val imageOffsets = slices.map { (it.scrolledLayoutPx * pxPerLayoutPx).roundToInt() }
    val lastOffsetPx = imageOffsets.last()
    val totalHeight = sliceH + lastOffsetPx

    val stitched = BufferedImage(width, totalHeight, BufferedImage.TYPE_INT_ARGB)
    val g = stitched.createGraphics()
    try {
        g.drawImage(firstImage, 0, 0, null)

        var yPrev = sliceH
        for (i in 1 until slices.size) {
            val img = ImageIO.read(slices[i].file)
                ?: error("Failed to read slice PNG: ${slices[i].file}")
            require(img.width == width && img.height == sliceH) {
                "Slice dimensions drifted: expected ${width}x$sliceH, got ${img.width}x${img.height} at index $i"
            }
            val delta = imageOffsets[i] - imageOffsets[i - 1]
            if (delta <= 0) continue // duplicate capture — framework reported no motion

            g.drawImage(
                img,
                0, yPrev, width, yPrev + delta,
                0, sliceH - delta, width, sliceH,
                null,
            )
            yPrev += delta
        }
    } finally {
        g.dispose()
    }

    outputFile.parentFile?.mkdirs()
    ImageIO.write(stitched, "PNG", outputFile)
    return outputFile
}

/**
 * Clips [file]'s image into a pill/stadium shape: half-circle at the top,
 * rectangular middle, half-circle at the bottom. Width determines the circle
 * diameter. Pixels outside the shape become transparent.
 *
 * Used on stitched `@ScrollingPreview(LONG)` outputs for round Wear devices,
 * so the rendered scroll visually preserves the round screen edge at the top
 * of the first frame and the bottom of the last frame.
 */
internal fun applyWearPillClip(file: File) {
    val src = ImageIO.read(file) ?: return
    val w = src.width
    val h = src.height
    if (h <= 0 || w <= 0) return

    val radius = w / 2.0

    // Union of: top half-circle (centred at y=r), middle rectangle, bottom
    // half-circle (centred at y=h-r). For h < 2r (too short to be a proper
    // pill), fall back to a single ellipse.
    val pill: Area = if (h >= 2 * radius) {
        Area(Ellipse2D.Double(0.0, 0.0, w.toDouble(), 2 * radius)).apply {
            add(Area(Rectangle2D.Double(0.0, radius, w.toDouble(), h - 2 * radius)))
            add(Area(Ellipse2D.Double(0.0, h - 2 * radius, w.toDouble(), 2 * radius)))
        }
    } else {
        Area(Ellipse2D.Double(0.0, 0.0, w.toDouble(), min(h.toDouble(), 2 * radius)))
    }

    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.composite = AlphaComposite.Src
        g.color = Color(0, 0, 0, 0)
        g.fillRect(0, 0, w, h)
        g.clip = pill
        g.drawImage(src, 0, 0, null)
    } finally {
        g.dispose()
    }
    ImageIO.write(out, "PNG", file)
}
