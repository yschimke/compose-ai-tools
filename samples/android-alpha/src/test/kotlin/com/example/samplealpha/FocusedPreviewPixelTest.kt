package com.example.samplealpha

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end check that `@FocusedPreview` drives real focus and the PNGs reflect it (reading
 * `:samples:android-alpha:composePreviewRenderAll` output). Guards against input mode falling back
 * to Touch (focus refused), off-by-one in the focus walk, overlay regressions (marked `.png` vs
 * unmarked `.raw.png`), and `Previous`/`Next` traversal regressions.
 */
class FocusedPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  // `RenderFilenames` strips the package + class qualifier and converts spaces to underscores
  // for on-disk paths — see docs/RENDER_FILENAMES.md. Kept as plain literals here so the test
  // breaks loudly if filename normalisation changes shape.
  private val fanOutBase = "InsetFocusRingFanOutPreview_Inset_Focus_Ring_fan_out"
  private val traversalBase = "FocusTraversalPreview_Focus_Traversal"
  private val overlayBase = "FocusOverlayPreview_Focus_Overlay"
  private val movingBase = "InsetFocusRingMovingPreview_Inset_Focus_Ring_moving"

  /**
   * The four `_FOCUS_<n>.png` captures must all differ; a Touch fallback would make every capture
   * identical.
   */
  @Test
  fun `fan-out captures differ across focus indices`() {
    val files = (0..3).map { renderFile(rendersDir, fanOutBase, "_FOCUS_$it") }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(4)
  }

  // Where the ring lands is verified by the traversal test's matching hashes; localising it within
  // a capture is defeated by shadow and ring bleed across button boundaries.

  /**
   * `Next, Next, Previous, Next` lands on buttons 0, 1, 0, 1: steps 1 and 3 match, steps 2 and 4
   * match, and step 1 ≠ step 2.
   */
  @Test
  fun `traversal walks Next-Next-Previous-Next as 0-1-0-1`() {
    val step1 = renderFile(rendersDir, traversalBase, "_FOCUS_step1_Next")
    val step2 = renderFile(rendersDir, traversalBase, "_FOCUS_step2_Next")
    val step3 = renderFile(rendersDir, traversalBase, "_FOCUS_step3_Previous")
    val step4 = renderFile(rendersDir, traversalBase, "_FOCUS_step4_Next")
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
   * `@FocusedPreview(gif = true)` emits one stitched `.gif` (non-empty, GIF magic) and no per-step
   * PNGs.
   */
  @Test
  fun `moving inset ring lands at a single gif`() {
    val gif = renderFile(rendersDir, movingBase, ext = "gif")
    assertThat(gif.exists()).isTrue()
    assertThat(gif.length()).isGreaterThan(0L)
    val header = gif.inputStream().use { it.readNBytes(6).toString(Charsets.US_ASCII) }
    assertThat(header).isAnyOf("GIF87a", "GIF89a")
    // No PNG siblings — the gif flag swapped the per-step PNG fan-out for one GIF.
    (0..3).forEach { i ->
      val sibling = renderFile(rendersDir, movingBase, "_FOCUS_$i")
      assertThat(sibling.exists()).isFalse()
    }
  }

  /**
   * Overlay assertions: the marked `.png` carries the renderer's red stroke + label pill, the
   * `.raw.png` companion is the unmarked baseline. Marked must contain a vivid overlay-red pixel,
   * raw must not, and the two files must hash differently.
   */
  @Test
  fun `overlay paints marker on capture and preserves raw baseline`() {
    for (i in 0..3) {
      val marked = renderFile(rendersDir, overlayBase, "_FOCUS_$i")
      val raw = renderFile(rendersDir, overlayBase, "_FOCUS_$i", ext = "raw.png")
      assertThat(marked.exists()).isTrue()
      assertThat(raw.exists()).isTrue()
      assertThat(marked.readBytes().contentHashCode())
        .isNotEqualTo(raw.readBytes().contentHashCode())

      assertThat(hasOverlayRedPixel(ImageIO.read(marked))).isTrue()
      assertThat(hasOverlayRedPixel(ImageIO.read(raw))).isFalse()
    }
  }

  /**
   * Whether [img] has a pixel near the overlay's `(0xFF, 0x40, 0x40)` red (±32 per channel for
   * antialiasing); the scene's purple `(0x65, 0x4F, 0xA4)` can't match.
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
      if (
        kotlin.math.abs(r - targetR) <= 32 &&
          kotlin.math.abs(g - targetG) <= 32 &&
          kotlin.math.abs(b - targetB) <= 32
      ) {
        return true
      }
    }
    return false
  }
}
