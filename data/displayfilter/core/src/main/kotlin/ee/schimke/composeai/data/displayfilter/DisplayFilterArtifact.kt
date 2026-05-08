package ee.schimke.composeai.data.displayfilter

import ee.schimke.composeai.data.render.extensions.DataProductKey

/** One filtered PNG produced from the base capture. */
data class DisplayFilterArtifact(
  val filter: DisplayFilter,
  val path: String,
  val mediaType: String = "image/png",
)

/** Bag of all enabled filters' outputs for a single preview. */
data class DisplayFilterArtifacts(val artifacts: List<DisplayFilterArtifact>)

object DisplayFilterDataProducts {
  /** Protocol/on-disk kind shared between the typed product graph and the JSON-RPC surface. */
  const val KIND_VARIANTS: String = "displayfilter/variants"

  /** Bumped when the on-disk manifest layout changes. */
  const val SCHEMA_VERSION: Int = 1

  /**
   * Single output product carrying every enabled filter's artifact for one preview. One product
   * (rather than one-key-per-filter) keeps the planner contract simple — the configured set of
   * filters is host-controlled context, not a graph dependency.
   */
  val Variants: DataProductKey<DisplayFilterArtifacts> =
    DataProductKey(
      kind = KIND_VARIANTS,
      schemaVersion = SCHEMA_VERSION,
      type = DisplayFilterArtifacts::class.java,
    )
}
