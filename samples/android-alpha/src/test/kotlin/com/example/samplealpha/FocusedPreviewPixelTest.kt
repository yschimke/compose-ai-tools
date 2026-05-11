package com.example.samplealpha

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that `@FocusedPreview` actually drives focus through the renderer's
 * Compose pipeline and that the resulting PNGs reflect the requested focus state. Reads the files
 * produced by `:samples:android-alpha:renderAllPreviews` (wired into this module's `test` task in
 * `build.gradle.kts`) and pixel-asserts on them.
 *
 * What this guards against:
 *
 * * Renderer regressions where `LocalInputModeManager` falls back to Touch and Compose's clickable
 *   focusable refuses focus — captures come back identical to a no-focus run.
 * * Off-by-one breakages in the `moveFocus(Enter) + Next * (n + 1)` walk — focus would land on the
 *   wrong button and every fan-out PNG would shift one slot.
 * * Overlay regressions — the red marker rect / `index N` pill is what reviewers actually see, so
 *   we assert the marked pixels are present *and* that the unmarked `.raw.png` companion was
 *   preserved.
 * * Traversal-mode regressions — `Previous` and `Next` should land on different buttons, and
 *   `Next, Next, Previous, Next` should walk a known sequence.
 */
class FocusedPreviewPixelTest {

    private val rendersDir = File("build/compose-previews/renders")

    // `RenderFilenames` strips the package + class qualifier and converts spaces to underscores
    // for on-disk paths — see docs/RENDER_FILENAMES.md. Kept as plain literals here so the test
    // breaks loudly if filename normalisation changes shape.
    private val fanOutBase = "InsetFocusRingFanOutPreview_Inset_Focus_Ring_fan-out"
    private val traversalBase = "FocusTraversalPreview_Focus_Traversal"
    private val overlayBase = "FocusOverlayPreview_Focus_Overlay"
    private val movingBase = "InsetFocusRingMovingPreview_Inset_Focus_Ring_moving"

    /**
     * The four `_FOCUS_<n>.png` fan-out captures must all differ from each other — same
     * composition, focus driven to a different button per capture. If [LocalInputModeManager] falls
     * back to Touch, every capture renders without a focus indicator and all four hashes collapse.
     */
    @Test
    fun `fan-out captures differ across focus indices`() {
        val files = (0..3).map { File(rendersDir, "${fanOutBase}_FOCUS_$it.png") }
        files.forEach { assertThat(it.exists()).isTrue() }
        val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
        assertThat(hashes).hasSize(4)
    }

    /**
     * Where the focus ring lands per capture is verified indirectly by the traversal test:
     * `Next, Next, Previous, Next` produces four captures in `0, 1, 0, 1` order, so step 1's hash
     * must match step 3's and step 2's must match step 4's. If the renderer dispatched directions
     * to wrong slots, those hashes would diverge. Trying to localise the ring inside fan-out
     * captures via column-banded pixel counts was attempted and abandoned: Material's elevation
     * shadow + the ring's outer stroke bleed across button boundaries by ~1.5% of the slot's
     * pixels, which is enough to defeat any clean "untargeted slot is byte-identical" assertion
     * without complicating the test with button-geometry math.
     */

    /**
     * Traversal mode: `Next, Next, Previous, Next` should land on buttons 0, 1, 0, 1.
     * After Next then Previous we should be back where we started, so step 1 and step 3 produce
     * pixel-identical captures; step 2 and step 4 likewise. Step 1 ≠ step 2 (Next moves forward).
     */
    @Test
    fun `traversal walks Next-Next-Previous-Next as 0-1-0-1`() {
        val step1 = File(rendersDir, "${traversalBase}_FOCUS_step1_Next.png")
        val step2 = File(rendersDir, "${traversalBase}_FOCUS_step2_Next.png")
        val step3 = File(rendersDir, "${traversalBase}_FOCUS_step3_Previous.png")
        val step4 = File(rendersDir, "${traversalBase}_FOCUS_step4_Next.png")
        listOf(step1, step2, step3, step4).forEach { assertThat(it.exists()).isTrue() }

        val h1 = step1.readBytes().contentHashCode()
        val h2 = step2.readBytes().contentHashCode()
        val h3 = step3.readBytes().contentHashCode()
        val h4 = step4.readBytes().contentHashCode()

        assertThat(h1).isEqualTo(h3)
        assertThat(h2).isEqualTo(h4)
        assertThat(h1).isNotEqualTo(h2)
    }

    /**
     * `@FocusedPreview(gif = true)`: discovery should emit one stitched `.gif` instead of N
     * `_FOCUS_<n>.png` siblings, and the GIF mode must drive focus through the renderer's
     * `FocusManager.moveFocus` walk — so the file exists, is non-empty, has the GIF magic
     * header, and no per-step PNGs leaked alongside (the whole point of the gif flag was to
     * collapse the sample's hand-rolled `LaunchedEffect` focus emission, #1020).
     */
    @Test
    fun `moving inset ring lands at a single gif`() {
        val gif = File(rendersDir, "$movingBase.gif")
        assertThat(gif.exists()).isTrue()
        assertThat(gif.length()).isGreaterThan(0L)
        val header = gif.inputStream().use { it.readNBytes(6).toString(Charsets.US_ASCII) }
        assertThat(header).isAnyOf("GIF87a", "GIF89a")
        // No PNG siblings — the gif flag swapped the per-step PNG fan-out for one GIF.
        (0..3).forEach { i ->
            val sibling = File(rendersDir, "${movingBase}_FOCUS_$i.png")
            assertThat(sibling.exists()).isFalse()
        }
    }

    /**
     * Overlay assertions: the marked `.png` carries the renderer's red stroke + label pill, the
     * `.raw.png` companion is the unmarked baseline. Marked must contain a vivid overlay-red
     * pixel, raw must not, and the two files must hash differently.
     */
    @Test
    fun `overlay paints marker on capture and preserves raw baseline`() {
        for (i in 0..3) {
            val marked = File(rendersDir, "${overlayBase}_FOCUS_$i.png")
            val raw = File(rendersDir, "${overlayBase}_FOCUS_$i.raw.png")
            assertThat(marked.exists()).isTrue()
            assertThat(raw.exists()).isTrue()
            assertThat(marked.readBytes().contentHashCode())
                .isNotEqualTo(raw.readBytes().contentHashCode())

            assertThat(hasOverlayRedPixel(ImageIO.read(marked))).isTrue()
            assertThat(hasOverlayRedPixel(ImageIO.read(raw))).isFalse()
        }
    }

    /**
     * `true` when [img] contains a pixel close to the overlay's `(0xFF, 0x40, 0x40)` red. Wide
     * tolerance per channel (±32) absorbs Java2D's antialiased stroke edges. The body purple in
     * this composition is `(0x65, 0x4F, 0xA4)`, so the high-red / low-green combination cleanly
     * distinguishes overlay paint from any natural pixel in the scene.
     */
    private fun hasOverlayRedPixel(img: BufferedImage): Boolean {
        val targetR = 0xFF
        val targetG = 0x40
        val targetB = 0x40
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            if (kotlin.math.abs(r - targetR) <= 32 &&
                kotlin.math.abs(g - targetG) <= 32 &&
                kotlin.math.abs(b - targetB) <= 32
            ) {
                return true
            }
        }
        return false
    }
}
