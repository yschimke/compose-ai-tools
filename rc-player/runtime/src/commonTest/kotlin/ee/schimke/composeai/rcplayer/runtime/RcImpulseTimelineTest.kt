package ee.schimke.composeai.rcplayer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RcImpulseTimelineTest {
  @Test
  fun matchesAndroidXInitialProcessWaitingAndResetBranches() {
    val timeline = RcImpulseTimeline()

    assertEquals(RcImpulsePhase.WAITING, timeline.evaluate(0f, 1f, 2f))
    assertEquals(RcImpulsePhase.INITIALIZE, timeline.evaluate(1f, 1f, 2f))
    assertEquals(RcImpulsePhase.PROCESS, timeline.evaluate(1.5f, 1f, 2f))
    // AndroidX returns early before start and deliberately does not reset mInitialPass.
    assertEquals(RcImpulsePhase.WAITING, timeline.evaluate(1.5f, 2f, 2f))
    assertEquals(RcImpulsePhase.PROCESS, timeline.evaluate(2f, 2f, 2f))
    assertEquals(RcImpulsePhase.IDLE, timeline.evaluate(4.1f, 2f, 2f))
    assertEquals(RcImpulsePhase.INITIALIZE, timeline.evaluate(2f, 2f, 2f))
  }
}
