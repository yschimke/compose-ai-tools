package ee.schimke.composeai.daemon.devices

import ee.schimke.composeai.daemon.protocol.Orientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameOrientationTest {

  @Test
  fun `portrait rotates a landscape frame`() {
    // The reported shape: Pixel Tablet is 1280x800dp at 2.0 => 2560x1600px, landscape.
    assertEquals(1600 to 2560, FrameOrientation.orientedPx(2560, 1600, Orientation.PORTRAIT))
  }

  @Test
  fun `landscape rotates a portrait frame`() {
    assertEquals(2560 to 1600, FrameOrientation.orientedPx(1600, 2560, Orientation.LANDSCAPE))
  }

  @Test
  fun `a frame that already satisfies the request is untouched`() {
    assertEquals(2560 to 1600, FrameOrientation.orientedPx(2560, 1600, Orientation.LANDSCAPE))
    assertEquals(1600 to 2560, FrameOrientation.orientedPx(1600, 2560, Orientation.PORTRAIT))
  }

  @Test
  fun `applying the same request twice is a no-op`() {
    // Idempotence is what lets every layer apply the swap without tracking whether an earlier one
    // already did. Losing it would rotate a frame back to landscape at the second call site.
    val once = FrameOrientation.orientedPx(2560, 1600, Orientation.PORTRAIT)
    val twice = FrameOrientation.orientedPx(once.first, once.second, Orientation.PORTRAIT)
    assertEquals(once, twice)
    assertEquals(1600 to 2560, twice)
  }

  @Test
  fun `a square frame is never swapped`() {
    assertEquals(454 to 454, FrameOrientation.orientedPx(454, 454, Orientation.PORTRAIT))
    assertEquals(454 to 454, FrameOrientation.orientedPx(454, 454, Orientation.LANDSCAPE))
  }

  @Test
  fun `no request leaves the frame alone`() {
    assertEquals(2560 to 1600, FrameOrientation.orientedPx(2560, 1600, null as Orientation?))
  }

  @Test
  fun `payload tokens parse case-insensitively`() {
    assertEquals(Orientation.PORTRAIT, FrameOrientation.parse("portrait"))
    assertEquals(Orientation.PORTRAIT, FrameOrientation.parse("Portrait"))
    assertEquals(Orientation.LANDSCAPE, FrameOrientation.parse(" LANDSCAPE "))
  }

  @Test
  fun `an absent or unrecognised token is treated as no request`() {
    assertNull(FrameOrientation.parse(null))
    assertNull(FrameOrientation.parse(""))
    assertNull(FrameOrientation.parse("sideways"))
    assertEquals(2560 to 1600, FrameOrientation.orientedPx(2560, 1600, "sideways"))
  }

  @Test
  fun `the string overload agrees with the enum overload`() {
    assertEquals(
      FrameOrientation.orientedPx(2560, 1600, Orientation.PORTRAIT),
      FrameOrientation.orientedPx(2560, 1600, "portrait"),
    )
  }
}
