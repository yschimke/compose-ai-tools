package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the qualifier triple every Robolectric render path emits for its viewport. The regression
 * this guards is issue #3309: without a `sw<n>dp` token, Robolectric's baseline `sw320dp` survives
 * the incremental `setQualifiers("+…")`, so a 227dp round Wear preview rendered with `screenWidthDp
 * == 227` but `smallestScreenWidthDp == 320`.
 */
class PreviewSizeQualifiersTest {

  @Test
  fun `smallest width is emitted and precedes available width`() {
    assertEquals(
      listOf("sw227dp", "w227dp", "h227dp"),
      previewSizeQualifiers(widthDp = 227, heightDp = 227),
    )
  }

  @Test
  fun `smallest width is the narrower axis, not the width`() {
    assertEquals(
      listOf("sw411dp", "w411dp", "h891dp"),
      previewSizeQualifiers(widthDp = 411, heightDp = 891),
    )
    // Landscape: the height is now the narrow axis, and swdp follows it — smallestScreenWidthDp is
    // rotation-invariant on a real device.
    assertEquals(
      listOf("sw411dp", "w891dp", "h411dp"),
      previewSizeQualifiers(widthDp = 891, heightDp = 411),
    )
  }

  @Test
  fun `a non-positive axis is dropped rather than emitted as zero`() {
    assertEquals(listOf("sw320dp", "w320dp"), previewSizeQualifiers(widthDp = 320, heightDp = 0))
    assertEquals(listOf("sw480dp", "h480dp"), previewSizeQualifiers(widthDp = -1, heightDp = 480))
    assertEquals(emptyList(), previewSizeQualifiers(widthDp = 0, heightDp = 0))
  }

  @Test
  fun `orientation qualifier follows the frame, not the request`() {
    // The case that matters: explicit pixels outrank the rotation (PROTOCOL.md § 5), so the frame
    // stays landscape and the qualifier must say so. Trusting the request would hand the resource
    // framework and Configuration.orientation a shape the bitmap does not have.
    assertEquals("land", previewOrientationQualifier(1000, 200, requested = "port"))
    assertEquals("port", previewOrientationQualifier(200, 1000, requested = "land"))

    // When the rotation did happen upstream, the dimensions already carry the request — deriving
    // from them agrees rather than conflicts.
    assertEquals("port", previewOrientationQualifier(800, 1600, requested = "port"))
    assertEquals("land", previewOrientationQualifier(1600, 800, requested = "land"))

    // No request at all: the frame still decides.
    assertEquals("land", previewOrientationQualifier(1280, 800, requested = null))
    assertEquals("port", previewOrientationQualifier(411, 891, requested = null))
  }

  @Test
  fun `a square frame defers to the request`() {
    // Only here can the dimensions not say. A Wear round 227x227 asked to be landscape reports it.
    assertEquals("land", previewOrientationQualifier(227, 227, requested = "land"))
    assertEquals("port", previewOrientationQualifier(227, 227, requested = "port"))
    // Unset or unrecognised falls back to `port`, matching Android's Configuration at equal axes.
    assertEquals("port", previewOrientationQualifier(227, 227, requested = null))
    assertEquals("port", previewOrientationQualifier(227, 227, requested = "sideways"))
  }

  @Test
  fun `a non-positive axis has nothing to say`() {
    assertNull(previewOrientationQualifier(0, 800, requested = "port"))
    assertNull(previewOrientationQualifier(800, 0, requested = null))
    assertNull(previewOrientationQualifier(-1, -1, requested = "land"))
  }
}
