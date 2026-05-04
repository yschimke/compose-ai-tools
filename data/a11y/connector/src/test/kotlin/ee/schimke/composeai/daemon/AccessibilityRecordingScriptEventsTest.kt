package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the supported/roadmap split of [AccessibilityRecordingScriptEvents] — Android `DaemonMain`
 * composes the daemon's `dataExtensions` capability from these two halves, and `record_preview`'s
 * `validateRecordingScriptKinds` filters by the `supported` flag. The split must stay honest:
 * supported descriptors carry the kinds wired to a real `RobolectricHost.performSemanticsAction*`
 * arm, roadmap descriptors are everything else.
 */
class AccessibilityRecordingScriptEventsTest {

  @Test
  fun `supportedDescriptors expose only a11y action click as supported`() {
    val supported = AccessibilityRecordingScriptEvents.supportedDescriptors
    val supportedEvents = supported.flatMap { it.recordingScriptEvents }
    assertEquals(1, supportedEvents.size)
    val click = supportedEvents.single()
    assertEquals(AccessibilityRecordingScriptEvents.ACTION_CLICK, click.id)
    assertTrue(
      "a11y.action.click must advertise supported = true so record_preview accepts it",
      click.supported,
    )
  }

  @Test
  fun `roadmapDescriptors carry the unwired a11y action ids`() {
    val roadmap = AccessibilityRecordingScriptEvents.roadmapDescriptors
    val roadmapIds = roadmap.flatMap { it.recordingScriptEvents }.map { it.id }.toSet()
    val expectedRoadmapIds =
      setOf(
        AccessibilityRecordingScriptEvents.ACTION_LONG_CLICK,
        AccessibilityRecordingScriptEvents.ACTION_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_SELECT,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_SELECTION,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_FORWARD,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_BACKWARD,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_UP,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_DOWN,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_LEFT,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_RIGHT,
        AccessibilityRecordingScriptEvents.ACTION_EXPAND,
        AccessibilityRecordingScriptEvents.ACTION_COLLAPSE,
        AccessibilityRecordingScriptEvents.ACTION_DISMISS,
        AccessibilityRecordingScriptEvents.ACTION_NEXT_AT_GRANULARITY,
        AccessibilityRecordingScriptEvents.ACTION_PREVIOUS_AT_GRANULARITY,
      )
    assertEquals(expectedRoadmapIds, roadmapIds)
    val anySupported = roadmap.flatMap { it.recordingScriptEvents }.any { it.supported }
    assertFalse(
      "roadmap descriptors must all be supported = false; flip them into the supportedDescriptors " +
        "list when a real handler arm lands",
      anySupported,
    )
  }

  @Test
  fun `legacy descriptors aggregate is supportedDescriptors plus roadmap`() {
    assertEquals(
      AccessibilityRecordingScriptEvents.supportedDescriptors +
        AccessibilityRecordingScriptEvents.roadmapDescriptors,
      AccessibilityRecordingScriptEvents.descriptors,
    )
  }
}
