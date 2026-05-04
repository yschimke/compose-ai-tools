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
  fun `supportedDescriptors carry the wired a11y action ids`() {
    val supported = AccessibilityRecordingScriptEvents.supportedDescriptors
    val supportedIds = supported.flatMap { it.recordingScriptEvents }.map { it.id }.toSet()
    val expectedSupportedIds =
      setOf(
        AccessibilityRecordingScriptEvents.ACTION_CLICK,
        AccessibilityRecordingScriptEvents.ACTION_LONG_CLICK,
        AccessibilityRecordingScriptEvents.ACTION_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_EXPAND,
        AccessibilityRecordingScriptEvents.ACTION_COLLAPSE,
        AccessibilityRecordingScriptEvents.ACTION_DISMISS,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_FORWARD,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_BACKWARD,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_UP,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_DOWN,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_LEFT,
        AccessibilityRecordingScriptEvents.ACTION_SCROLL_RIGHT,
      )
    assertEquals(expectedSupportedIds, supportedIds)
    val allSupported = supported.flatMap { it.recordingScriptEvents }.all { it.supported }
    assertTrue(
      "every entry in supportedDescriptors must be supported = true so record_preview accepts it",
      allSupported,
    )
  }

  @Test
  fun `roadmapDescriptors carry the actions without a clean SemanticsActions equivalent`() {
    val roadmap = AccessibilityRecordingScriptEvents.roadmapDescriptors
    val roadmapIds = roadmap.flatMap { it.recordingScriptEvents }.map { it.id }.toSet()
    val expectedRoadmapIds =
      setOf(
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_SELECT,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_SELECTION,
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
