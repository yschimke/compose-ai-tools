package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the typed-evidence heuristic for unsupported `uia.*` dispatches (#874 item #2). Drives
 * `UiAutomatorEvidence.compute(...)` against real Compose semantics trees through Robolectric
 * — same path the held-rule sandbox runs at dispatch time, so anything passing here matches
 * what an agent's `record_preview` response would look like in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class UiAutomatorEvidenceTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `NO_MATCH surfaces a case-insensitive-equal node as the near match`() {
    rule.setContent {
      Column {
        // Material3 Button merges its Text label, so the merged node carries text="SUBMIT"
        // (Button forces uppercase via TextStyle). A `By.text("Submit")` would miss; the
        // near-match heuristic must surface "SUBMIT" with the click action so the agent
        // sees the case mismatch directly.
        Button(onClick = {}) { Text("SUBMIT") }
        Text("decorative")
      }
    }

    val reason =
      UiAutomatorEvidence.compute(
        rule = rule,
        actionKind = "click",
        selectorJson = """{"text":"Submit"}""",
        useUnmergedTree = false,
      )
    assertEquals(UiAutomatorUnsupportedReasonCode.NO_MATCH, reason.code)
    assertEquals(0, reason.matchCount)
    assertEquals("click", reason.actionKind)
    assertEquals(false, reason.useUnmergedTree)
    assertEquals("""{"text":"Submit"}""", reason.selectorJson)
    val nearMatch = reason.nearMatch
    assertNotNull("expected a near-match node, got null", nearMatch)
    assertEquals("SUBMIT", nearMatch!!.text)
    assertTrue(
      "near-match must expose `click` so the agent knows the dispatch would fire after fixing the selector",
      "click" in nearMatch.actions,
    )
  }

  @Test
  fun `ACTION_NOT_EXPOSED surfaces the matched node and the actions it does expose`() {
    rule.setContent {
      Box(
        modifier =
          Modifier.size(80.dp)
            .testTag("only-clickable")
            .clickable(onClick = {})
      )
    }

    // The selector matches the only clickable node, but we asked for `inputText` — the
    // matched node has no SetText action so the dispatch returned false. The heuristic must
    // surface the matched node + its real action set so the agent sees "you matched, but
    // this node only exposes click".
    val reason =
      UiAutomatorEvidence.compute(
        rule = rule,
        actionKind = "inputText",
        selectorJson = """{"res":"only-clickable"}""",
        useUnmergedTree = false,
      )
    assertEquals(UiAutomatorUnsupportedReasonCode.ACTION_NOT_EXPOSED, reason.code)
    assertEquals(1, reason.matchCount)
    val nearMatch = reason.nearMatch
    assertNotNull(nearMatch)
    assertEquals("only-clickable", nearMatch!!.testTag)
    assertTrue(
      "matched node must expose the click action it carries — agents see the action it does have",
      "click" in nearMatch.actions,
    )
    assertTrue(
      "matched node must NOT expose `setText` — that's the gap the agent needs to see",
      "setText" !in nearMatch.actions,
    )
  }

  @Test
  fun `MULTIPLE_MATCHES surfaces the first match and a count above one`() {
    rule.setContent {
      Column {
        Button(onClick = {}) { Text("Primary") }
        Button(onClick = {}) { Text("Secondary") }
      }
    }

    val reason =
      UiAutomatorEvidence.compute(
        rule = rule,
        actionKind = "click",
        selectorJson = """{"clickable":true}""",
        useUnmergedTree = false,
      )
    assertEquals(UiAutomatorUnsupportedReasonCode.MULTIPLE_MATCHES, reason.code)
    assertTrue(
      "expected matchCount >= 2, got ${reason.matchCount}",
      reason.matchCount >= 2,
    )
    assertNotNull("first matched node should be surfaced as the near-match", reason.nearMatch)
  }

  @Test
  fun `UNKNOWN_ACTION_KIND short-circuits without walking the tree`() {
    rule.setContent { Button(onClick = {}) { Text("Submit") } }

    val reason =
      UiAutomatorEvidence.compute(
        rule = rule,
        actionKind = "doesNotExist",
        selectorJson = """{"text":"Submit"}""",
        useUnmergedTree = false,
      )
    assertEquals(UiAutomatorUnsupportedReasonCode.UNKNOWN_ACTION_KIND, reason.code)
    assertEquals(0, reason.matchCount)
    assertNull("UNKNOWN_ACTION_KIND must not surface a near-match", reason.nearMatch)
  }

  @Test
  fun `NO_MATCH with no actionable nodes leaves nearMatch null`() {
    // Non-actionable composition — only a static Text. The heuristic should report
    // NO_MATCH with `null` nearMatch (no candidate exists), not invent a synthetic node.
    rule.setContent { Text("decorative") }

    val reason =
      UiAutomatorEvidence.compute(
        rule = rule,
        actionKind = "click",
        selectorJson = """{"text":"Submit"}""",
        useUnmergedTree = false,
      )
    assertEquals(UiAutomatorUnsupportedReasonCode.NO_MATCH, reason.code)
    assertNull(
      "without any actionable node the heuristic must not invent a near-match",
      reason.nearMatch,
    )
  }
}
