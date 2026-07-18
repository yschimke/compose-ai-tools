package ee.schimke.composeai.daemon

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [pixelAssertVerdict] golden-image verdict logic (issues #1967, #2519) —
 * the part of the `assert.pixels` recording handler that doesn't need a held scene or file IO.
 * Lives in `:daemon:core` alongside the verdict it covers (relocated from `:daemon:desktop` when
 * Android gained `assert.pixels` too). The wired handlers (deferred post-loop diff against the
 * frame each session wrote) are covered by the integration tests in `DesktopRecordingSessionTest` /
 * `AndroidRecordingSessionTest`.
 */
class PixelAssertionsTest {

  @Test
  fun fails_closed_when_baseline_missing() {
    val verdict = pixelAssertVerdict(actualPng = solidPng(8, 8, 0xFFFFFF), baselinePng = null)
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("baseline"))
  }

  @Test
  fun fails_closed_when_recorded_frame_missing() {
    val verdict = pixelAssertVerdict(actualPng = null, baselinePng = solidPng(8, 8, 0xFFFFFF))
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("frame"))
  }

  @Test
  fun passes_on_identical_frames() {
    val a = solidPng(8, 8, 0x3366CC)
    val b = solidPng(8, 8, 0x3366CC)
    assertEquals(AssertionVerdict.Passed, pixelAssertVerdict(a, b))
  }

  @Test
  fun fails_on_drift_beyond_tolerance_and_reports_delta() {
    // Solid black vs solid white: every channel maxed out, far beyond PixelDiff's default cap.
    val verdict = pixelAssertVerdict(solidPng(8, 8, 0x000000), solidPng(8, 8, 0xFFFFFF))
    assertTrue(verdict is AssertionVerdict.Failed)
    val reason = (verdict as AssertionVerdict.Failed).reason
    assertTrue("reports maxDelta; got $reason", reason.contains("maxDelta"))
  }

  @Test
  fun fails_on_dimension_mismatch() {
    val verdict = pixelAssertVerdict(solidPng(4, 4, 0xFFFFFF), solidPng(8, 8, 0xFFFFFF))
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("dimension"))
  }

  private fun solidPng(width: Int, height: Int, rgb: Int): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, rgb)
    return ByteArrayOutputStream().use {
      ImageIO.write(img, "png", it)
      it.toByteArray()
    }
  }
}
