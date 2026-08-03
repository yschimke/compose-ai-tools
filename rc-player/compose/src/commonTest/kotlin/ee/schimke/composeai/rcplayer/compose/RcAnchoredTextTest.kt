package ee.schimke.composeai.rcplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RcAnchoredTextTest {
  @Test
  fun matchesAndroidXPanAndInkBoundsFormula() {
    val centered = computeAnchoredTextPosition(100f, 80f, 0f, 0f, -2f, -12f, 38f, 4f, false)
    assertEquals(82f, centered.x)
    assertEquals(84f, centered.baselineY)

    val rightBottom = computeAnchoredTextPosition(100f, 80f, 1f, 1f, -2f, -12f, 38f, 4f, false)
    assertEquals(62f, rightBottom.x)
    assertEquals(92f, rightBottom.baselineY)

    val baseline = computeAnchoredTextPosition(100f, 80f, -1f, 0f, -2f, -12f, 38f, 4f, true)
    assertEquals(102f, baseline.x)
    assertEquals(80f, baseline.baselineY)
  }

  @Test
  fun nanPanYUsesAnchorAsBaseline() {
    val position = computeAnchoredTextPosition(10f, 20f, 0f, Float.NaN, 0f, -8f, 12f, 2f, false)
    assertEquals(20f, position.baselineY)
  }
}
