package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertEquals
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
  fun `roadmapDescriptors is empty once every renderer-agnostic event has a host`() {
    // `state.save` / `state.restore` were the last entries here; they joined `state.recreate`
    // in `StateRecordingScriptEvents` once the Android `SaveableStateRegistry` bridge was wired.
    // Renderer-agnostic roadmap stays empty until another event wants to advertise itself
    // pre-dispatch.
    assertEquals(emptyList<Any>(), RecordingScriptDataExtensions.roadmapDescriptors)
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
