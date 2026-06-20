package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode

/**
 * Pure decision logic for the Maestro-style `assert.visible` / `assert.notVisible` /
 * `assert.textEquals` recording events. Kept free of any Compose / Skiko dependency so it's
 * unit-testable without standing up a held scene: the [DesktopRecordingSession] handler resolves
 * the event's target against the live semantics tree, reduces it to a [matchCount] (or the resolved
 * node's text), and asks these functions for the verdict.
 *
 * **Semantics (matching Maestro).** `assertVisible` passes when *at least one* node matches the
 * target — multiple matches still count as "present". `assertNotVisible` passes only when *zero*
 * nodes match. This is deliberately laxer than the tap-dispatch path (which treats an ambiguous
 * match as unresolved): an existence check doesn't need a unique node, it needs a yes/no answer.
 */
internal sealed interface AssertionVerdict {
  data object Passed : AssertionVerdict

  data class Failed(val reason: String) : AssertionVerdict
}

/**
 * Evaluate a visibility assertion. [expectVisible] = `true` for `assert.visible`, `false` for
 * `assert.notVisible`. [matchCount] is how many semantics nodes the target resolved to (0, 1, or
 * more). [targetLabel] is a human-readable rendering of the target for the failure message.
 */
internal fun evaluateVisibilityAssertion(
  expectVisible: Boolean,
  matchCount: Int,
  targetLabel: String,
): AssertionVerdict =
  if (expectVisible) {
    if (matchCount >= 1) AssertionVerdict.Passed
    else AssertionVerdict.Failed("assert.visible: no node matched $targetLabel")
  } else {
    if (matchCount == 0) AssertionVerdict.Passed
    else
      AssertionVerdict.Failed(
        "assert.notVisible: $matchCount node(s) matched $targetLabel but none were expected"
      )
  }

/**
 * Evaluate an `assert.textEquals` assertion against the **resolved** node's text. [expected] is the
 * string the script asked for (the event's `inputText`); [actual] is the resolved node's `text`
 * (`null` when the node carries no text). [targetLabel] renders the target for the failure message.
 * The target-resolution failure (no match / ambiguous) is handled by the caller before this runs —
 * this is purely the string comparison.
 */
internal fun evaluateTextEqualsAssertion(
  expected: String,
  actual: String?,
  targetLabel: String,
): AssertionVerdict =
  if (actual == expected) AssertionVerdict.Passed
  else
    AssertionVerdict.Failed(
      "assert.textEquals: $targetLabel text was ${actual?.let { "\"$it\"" } ?: "<none>"} " +
        "but expected \"$expected\""
    )

/**
 * The text to compare a resolved node against for `assert.textEquals`. Prefers the node's own
 * `text`; when it has none, falls back to the **merged** text of its descendants.
 *
 * `composeSemanticsRoot()` builds the UNMERGED semantics tree, so a common shape like
 * `Button(Modifier.testTag("submit")) { Text("Submit") }` resolves the tag-bearing container —
 * whose own `text` is null — while the visible text sits on a descendant node. Compose's merged
 * semantics concatenates that descendant text into the parent; mirror it here (depth-first,
 * newline-joined, the same separator Compose uses) so the assertion sees what the user sees rather
 * than `<none>`. Pure (operates on the [ComposeSemanticsNode] model, no scene) so it's
 * unit-testable directly.
 */
internal fun resolvedNodeText(node: ComposeSemanticsNode): String? {
  node.text
    ?.takeIf { it.isNotEmpty() }
    ?.let {
      return it
    }
  val parts = mutableListOf<String>()
  fun collect(n: ComposeSemanticsNode) {
    n.text?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    n.children.forEach(::collect)
  }
  node.children.forEach(::collect)
  return parts.joinToString("\n").ifEmpty { null }
}
