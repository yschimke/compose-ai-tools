package ee.schimke.composeai.data.history

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `history/diff/regions` data product. Lifted out of
 * `HistoryDiffRegionsDataProductRegistry` so MCP clients and other connectors can depend on the
 * payload schema without pulling in the daemon-side history manager.
 */
object HistoryDiffRegionsProduct {
  const val KIND: String = "history/diff/regions"
  const val SCHEMA_VERSION: Int = 1
}

@Serializable
data class HistoryDiffPayload(
  val baselineHistoryId: String,
  val totalPixelsChanged: Long,
  val changedFraction: Double,
  val regions: List<HistoryDiffRegion>,
)

@Serializable
data class HistoryDiffRegion(
  val bounds: String,
  val pixelCount: Long,
  val avgDelta: HistoryDiffAverageDelta,
)

@Serializable
data class HistoryDiffAverageDelta(val r: Double, val g: Double, val b: Double, val a: Double)
