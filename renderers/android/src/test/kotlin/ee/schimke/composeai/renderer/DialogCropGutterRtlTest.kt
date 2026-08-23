package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@CaptureGutter`'s start/end edges are **layout** edges; the dialog-window crop rect is in
 * already-rendered pixels, where an RTL capture has been mirrored. So the leading edge is on the
 * right of the image and the horizontal pair has to swap (issue #4452 review).
 *
 * The wrap box already makes exactly this swap in layout coordinates (`leftPx = if (Rtl) endPx else
 * startPx`); a dialog crop that mapped `start` onto `left` regardless would add an asymmetric
 * gutter to the wrong side of a mirrored capture and leave the overhang it was sized for clipped —
 * which is worse than no gutter, because the canvas grows on the side that needed nothing.
 */
class DialogCropGutterRtlTest {

  private val asymmetric = CaptureGutterDp(start = 2, top = 3, end = 8, bottom = 5)

  @Test
  fun `ltr maps start onto left and end onto right`() {
    val g = dialogCropGutter(asymmetric, density = 2.0f, rtl = false)
    assertEquals(4, g.leftPx)
    assertEquals(16, g.rightPx)
    assertEquals(6, g.topPx)
    assertEquals(10, g.bottomPx)
  }

  @Test
  fun `rtl swaps the horizontal pair and leaves the vertical one alone`() {
    val g = dialogCropGutter(asymmetric, density = 2.0f, rtl = true)
    assertEquals("start is on the right under RTL", 16, g.leftPx)
    assertEquals(4, g.rightPx)
    // Gravity has no say in the vertical edges — a shadow still falls downward in a mirrored
    // capture.
    assertEquals(6, g.topPx)
    assertEquals(10, g.bottomPx)
  }

  @Test
  fun `no gutter is no expansion in either direction`() {
    assertEquals(DialogWindowCapture.DialogCropGutter(), dialogCropGutter(null, 2.0f, rtl = false))
    assertEquals(DialogWindowCapture.DialogCropGutter(), dialogCropGutter(null, 2.0f, rtl = true))
  }

  @Test
  fun `the rtl decision matches the one the qualifier makes`() {
    // Real RTL languages and the RTL pseudolocale mirror; everything else does not. Same rule
    // `applyPreviewQualifiers` uses to emit `ldrtl`, which is the point of sharing the helper.
    assertTrue(previewRendersRtl("ar"))
    assertTrue(previewRendersRtl("he-IL"))
    assertTrue(previewRendersRtl("ar-XB"))
    assertFalse(previewRendersRtl("en-XA"))
    assertFalse(previewRendersRtl("en-US"))
    assertFalse(previewRendersRtl(null))
  }
}
