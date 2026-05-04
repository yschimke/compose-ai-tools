package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single `a11y` descriptor's shape — `record_preview`'s `validateRecordingScriptKinds`
 * filters by per-event `supported` flag, so the wired/unwired split is event-level rather than
 * descriptor-level.
 */
class AccessibilityRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises a11y as one extension with all 19 actions`() {
    val descriptor = AccessibilityRecordingScriptEvents.descriptor
    assertEquals("a11y", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    val expectedIds =
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
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_SELECT,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_SELECTION,
        AccessibilityRecordingScriptEvents.ACTION_NEXT_AT_GRANULARITY,
        AccessibilityRecordingScriptEvents.ACTION_PREVIOUS_AT_GRANULARITY,
      )
    assertEquals(expectedIds, ids)
  }

  @Test
  fun `wired actions advertise supported = true`() {
    val expectedSupported =
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
    val actuallySupported =
      AccessibilityRecordingScriptEvents.descriptor.recordingScriptEvents
        .filter { it.supported }
        .map { it.id }
        .toSet()
    assertEquals(expectedSupported, actuallySupported)
  }

  @Test
  fun `unwired actions advertise supported = false alongside the wired ones`() {
    // Roadmap actions appear in the same descriptor with `supported = false` so agents calling
    // `list_data_products` see the full a11y action surface in one extension entry. The wired /
    // unwired distinction is per-event, not per-descriptor — there's no separate `a11y.roadmap`
    // extension.
    val expectedRoadmap =
      setOf(
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityRecordingScriptEvents.ACTION_SELECT,
        AccessibilityRecordingScriptEvents.ACTION_CLEAR_SELECTION,
        AccessibilityRecordingScriptEvents.ACTION_NEXT_AT_GRANULARITY,
        AccessibilityRecordingScriptEvents.ACTION_PREVIOUS_AT_GRANULARITY,
      )
    val actuallyUnsupported =
      AccessibilityRecordingScriptEvents.descriptor.recordingScriptEvents
        .filterNot { it.supported }
        .map { it.id }
        .toSet()
    assertEquals(expectedRoadmap, actuallyUnsupported)
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(AccessibilityRecordingScriptEvents.descriptor),
      AccessibilityRecordingScriptEvents.descriptors,
    )
    assertTrue(
      "descriptors list must contain exactly the one a11y descriptor",
      AccessibilityRecordingScriptEvents.descriptors.size == 1,
    )
  }
}
