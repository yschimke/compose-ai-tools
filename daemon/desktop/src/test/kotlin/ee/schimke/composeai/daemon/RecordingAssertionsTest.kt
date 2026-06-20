package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [evaluateVisibilityAssertion] verdict logic — the part of the
 * `assert.visible` / `assert.notVisible` recording handlers that doesn't need a held scene. The
 * wired handler (target resolution against the live semantics tree) is covered by the integration
 * test in `DesktopRecordingSessionTest`.
 */
class RecordingAssertionsTest {

  @Test
  fun assert_visible_passes_when_a_node_matches() {
    assertEquals(
      AssertionVerdict.Passed,
      evaluateVisibilityAssertion(true, matchCount = 1, "tag=ok"),
    )
  }

  @Test
  fun assert_visible_passes_when_multiple_nodes_match() {
    // Maestro semantics: an existence check is satisfied by ≥ 1 match, even if ambiguous.
    assertEquals(
      AssertionVerdict.Passed,
      evaluateVisibilityAssertion(true, matchCount = 3, "tag=ok"),
    )
  }

  @Test
  fun assert_visible_fails_when_no_node_matches() {
    val verdict = evaluateVisibilityAssertion(true, matchCount = 0, "text=Submit")
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("text=Submit"))
    assertTrue(verdict.reason.contains("assert.visible"))
  }

  @Test
  fun assert_not_visible_passes_when_no_node_matches() {
    assertEquals(
      AssertionVerdict.Passed,
      evaluateVisibilityAssertion(false, matchCount = 0, "text=Error"),
    )
  }

  @Test
  fun assert_not_visible_fails_when_a_node_matches() {
    val verdict = evaluateVisibilityAssertion(false, matchCount = 1, "text=Error")
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("assert.notVisible"))
    assertTrue(verdict.reason.contains("text=Error"))
  }
}
