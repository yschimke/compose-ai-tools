package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget

/**
 * Pure target resolution against the Android backend's **flat probe-semantics snapshot**
 * ([RecordingProbeNode] list, issue #2519). The desktop backend resolves a [SemanticsInputTarget]
 * against a live unmerged semantics tree via `SemanticsTargets.resolve`; Android only ever sees the
 * flat snapshot bridged out of the Robolectric sandbox, so this is its equivalent resolver — kept
 * in `:daemon:core` and free of any Compose / Robolectric dependency so it is unit-testable without
 * standing up a held scene.
 *
 * **What it matches (and why not `ref`).** The snapshot carries `testTag`, `role`, the node's own
 * `text`, its merged descendant text ([RecordingProbeNode.mergedText]), and `contentDescription` —
 * but no stable [ref][SemanticsInputTarget.ref] (refs are assigned on the tree the snapshot was
 * flattened from and don't survive into it). So a `ref` target resolves to
 * [ProbeTargetResolution.Unsupported] rather than silently matching nothing — a false
 * `assert.notVisible` pass is worse than a clear "unsupported" failure. The other target shapes
 * resolve by the most specific field present:
 * - `testTag` → nodes whose `testTag` equals it (the pre-#2519 behaviour).
 * - `role` + `text` → nodes whose `role` equals it **and** whose [effectiveText] equals the text,
 *   so a `Button { Text("Add") }` (role on the button, text merged up from the child) matches.
 * - `text` alone → nodes whose [effectiveText] (or [contentDescription], for an icon-only control)
 *   equals it.
 * - `role` alone → nodes whose `role` equals it.
 */

/**
 * The text to match / compare a probe node against: its own [RecordingProbeNode.text] when present,
 * else its [RecordingProbeNode.mergedText] (the merged text of its descendants). Mirrors the
 * desktop [resolvedNodeText] preference (own text wins over descendant text) so the two backends
 * answer `role`+`text` and `assert.textEquals` the same way.
 */
fun RecordingProbeNode.effectiveText(): String? =
  text?.takeIf { it.isNotEmpty() } ?: mergedText?.takeIf { it.isNotEmpty() }

/** Outcome of resolving a [SemanticsInputTarget] against a flat probe snapshot. */
sealed interface ProbeTargetResolution {
  /**
   * The target shape is resolvable against the snapshot; [nodes] is every node that matched (0, 1,
   * or more). A visibility check reads its size; a text check requires exactly one.
   */
  data class Matched(val nodes: List<RecordingProbeNode>) : ProbeTargetResolution

  /**
   * The target can't be resolved against the flat snapshot at all (a `ref` target, or a target with
   * no resolvable field). [reason] is a caller-facing explanation for the failed-assertion
   * evidence.
   */
  data class Unsupported(val reason: String) : ProbeTargetResolution
}

/**
 * Resolve [target] against the flat probe [nodes]. See the file header for the matching rules. A
 * `ref` target — or a target that carries no resolvable field — is
 * [ProbeTargetResolution.Unsupported] so the caller fails the assertion with a clear message
 * instead of risking a false pass.
 */
fun resolveProbeTarget(
  nodes: List<RecordingProbeNode>,
  target: SemanticsInputTarget,
): ProbeTargetResolution {
  val testTag = target.testTag?.takeIf { it.isNotBlank() }
  val role = target.role?.takeIf { it.isNotBlank() }
  val text = target.text?.takeIf { it.isNotBlank() }
  val ref = target.ref?.takeIf { it.isNotBlank() }

  // `testTag` is the most specific finder and lands on a single node — resolve it first even when a
  // role/text also rode along, mirroring the desktop resolver's precedence.
  if (testTag != null) {
    return ProbeTargetResolution.Matched(nodes.filter { it.testTag == testTag })
  }
  if (role != null && text != null) {
    return ProbeTargetResolution.Matched(
      nodes.filter { it.role == role && it.effectiveText() == text }
    )
  }
  if (text != null) {
    return ProbeTargetResolution.Matched(
      nodes.filter { it.effectiveText() == text || it.contentDescription == text }
    )
  }
  if (role != null) {
    return ProbeTargetResolution.Matched(nodes.filter { it.role == role })
  }
  if (ref != null) {
    return ProbeTargetResolution.Unsupported(
      "the Android recording backend has no refs in its probe snapshot; " +
        "target by testTag, role+text, or text instead of ref"
    )
  }
  return ProbeTargetResolution.Unsupported(
    "target has no resolvable field; set testTag, role, or text"
  )
}
