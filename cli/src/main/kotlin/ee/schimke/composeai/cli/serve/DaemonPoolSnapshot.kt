package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/** Resident child-daemon pool occupancy, additive on `/status.json`. */
@Serializable
data class DaemonPoolSnapshot(
  val name: String,
  val open: Int,
  val maxOpen: Int,
  val activeStreams: Int,
)
