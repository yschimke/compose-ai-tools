package ee.schimke.composeai.daemon

/**
 * Pure decision logic for the Maestro-style `assert.visible` / `assert.notVisible` recording
 * events. Kept free of any Compose / Skiko dependency so it's unit-testable without standing up a
 * held scene: the [DesktopRecordingSession] handler resolves the event's target against the live
 * semantics tree, reduces it to a [matchCount], and asks this function for the verdict.
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
