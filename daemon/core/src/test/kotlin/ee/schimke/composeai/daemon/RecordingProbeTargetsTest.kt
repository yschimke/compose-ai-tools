package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for the pure Android probe-target resolver (issue #2519). */
class RecordingProbeTargetsTest {

  // A realistic flat snapshot of `Button { Text("Add") }` (the role-bearing container with merged
  // text [0] *and* the child text node it merged [1] — both are retained), a test-tagged submit
  // button carrying its child's merged text [2], a standalone label [3], and an icon-only control
  // with just a content description [4].
  private val snapshot =
    listOf(
      RecordingProbeNode(role = "Button", clickable = true, mergedText = "Add"),
      RecordingProbeNode(text = "Add"),
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
    assertEquals(listOf(snapshot[2]), matched(SemanticsInputTarget(testTag = "submit")))
  }

  @Test
  fun testTagWinsWhenRoleAndTextAlsoRideAlong() {
    // The most specific finder resolves even when role/text are also set (desktop precedence).
    assertEquals(
      listOf(snapshot[2]),
      matched(SemanticsInputTarget(testTag = "submit", role = "Button", text = "wrong")),
    )
  }

  @Test
  fun roleAndTextMatchTheContainerViaMergedText() {
    // `Button { Text("Add") }`: role on the button, text on the child — resolved via merged text.
    // Only the container matches; the child text node has no role.
    assertEquals(listOf(snapshot[0]), matched(SemanticsInputTarget(role = "Button", text = "Add")))
  }

  @Test
  fun textAloneMatchesOwnTextAndContentDescriptionNotMergedText() {
    assertEquals(listOf(snapshot[3]), matched(SemanticsInputTarget(text = "Thanks!")))
    // `text: "Add"` resolves to the child text node (own text) only — NOT the role-bearing
    // container that merged it — so assert.textEquals stays unambiguous.
    assertEquals(listOf(snapshot[1]), matched(SemanticsInputTarget(text = "Add")))
    assertEquals(listOf(snapshot[4]), matched(SemanticsInputTarget(text = "Settings")))
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
