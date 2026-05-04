package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the supported/roadmap split of [RecordingScriptDataExtensions] — `record_preview`'s
 * `validateRecordingScriptKinds` filters by descriptor `supported` flag, and the daemon's
 * `dataExtensions` capability is composed from `RenderHost.recordingScriptEventDescriptors()` plus
 * [RecordingScriptDataExtensions.roadmapDescriptors]. The split has to stay honest: the host
 * contribution carries the `supported = true` halves; the roadmap list is `supported = false`
 * everywhere.
 */
class RecordingScriptDataExtensionsTest {

  @Test
  fun `recordingDescriptor is the supported probe descriptor`() {
    val descriptor = RecordingScriptDataExtensions.recordingDescriptor
    assertEquals(DataExtensionId("recording"), descriptor.id)
    assertEquals(1, descriptor.recordingScriptEvents.size)
    val probe = descriptor.recordingScriptEvents.single()
    assertEquals(RecordingScriptDataExtensions.PROBE_EVENT, probe.id)
    assertTrue(
      "recording.probe must advertise supported = true so record_preview accepts it",
      probe.supported,
    )
  }

  @Test
  fun `roadmapDescriptors carry only unsupported events`() {
    val roadmap = RecordingScriptDataExtensions.roadmapDescriptors
    val ids = roadmap.flatMap { it.recordingScriptEvents }.map { it.id }.toSet()
    assertEquals(
      setOf(
        RecordingScriptDataExtensions.STATE_SAVE_EVENT,
        RecordingScriptDataExtensions.STATE_RESTORE_EVENT,
        RecordingScriptDataExtensions.LIFECYCLE_EVENT,
        RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT,
      ),
      ids,
    )
    val anySupported = roadmap.flatMap { it.recordingScriptEvents }.any { it.supported }
    assertFalse(
      "roadmap descriptors must all be supported = false; flip them in the host's contribution " +
        "when real dispatch lands",
      anySupported,
    )
  }

  @Test
  fun `legacy descriptors aggregate is recordingDescriptor plus roadmap`() {
    assertEquals(
      listOf(RecordingScriptDataExtensions.recordingDescriptor) +
        RecordingScriptDataExtensions.roadmapDescriptors,
      RecordingScriptDataExtensions.descriptors,
    )
  }
}
