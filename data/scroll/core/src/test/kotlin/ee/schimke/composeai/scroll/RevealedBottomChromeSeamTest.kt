package ee.schimke.composeai.scroll

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The last stride of a Wear `TransformingLazyColumn` walk lands at the content end, and landing
 * there reveals the `ScreenScaffold`'s `EdgeButton`: the last slice's bottom fifth is a bright
 * button where the previous slice, one stride earlier, had black scaffold background and a peek
 * pill. Every other row of the pair is a pixel-exact translation.
 *
 * `wear-edge-reveal/prev.png` and `next.png` are that pair verbatim from `:samples:wear`'s
 * `ActivityListLongPreview` (slices 5 and 6 of a 454 px Large Round walk; the driver measured the
 * stride at 156 px). Row profile at 156 px: rows 0–249 of `next` match `prev` at 0.0/px, rows
 * 250–297 are the EdgeButton against black at 40–160/px.
 *
 * The matcher used to score the whole overlap, so the revealed band dragged the true shift to a
 * 40/px residual — a `mismatch` verdict on a seam that is right — and, given only the scroller's
 * hint rather than a measured one, preferred a 198 px shift that lines the button up with a card
 * body (28/px) over the truth (40/px). Chrome that *appears* at the foot of a slice is not evidence
 * of misalignment: it is a contiguous run of disagreeing rows at the bottom of the overlap, and the
 * stitcher scores the rows above it.
 */
class RevealedBottomChromeSeamTest {
  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private val viewport = 454
  private val trueShift = 156f

  private fun fixture(name: String): File {
    val resource =
      checkNotNull(javaClass.getResource("/wear-edge-reveal/$name")) { "missing fixture $name" }
    return File(resource.toURI())
  }

  private fun stitch(measured: Boolean, hint: Float): Pair<ScrollSeam, BufferedImage> {
    val slices =
      listOf(
        SliceCapture(0f, fixture("prev.png"), measured = measured),
        SliceCapture(hint, fixture("next.png"), measured = measured),
      )
    val out = tmp.newFile("reveal_${measured}_${hint.toInt()}.png")
    val seams = mutableListOf<ScrollSeam>()
    stitchSlices(slices, viewport, out) { seams += it } ?: error("stitchSlices returned null")
    return seams.single() to ImageIO.read(out)
  }

  @Test
  fun `a revealed EdgeButton at the foot of the last slice does not fail the seam`() {
    val (seam, _) = stitch(measured = true, hint = trueShift)
    assertEquals(seam.describe(), trueShift.toInt(), seam.shiftPx)
    assertEquals(seam.describe(), ScrollSeam.Verdict.VERIFIED, seam.verdict)
    assertTrue(
      "the residual over the list rows is a pixel-exact translation: ${seam.describe()}",
      seam.weightedSadPerPixel < 1.0,
    )
  }

  @Test
  fun `the revealed band cannot drag a reported hint to a wrong shift`() {
    // The scroller's own word for the stride — `driveScrollByViewport` credits the full 363 px plan
    // when it cannot measure — so the matcher searches the wide window and must still land on 156.
    val (seam, stitched) = stitch(measured = false, hint = 363f)
    assertEquals(seam.describe(), trueShift.toInt(), seam.shiftPx)
    assertEquals(seam.describe(), ScrollSeam.Verdict.VERIFIED, seam.verdict)
    assertEquals(viewport + trueShift.toInt(), stitched.height)
  }
}
