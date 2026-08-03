package ee.schimke.composeai.rcplayer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RcMarqueeTimelineTest {
  @Test
  fun matchesAndroidXAlpha16DelayAndSinusoidalCycle() {
    assertEquals(0f, androidXMarqueeOffset(100f, 1f, 100f, 500f, .999f))
    assertEquals(0f, androidXMarqueeOffset(100f, 1f, 100f, 500f, 1f))
    assertEquals(-50f, androidXMarqueeOffset(100f, 1f, 100f, 500f, 1.25f), .001f)
    assertEquals(-100f, androidXMarqueeOffset(100f, 1f, 100f, 500f, 1.5f), .001f)
    assertEquals(0f, androidXMarqueeOffset(100f, 1f, 100f, 500f, 2f), .001f)
  }
}
