package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [NavigationRecordingScriptEvents] — the Android-only navigation script-event descriptor
 * that `RobolectricHost` advertises from `recordingScriptEventDescriptors()` and that
 * `DaemonMain` registers as a discoverable extension.
 *
 * Each supported action has its own event id (`navigation.deepLink` / `navigation.back` /
 * `navigation.predictiveBack{Started,Progressed,Committed,Cancelled}`) so the MCP validator can
 * reject unknown actions at the kind level without a payload-shape branch. Dispatch routing is
 * pinned in [AndroidRecordingSessionTest]; this test keeps the descriptor side honest.
 */
class NavigationRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises one navigation event id per wired action`() {
    val descriptor = NavigationRecordingScriptEvents.descriptor
    assertEquals("navigation", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    assertEquals(
      setOf(
        NavigationRecordingScriptEvents.NAV_DEEP_LINK_EVENT,
        NavigationRecordingScriptEvents.NAV_BACK_EVENT,
        NavigationRecordingScriptEvents.NAV_PREDICTIVE_BACK_STARTED_EVENT,
        NavigationRecordingScriptEvents.NAV_PREDICTIVE_BACK_PROGRESSED_EVENT,
        NavigationRecordingScriptEvents.NAV_PREDICTIVE_BACK_COMMITTED_EVENT,
        NavigationRecordingScriptEvents.NAV_PREDICTIVE_BACK_CANCELLED_EVENT,
      ),
      ids,
    )
    val allSupported = descriptor.recordingScriptEvents.all { it.supported }
    assertTrue(
      "every wired navigation event id must advertise supported = true",
      allSupported,
    )
  }

  @Test
  fun `wired event set covers deep-link back and the four predictive-back phases`() {
    assertEquals(
      listOf(
        "navigation.deepLink",
        "navigation.back",
        "navigation.predictiveBackStarted",
        "navigation.predictiveBackProgressed",
        "navigation.predictiveBackCommitted",
        "navigation.predictiveBackCancelled",
      ),
      NavigationRecordingScriptEvents.WIRED_EVENTS,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(NavigationRecordingScriptEvents.descriptor),
      NavigationRecordingScriptEvents.descriptors,
    )
  }
}
