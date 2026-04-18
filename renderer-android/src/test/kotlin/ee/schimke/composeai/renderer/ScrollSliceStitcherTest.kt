package ee.schimke.composeai.renderer

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for [stitchSlices]. Builds a tall synthetic "source" image
 * with a unique colour band per horizontal row, carves viewport-sized windows
 * out of it at known positions, and verifies the stitcher recomposes the
 * source from the slices — byte-exact.
 *
 * The `overreported` test simulates the real-world bug we've seen on
 * `TransformingLazyColumn` (see the duplicated "Allow LTE" row on the Wear
 * settings screen): the framework's semantic `scrolledLayoutPx` advances
 * faster than the image content actually moves, so a stitcher that trusts
 * that value re-prints a band of pixels at the seam. A content-aware stitcher
 * locks onto the real overlap instead.
 */
class ScrollSliceStitcherTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `reconstructs source when scroll offsets honestly match pixel motion`() {
        val source = buildSource(width = 40, height = 500)
        val viewport = 100
        val offsets = listOf(0, 80, 160, 240, 320, 400)

        val slices = offsets.mapIndexed { i, off ->
            SliceCapture(off.toFloat(), writeSlice(source, off, viewport, "honest_$i"))
        }

        val out = tmp.newFile("stitched_honest.png")
        val written = stitchSlices(slices, viewport, out)
        assertTrue("stitchSlices returned null", written != null)

        val stitched = ImageIO.read(out)
        assertEquals(500, stitched.height)
        assertPixelsEqual(source, stitched)
    }

    @Test
    fun `reconstructs source even when scroll offsets overreport motion`() {
        val source = buildSource(width = 40, height = 500)
        val viewport = 100
        // Actual pixel motion per step: 80 (20% overlap between consecutive slices).
        val actualOffsets = listOf(0, 80, 160, 240, 320, 400)
        // Framework claims it advanced a full viewport (100) each time — a lie.
        // A naïve stitcher keying on scrolledLayoutPx will skip over real content.
        val reportedOffsets = listOf(0, 100, 200, 300, 400, 500)

        val slices = actualOffsets.zip(reportedOffsets).mapIndexed { i, (actual, reported) ->
            SliceCapture(reported.toFloat(), writeSlice(source, actual, viewport, "lied_$i"))
        }

        val out = tmp.newFile("stitched_lied.png")
        val written = stitchSlices(slices, viewport, out)
        assertTrue("stitchSlices returned null", written != null)

        val stitched = ImageIO.read(out)
        // viewport (100) + 5 × measured overlap delta (80) = 500.
        assertEquals(500, stitched.height)
        assertPixelsEqual(source, stitched)
    }

    @Test
    fun `reconstructs source when scroll offsets underreport motion`() {
        val source = buildSource(width = 40, height = 600)
        val viewport = 100
        // Slice was actually advanced 80 px per step, but framework claims 50.
        val actualOffsets = listOf(0, 80, 160, 240, 320, 400, 480)
        val reportedOffsets = listOf(0, 50, 100, 150, 200, 250, 300)

        val slices = actualOffsets.zip(reportedOffsets).mapIndexed { i, (actual, reported) ->
            SliceCapture(reported.toFloat(), writeSlice(source, actual, viewport, "low_$i"))
        }

        val out = tmp.newFile("stitched_low.png")
        stitchSlices(slices, viewport, out) ?: error("stitchSlices returned null")

        val stitched = ImageIO.read(out)
        assertEquals(100 + 6 * 80, stitched.height)
        val expected = source.getSubimage(0, 0, source.width, 100 + 6 * 80)
        assertPixelsEqual(expected, stitched)
    }

    @Test
    fun `alignment is driven by interesting rows, not blank background`() {
        // Source: mostly-uniform grey background (low-but-nonzero stddev) with a
        // single very distinctive high-contrast "text" row placed inside every
        // slice-pair's overlap region. A naive matcher could align anywhere in
        // the grey; the weighted matcher should lock onto the distinctive rows.
        val width = 40
        val height = 500
        val source = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        // Grey-ish background with tiny horizontal jitter — stddev is small
        // but non-zero, so these rows contribute to the weighted score only
        // mildly.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = 0x40 + ((x + y) and 0x07) // 64..71
                source.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        // High-variance "text-like" rows at y ≡ 8 (mod 20) — guaranteed to
        // appear inside every 80-px shift's 20-px overlap.
        for (y in 0 until height) {
            if (y % 20 == 8) {
                for (x in 0 until width) {
                    // Large amplitude chequered pattern → huge horizontal stddev.
                    val on = ((x + y) and 1) != 0
                    val v = if (on) 0xEE else 0x11
                    source.setRGB(
                        x,
                        y,
                        (0xFF shl 24) or ((v xor ((y * 7) and 0xFF)) shl 16) or
                            (v shl 8) or ((v xor 0x5A) and 0xFF),
                    )
                }
            }
        }

        val viewport = 100
        // Actual physical scroll = 80 px per step (20 px overlap).
        // Reported offsets lie about the step size.
        val actualOffsets = listOf(0, 80, 160, 240, 320, 400)
        val reportedOffsets = listOf(0, 100, 200, 300, 400, 500)

        val slices = actualOffsets.zip(reportedOffsets).mapIndexed { i, (actual, reported) ->
            SliceCapture(reported.toFloat(), writeSlice(source, actual, viewport, "text_$i"))
        }

        val out = tmp.newFile("stitched_text.png")
        stitchSlices(slices, viewport, out) ?: error("stitchSlices returned null")

        val stitched = ImageIO.read(out)
        assertEquals(500, stitched.height)
        assertPixelsEqual(source, stitched)
    }

    @Test
    fun `single slice round-trips unchanged`() {
        val source = buildSource(width = 40, height = 100)
        val slices = listOf(
            SliceCapture(0f, writeSlice(source, 0, 100, "single")),
        )
        val out = tmp.newFile("stitched_single.png")
        stitchSlices(slices, 100, out) ?: error("stitchSlices returned null")
        assertPixelsEqual(source, ImageIO.read(out))
    }

    @Test
    fun `empty slice list returns null without writing a file`() {
        val out = tmp.newFile("should_not_exist.png")
        out.delete()
        val result = stitchSlices(emptyList(), viewportLayoutPx = 100, outputFile = out)
        assertEquals(null, result)
        assertEquals(false, out.exists())
    }

    /**
     * Build a tall "source" image where every pixel has a pseudo-random
     * colour derived from both (x, y). Two properties matter for the test:
     *  - Each row has high horizontal variation (non-zero stddev), mimicking
     *    real screenshots where there's always *some* content per row — so
     *    the weighted matcher has a signal to grip onto.
     *  - Two rows at different y never accidentally share a pixel pattern,
     *    so no two shifts look equally plausible to the matcher.
     */
    private fun buildSource(width: Int, height: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Integer hash of (x, y) → pseudo-random 24-bit colour. The
                // bit-mixing makes neighbouring rows visually distinct and
                // keeps per-row horizontal stddev high.
                var h = y * -1640531535 xor (x * 1597334677)
                h = h xor (h ushr 13)
                h *= 5
                val rgb = h and 0x00FFFFFF
                img.setRGB(x, y, (0xFF shl 24) or rgb)
            }
        }
        return img
    }

    private fun writeSlice(source: BufferedImage, y0: Int, viewport: Int, label: String): File {
        val slice = source.getSubimage(0, y0, source.width, viewport)
        val file = tmp.newFile("slice_${label}.png")
        ImageIO.write(slice, "PNG", file)
        return file
    }

    private fun assertPixelsEqual(expected: BufferedImage, actual: BufferedImage) {
        assertEquals("width", expected.width, actual.width)
        assertEquals("height", expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val e = expected.getRGB(x, y)
                val a = actual.getRGB(x, y)
                if (e != a) {
                    throw AssertionError(
                        "pixel mismatch at ($x, $y): expected 0x${Integer.toHexString(e)}, " +
                            "got 0x${Integer.toHexString(a)}",
                    )
                }
            }
        }
    }
}
