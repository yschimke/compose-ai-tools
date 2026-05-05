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
  const val SCHEMA_VERSION: Int = 1

  /** Subscribe-time mode: per-input deltas, requires a live interactive session. */
  const val MODE_DELTA: String = "delta"

  /** Subscribe-time mode: one-shot snapshot of initial-composition counts. */
  const val MODE_SNAPSHOT: String = "snapshot"
}

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
)
