package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that `@ScrollingPreview(modes = [TOP, END])`
 * produces two distinct captures from one preview function: an unscrolled
 * top frame and a scrolled-to-end frame. Reads the PNGs produced by
 * `:samples:android:renderAllPreviews` and asserts colour dominance matches
 * the expected top (red) / bottom (blue) of [RedToBlueList].
 *
 * The `renderAllPreviews` task is wired into this module's `test` task
 * dependency graph in build.gradle.kts so running `:samples:android:test` (or
 * `:check`) renders the PNGs first.
 */
class ScrollPreviewPixelTest {

    private val rendersDir = File("build/compose-previews/renders")
    private val baseName = "ScrollPreviewsKt.RedToBlueScrollPreview_Scroll"

    private data class Avg(val r: Double, val g: Double, val b: Double) {
        fun dominant(): Char = when {
            r > g && r > b -> 'R'
            g > r && g > b -> 'G'
            else -> 'B'
        }
    }

    private fun averageColor(file: File): Avg {
        val img = ImageIO.read(file)
        var rs = 0L
        var gs = 0L
        var bs = 0L
        val w = img.width
        val h = img.height
        for (y in 0 until h) for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            rs += (argb shr 16) and 0xff
            gs += (argb shr 8) and 0xff
            bs += argb and 0xff
        }
        val n = (w * h).toDouble()
        return Avg(rs / n, gs / n, bs / n)
    }

    @Test
    fun `TOP capture is red-dominant`() {
        val file = File(rendersDir, "${baseName}_SCROLL_top.png")
        assertThat(file.exists()).isTrue()
        val avg = averageColor(file)
        assertThat(avg.dominant()).isEqualTo('R')
        assertThat(avg.r).isGreaterThan(150.0)
        assertThat(avg.b).isLessThan(120.0)
    }

    @Test
    fun `END capture is blue-dominant`() {
        val file = File(rendersDir, "${baseName}_SCROLL_end.png")
        assertThat(file.exists()).isTrue()
        val avg = averageColor(file)
        // If scroll-to-end silently fails, the image is red-dominant — this
        // is exactly the regression this test guards against.
        assertThat(avg.dominant()).isEqualTo('B')
        assertThat(avg.b).isGreaterThan(150.0)
        assertThat(avg.r).isLessThan(120.0)
    }

    /**
     * End-to-end check of the [ScrollMode.GIF] pipeline: driver captures
     * each frame, encoder lays them into a NETSCAPE-looping GIF, and a
     * standard `ImageIO` reader on the other side can decode the frames
     * back out.
     *
     * Keyed off the single-mode `GIF` annotation on [RedToBlueScrollGifPreview],
     * so the output file is `...RedToBlueScrollGifPreview_ScrollGif.gif`
     * without the `_SCROLL_gif` suffix multi-mode would add.
     */
    @Test
    fun `GIF capture animates red to blue`() {
        val gifName = "ScrollPreviewsKt.RedToBlueScrollGifPreview_ScrollGif.gif"
        val file = File(rendersDir, gifName)
        assertThat(file.exists()).isTrue()

        val frames = readGifFrames(file)
        // Need at least two frames for an animation, and the driver should
        // typically produce many more (several per viewport).
        assertThat(frames.size).isAtLeast(2)

        val first = avgOfImage(frames.first())
        val last = avgOfImage(frames.last())
        // Frame 0 is the unscrolled top → red-dominant.
        assertThat(first.dominant()).isEqualTo('R')
        // Final frame is at (or near) the end → blue-dominant. Wider
        // tolerances than the PNG END test because GIF quantisation shifts
        // per-channel averages by a few points.
        assertThat(last.dominant()).isEqualTo('B')
        assertThat(last.b).isGreaterThan(130.0)
        assertThat(last.r).isLessThan(140.0)
    }

    /**
     * Regression guard for issue #154: a `@ScrollingPreview` with
     * `modes = [END, GIF]` shares one composition across captures, so END
     * leaves the scrollable at the bottom. Before the fix the follow-up
     * GIF capture was a single frame indistinguishable from END
     * (blue-dominant throughout). With the scroll reset, frame 0 should
     * be red-dominant again.
     */
    @Test
    fun `GIF capture following END resets scroll and still animates`() {
        val base = "ScrollPreviewsKt.RedToBlueEndThenGifPreview_EndThenGif"
        val endPng = File(rendersDir, "${base}_SCROLL_end.png")
        val gif = File(rendersDir, "${base}_SCROLL_gif.gif")
        assertThat(endPng.exists()).isTrue()
        assertThat(gif.exists()).isTrue()

        // END still lands on the bottom of the gradient (sanity check the
        // earlier capture wasn't accidentally disturbed by the reset).
        val endAvg = averageColor(endPng)
        assertThat(endAvg.dominant()).isEqualTo('B')

        val frames = readGifFrames(gif)
        // The driver should produce many frames per viewport scrolled;
        // a 1-frame GIF is the pre-fix regression signature.
        assertThat(frames.size).isAtLeast(2)

        val first = avgOfImage(frames.first())
        val last = avgOfImage(frames.last())
        // Pre-fix: `first` was blue-dominant because the GIF started from
        // wherever END left the scrollable.
        assertThat(first.dominant()).isEqualTo('R')
        assertThat(last.dominant()).isEqualTo('B')
    }

    private fun avgOfImage(img: BufferedImage): Avg {
        var rs = 0L
        var gs = 0L
        var bs = 0L
        val w = img.width
        val h = img.height
        for (y in 0 until h) for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            rs += (argb shr 16) and 0xff
            gs += (argb shr 8) and 0xff
            bs += argb and 0xff
        }
        val n = (w * h).toDouble()
        return Avg(rs / n, gs / n, bs / n)
    }

    /**
     * Reads every frame of an animated GIF into a list of [BufferedImage].
     * Uses the standard `javax.imageio` GIF reader plugin — same plugin
     * [ScrollGifEncoder] writes against, so this doubles as a round-trip
     * check on the encoder's metadata tree.
     */
    private fun readGifFrames(file: File): List<BufferedImage> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        javax.imageio.stream.FileImageInputStream(file).use { input ->
            reader.input = input
            val count = reader.getNumImages(true)
            return List(count) { reader.read(it) }
        }
    }
}
