package ee.schimke.composeai.daemon.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed evidence for a portable semantic-target dispatch (issue #1784) that didn't resolve to
 * exactly one node — the ref / testTag / role+text analogue of [UiAutomatorUnsupportedReason] for
 * the cross-backend `input.*` / `record_preview` path.
 *
 * When an agent targets `{ ref | testTag | role+text }` and the daemon can't dispatch (no semantics
 * tree yet, no node matched, or more than one matched), this carries the structured cause plus the
 * [candidates] the agent should pick from — mirroring Playwright codegen's "improve the locator
 * when multiple match" behaviour so the agent can disambiguate without re-rendering and reading
 * pixels.
 *
 * Always carried alongside [RecordingScriptEvidence.message]; the message stays human-readable for
 * trace logs while [code] / [matchCount] / [candidates] give a coding agent enough signal to fix
 * the next target. Wire-stable and additive — agents that predate this field keep reading [message]
 * and ignore [targetUnresolvedReason].
 */
@Serializable
data class SemanticsTargetUnresolvedReason(
  /** Coarse cause — see [SemanticsTargetUnresolvedCode]. */
  val code: SemanticsTargetUnresolvedCode,
  /** Echo of the target the agent supplied, so the failure is self-describing in a trace. */
  val target: SemanticsInputTarget? = null,
  /**
   * How many nodes the target matched: `0` for [SemanticsTargetUnresolvedCode.NO_MATCH] and
   * [SemanticsTargetUnresolvedCode.NO_SEMANTICS_ROOT], `>= 2` for
   * [SemanticsTargetUnresolvedCode.AMBIGUOUS]. Agents differentiate "target too broad" vs "target
   * too narrow" directly off this field.
   */
  val matchCount: Int = 0,
  /**
   * Nodes the agent can disambiguate among. For [SemanticsTargetUnresolvedCode.AMBIGUOUS] these are
   * the matched nodes — pick one by its unique [SemanticsTargetCandidate.ref]. For
   * [SemanticsTargetUnresolvedCode.NO_MATCH] these are the targetable nodes that *do* exist in the
   * live tree (so the agent sees what it could have aimed at). Empty for
   * [SemanticsTargetUnresolvedCode.NO_SEMANTICS_ROOT].
   */
  val candidates: List<SemanticsTargetCandidate> = emptyList(),
)

/**
 * Distinguishes the coarse causes a semantic-target dispatch can fail for, so agents branch on the
 * right next step (wait for a render, narrow the target, widen the target). New codes land
 * additively; clients that don't recognise a new code fall back to
 * [RecordingScriptEvidence.message].
 */
@Serializable
enum class SemanticsTargetUnresolvedCode {
  /** The held session hasn't produced a semantics tree yet (nothing rendered). */
  @SerialName("noSemanticsRoot") NO_SEMANTICS_ROOT,
  /**
   * The target matched zero nodes. [SemanticsTargetUnresolvedReason.candidates] lists what exists.
   */
  @SerialName("noMatch") NO_MATCH,
  /** The target matched two or more nodes — narrow it, ideally to a unique `ref`. */
  @SerialName("ambiguous") AMBIGUOUS,
}

/**
 * Slim projection of one targetable node, included on a [SemanticsTargetUnresolvedReason] to give
 * the agent just enough shape to formulate the next target. Mirrors the targetable subset of
 * `ComposeSemanticsNode` (the stable `ref`, plus the `testTag` / `role` / text axes targets match
 * on, plus bounds) — the two surfaces deliberately overlap so a `compose/semantics` payload and an
 * `unresolvedReason.candidates` entry carry the same targeting vocabulary.
 *
 * Lives in `:daemon:core` (not `:data-layoutinspector-core`) because [RecordingScriptEvidence]
 * lives here and `:daemon:core` doesn't depend on the layout-inspector module. Plain
 * JSON-serializable, no Compose dep; the backends map their `ComposeSemanticsNode`s onto it.
 */
@Serializable
data class SemanticsTargetCandidate(
  /** The stable handle to pass back as `{ ref }` — unique within a render, the unambiguous pick. */
  val ref: String? = null,
  val testTag: String? = null,
  val role: String? = null,
  /** Rendered text (`SemanticsProperties.Text`). */
  val text: String? = null,
  /** Accessible label (contentDescription, else text). */
  val label: String? = null,
  /**
   * `left,top,right,bottom` in root-pixel space — same shape `ComposeSemanticsNode.boundsInRoot`
   * uses.
   */
  val boundsInRoot: String? = null,
)
