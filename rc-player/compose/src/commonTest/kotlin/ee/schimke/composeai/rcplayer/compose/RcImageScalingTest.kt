package ee.schimke.composeai.rcplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RcImageScalingTest {
  @Test
  fun matchesAndroidXScaleModes() {
    fun scale(type: Int, factor: Float = 1f) =
      computeImageScaling(0f, 0f, 40f, 20f, 10f, 20f, 110f, 120f, type, factor)

    assertEquals(RcScaledRect(40f, 60f, 80f, 80f), scale(0))
    assertEquals(RcScaledRect(40f, 60f, 80f, 80f), scale(1))
    assertEquals(RcScaledRect(10f, 45f, 110f, 95f), scale(2))
    assertEquals(RcScaledRect(-40f, 20f, 160f, 120f), scale(3))
    assertEquals(RcScaledRect(10f, 45f, 110f, 95f), scale(4))
    assertEquals(RcScaledRect(-40f, 20f, 160f, 120f), scale(5))
    assertEquals(RcScaledRect(10f, 20f, 110f, 120f), scale(6))
    assertEquals(RcScaledRect(30f, 55f, 90f, 85f), scale(7, 1.5f))
  }
}
