package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
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

  @Test
  fun assert_text_equals_passes_on_exact_match() {
    assertEquals(
      AssertionVerdict.Passed,
      evaluateTextEqualsAssertion(expected = "Hello", actual = "Hello", "tag=greeting"),
    )
  }

  @Test
  fun assert_text_equals_fails_on_mismatch_and_reports_both() {
    val verdict =
      evaluateTextEqualsAssertion(expected = "Hello", actual = "Goodbye", "tag=greeting")
    assertTrue(verdict is AssertionVerdict.Failed)
    val reason = (verdict as AssertionVerdict.Failed).reason
    assertTrue("reports actual", reason.contains("Goodbye"))
    assertTrue("reports expected", reason.contains("Hello"))
  }

  @Test
  fun assert_text_equals_fails_when_node_has_no_text() {
    val verdict = evaluateTextEqualsAssertion(expected = "Hello", actual = null, "tag=greeting")
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("<none>"))
  }

  @Test
  fun resolved_node_text_uses_own_text_when_present() {
    val node = node(testTag = "greeting", text = "Hello")
    assertEquals("Hello", resolvedNodeText(node))
  }

  @Test
  fun resolved_node_text_falls_back_to_descendant_text() {
    // `Button(Modifier.testTag("submit")) { Text("Submit") }`: the tag-bearing container has no
    // text
    // of its own in the unmerged tree; the visible text lives on a child node.
    val container = node(testTag = "submit", text = null, children = listOf(node(text = "Submit")))
    assertEquals("Submit", resolvedNodeText(container))
  }

  @Test
  fun resolved_node_text_joins_multiple_descendant_texts() {
    val container =
      node(
        testTag = "row",
        text = null,
        children = listOf(node(text = "Hello"), node(text = "World")),
      )
    assertEquals("Hello\nWorld", resolvedNodeText(container))
  }

  @Test
  fun resolved_node_text_is_null_when_nothing_has_text() {
    assertEquals(null, resolvedNodeText(node(testTag = "empty", text = null)))
  }

  // assert.a11y (issue #1966) — threshold parsing + verdict evaluation.

  @Test
  fun a11y_threshold_parses_errors_by_default() {
    assertEquals(A11yAssertThreshold.ERRORS, A11yAssertThreshold.parseOrDefault(null))
    assertEquals(A11yAssertThreshold.ERRORS, A11yAssertThreshold.parseOrDefault("errors"))
    assertEquals(A11yAssertThreshold.ERRORS, A11yAssertThreshold.parseOrDefault("nonsense"))
    assertEquals(A11yAssertThreshold.WARNINGS, A11yAssertThreshold.parseOrDefault(" WARNINGS "))
    assertEquals(A11yAssertThreshold.WARNINGS, A11yAssertThreshold.parseOrDefault("warning"))
  }

  @Test
  fun a11y_passes_when_no_findings() {
    assertEquals(
      AssertionVerdict.Passed,
      evaluateA11yAssertion(emptyList(), A11yAssertThreshold.WARNINGS),
    )
  }

  @Test
  fun a11y_errors_threshold_ignores_warnings_and_info() {
    val findings = listOf(a11y("WARNING"), a11y("INFO"))
    assertEquals(
      AssertionVerdict.Passed,
      evaluateA11yAssertion(findings, A11yAssertThreshold.ERRORS),
    )
  }

  @Test
  fun a11y_errors_threshold_fails_on_an_error() {
    val verdict = evaluateA11yAssertion(listOf(a11y("ERROR")), A11yAssertThreshold.ERRORS)
    assertTrue(verdict is AssertionVerdict.Failed)
    val reason = (verdict as AssertionVerdict.Failed).reason
    assertTrue(reason.contains("1 error"))
    assertTrue(reason.contains("TouchTargetSizeCheck"))
  }

  @Test
  fun a11y_warnings_threshold_fails_on_a_warning() {
    val verdict = evaluateA11yAssertion(listOf(a11y("WARNING")), A11yAssertThreshold.WARNINGS)
    assertTrue(verdict is AssertionVerdict.Failed)
    assertTrue((verdict as AssertionVerdict.Failed).reason.contains("1 warning"))
  }

  @Test
  fun a11y_failure_detail_caps_at_five_violations() {
    val findings = List(8) { a11y("ERROR", message = "v$it") }
    val reason =
      (evaluateA11yAssertion(findings, A11yAssertThreshold.ERRORS) as AssertionVerdict.Failed)
        .reason
    // Detail lists at most 5 of the breaching findings; the count still reports all 8.
    assertEquals(5, reason.split("TouchTargetSizeCheck:").size - 1)
    assertTrue(reason.contains("8 error"))
  }

  private fun a11y(level: String, message: String = "too small") =
    ee.schimke.composeai.daemon.protocol.RecordingA11yFinding(
      level = level,
      type = "TouchTargetSizeCheck",
      message = message,
    )

  private var nodeCounter = 0

  private fun node(
    testTag: String? = null,
    text: String? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ): ComposeSemanticsNode =
    ComposeSemanticsNode(
      nodeId = "n${nodeCounter++}",
      boundsInRoot = "",
      testTag = testTag,
      text = text,
      children = children,
    )
}
