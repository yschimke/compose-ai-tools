package ee.schimke.composeai.data.recomposition

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/recomposition` data product. Lifted out of
 * `RecompositionDataProductRegistry` so MCP clients and other connectors can depend on the payload
 * schema without pulling in the daemon-side registry, the Compose runtime, or the desktop scene
 * recomposer reflection.
 */
object RecompositionProduct {
  const val KIND: String = "compose/recomposition"

  /**
   * v2 (#1605) — adds a per-scope [RecompositionNode.reason] diagnostic plus the nullable
   * [RecompositionNode.bounds] / source-marker carriers. The new fields all default (reason to
   * [InvalidationReason.UNKNOWN], the rest to `null`) so a v2 payload decoded by a v1-shaped reader
   * still parses — the wire stays additive. See `docs/daemon/DATA-PRODUCTS.md` § "Recomposition +
   * interactive mode".
   */
  const val SCHEMA_VERSION: Int = 2

  /** Subscribe-time mode: per-input deltas, requires a live interactive session. */
  const val MODE_DELTA: String = "delta"

  /** Subscribe-time mode: one-shot snapshot of initial-composition counts. */
  const val MODE_SNAPSHOT: String = "snapshot"
}

/**
 * Why a recomposed scope re-ran, as far as the
 * [androidx.compose.runtime.tooling.CompositionObserver] signals can tell. Derived per delta-window
 * from the relationship between how many times a scope exited composition
 * ([RecompositionNode.count]) and how many times the runtime *invalidated* it with a snapshot
 * value:
 *
 * - [STATE_READ] — the scope subscribed to a snapshot state it read, and a write to that state
 *   invalidated it. Every recomposition this window was state-driven.
 * - [PARAMETER_CHANGE] — the scope re-ran without being directly invalidated, i.e. its caller
 *   recomposed and re-invoked it (typically with changed arguments). This is the "surprising" audit
 *   signal: a child dragged along by its parent rather than its own state.
 * - [BOTH] — the scope was invalidated by state *and* re-ran more times than it was invalidated, so
 *   at least one of its recompositions came from a parameter change too.
 * - [UNKNOWN] — instrumentation couldn't attribute the recomposition (e.g. a backend that doesn't
 *   surface invalidation signals yet, or a Compose runtime where the callback didn't fire).
 */
enum class InvalidationReason {
  PARAMETER_CHANGE,
  STATE_READ,
  BOTH,
  UNKNOWN,
}

/**
 * Per-scope screen bounds in the rendered image's pixel space, for the PNG heat-map overlay.
 * Carried nullable on [RecompositionNode] because the layout join that populates it (matching a
 * scope id to its post-layout [androidx.compose.ui.layout.LayoutCoordinates]) is a v2 increment
 * that producers may ship without. See issue #1605.
 */
@Serializable
data class RecompositionBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Wire shape for `compose/recomposition` payloads. Mirrors the JSON the VS Code panel's heat-map
 * overlay decodes. See `docs/daemon/DATA-PRODUCTS.md` § "Recomposition + interactive mode".
 *
 * [sinceFrameStreamId] / [inputSeq] are populated only in delta mode — the snapshot mode is a
 * one-shot answer to "what recomposed during the initial composition" with no temporal baseline to
 * track.
 */
@Serializable
data class RecompositionPayload(
  val mode: String,
  val sinceFrameStreamId: String? = null,
  val inputSeq: Long? = null,
  val nodes: List<RecompositionNode> = emptyList(),
)

@Serializable
data class RecompositionNode(
  /**
   * Identity-hashcode-of-RecomposeScope encoded as base-16 — stable for the duration of one
   * interactive session. NOT stable across sessions. See `RecompositionDataProductRegistry` KDoc
   * for the v2 followup (slot-table-derived `(file:line:column)` keys).
   */
  val nodeId: String,
  val count: Int,
  /**
   * v2 (#1605) — why this scope recomposed, as attributed from the runtime's invalidation signals.
   * Defaults to [InvalidationReason.UNKNOWN] so a producer that can't attribute (or a v1 reader) is
   * never wrong, just uninformative.
   */
  val reason: InvalidationReason = InvalidationReason.UNKNOWN,
  /**
   * v2 (#1605) — per-scope screen bounds for the heat-map overlay, or `null` until the post-layout
   * bounds join lands (see issue #1605, deferred per its Risk section).
   */
  val bounds: RecompositionBounds? = null,
  /**
   * v2 (#1605) — source-marker fields for the VS Code source overlay, parsed from the compiler's
   * `sourceInformation()` markers off the scope's slot-table anchor. Nullable: the slot-table
   * reflection is private API and is a deferred v2 increment (issue #1605 Risk section), so
   * producers ship `null` until that path is wired and version-probed.
   */
  val sourceFile: String? = null,
  val sourceLine: Int? = null,
  val sourceColumn: Int? = null,
  val functionName: String? = null,
)
