package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedReason

/**
 * Thrown by an [InteractiveSession.dispatch] when a portable semantic target (issue #1784) — `{ ref
 * | testTag | role+text }` — didn't resolve to exactly one node. Carries the structured [reason]
 * (cause + candidate nodes) so the recording path can surface it as
 * `RecordingScriptEvidence.targetUnresolvedReason` and the interactive path can log/route it.
 *
 * Lives in `:daemon:core` so both backends (desktop host-side, Android host-side after the sandbox
 * reply crosses the bridge) throw and catch the same type. Pixel dispatches never raise this — a
 * bad pixel just dispatches into empty space.
 */
class SemanticsTargetUnresolvedException(
  val reason: SemanticsTargetUnresolvedReason,
  message: String,
) : RuntimeException(message)
