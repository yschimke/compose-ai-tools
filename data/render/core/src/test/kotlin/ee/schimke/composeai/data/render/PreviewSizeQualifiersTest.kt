package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
