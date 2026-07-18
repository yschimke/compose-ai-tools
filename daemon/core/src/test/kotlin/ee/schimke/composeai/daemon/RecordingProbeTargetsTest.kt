package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for the pure Android probe-target resolver (issue #2519). */
class RecordingProbeTargetsTest {

  // A bare `Button { Text("Add") }` (role on the container, text merged up from a child), a
  // test-tagged submit button carrying its child's text, a standalone label, and an icon-only
  // control with just a content description.
  private val snapshot =
    listOf(
      RecordingProbeNode(role = "Button", clickable = true, mergedText = "Add"),
      RecordingProbeNode(testTag = "submit", role = "Button", mergedText = "Submit"),
      RecordingProbeNode(text = "Thanks!"),
      RecordingProbeNode(contentDescription = "Settings", role = "Button"),
    )

  private fun matched(target: SemanticsInputTarget): List<RecordingProbeNode> {
    val res = resolveProbeTarget(snapshot, target)
    assertTrue("expected Matched but got $res", res is ProbeTargetResolution.Matched)
    return (res as ProbeTargetResolution.Matched).nodes
  }

  @Test
  fun effectiveTextPrefersOwnTextThenMerged() {
    assertEquals("Thanks!", RecordingProbeNode(text = "Thanks!").effectiveText())
    assertEquals("Add", RecordingProbeNode(mergedText = "Add").effectiveText())
    assertEquals("own", RecordingProbeNode(text = "own", mergedText = "child").effectiveText())
    assertEquals(null, RecordingProbeNode(role = "Button").effectiveText())
  }

  @Test
  fun testTagMatchesTheTaggedNode() {
    assertEquals(listOf(snapshot[1]), matched(SemanticsInputTarget(testTag = "submit")))
  }

  @Test
  fun testTagWinsWhenRoleAndTextAlsoRideAlong() {
    // The most specific finder resolves even when role/text are also set (desktop precedence).
    assertEquals(
      listOf(snapshot[1]),
      matched(SemanticsInputTarget(testTag = "submit", role = "Button", text = "wrong")),
    )
  }

  @Test
  fun roleAndTextMatchTheContainerViaMergedText() {
    // `Button { Text("Add") }`: role on the button, text on the child — resolved via merged text.
    assertEquals(listOf(snapshot[0]), matched(SemanticsInputTarget(role = "Button", text = "Add")))
  }

  @Test
  fun textAloneMatchesOwnTextMergedTextAndContentDescription() {
    assertEquals(listOf(snapshot[2]), matched(SemanticsInputTarget(text = "Thanks!")))
    assertEquals(listOf(snapshot[0]), matched(SemanticsInputTarget(text = "Add")))
    assertEquals(listOf(snapshot[3]), matched(SemanticsInputTarget(text = "Settings")))
  }

  @Test
  fun noMatchIsAnEmptyMatchedList() {
    assertEquals(emptyList<RecordingProbeNode>(), matched(SemanticsInputTarget(testTag = "absent")))
    assertEquals(
      emptyList<RecordingProbeNode>(),
      matched(SemanticsInputTarget(role = "Button", text = "Nope")),
    )
  }

  @Test
  fun refTargetIsUnsupportedRatherThanAFalseMiss() {
    val res = resolveProbeTarget(snapshot, SemanticsInputTarget(ref = "n42"))
    assertTrue(res is ProbeTargetResolution.Unsupported)
    assertTrue((res as ProbeTargetResolution.Unsupported).reason.contains("ref"))
  }

  @Test
  fun emptyTargetIsUnsupported() {
    val res = resolveProbeTarget(snapshot, SemanticsInputTarget())
    assertTrue(res is ProbeTargetResolution.Unsupported)
  }
}
