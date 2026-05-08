package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter
import ee.schimke.composeai.data.displayfilter.DisplayFilterDataProducts
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes per-render display-filter artifacts the data-product registry serves.
 *
 * Layout under `<rootDir>/<previewId>/`:
 * - `displayfilter_<filterId>.png` — one filtered PNG per enabled filter.
 * - `displayfilter-variants.json` — manifest listing each variant `{ filter, path, mediaType }`.
 *   The `displayfilter/variants` JSON-RPC kind serves this file (INLINE transport).
 *
 * Idempotent — overwrites prior files on repeat runs.
 */
object DisplayFilterDataProducer {

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  const val SCHEMA_VERSION: Int = DisplayFilterDataProducts.SCHEMA_VERSION
  const val KIND_VARIANTS: String = DisplayFilterDataProducts.KIND_VARIANTS
  const val FILE_VARIANTS: String = "displayfilter-variants.json"

  @Serializable internal data class VariantsManifest(val variants: List<VariantEntry>)

  @Serializable
  internal data class VariantEntry(val filter: String, val path: String, val mediaType: String)

  /**
   * Runs [runDisplayFilterPostCapturePipeline] against [pngFile] for the requested [filters],
   * places the variant PNGs under `<rootDir>/<previewId>/`, and writes the manifest JSON.
   *
   * No-op when [filters] is empty — neither the variants directory nor the manifest is created.
   * Returns the resolved variant entries so callers (e.g. RenderEngine integrations) can attach
   * them to renderFinished payloads without re-reading the manifest.
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    pngFile: File,
    filters: List<DisplayFilter>,
  ): List<DisplayFilterArtifactRecord> {
    if (filters.isEmpty()) return emptyList()
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    val store =
      runDisplayFilterPostCapturePipeline(
        previewId = previewId,
        imageArtifact = RenderImageArtifact(path = pngFile.absolutePath),
        outputDirectory = previewDir,
        filters = filters,
      )
    val artifacts = store.displayFilterArtifacts()?.artifacts.orEmpty()
    val records = artifacts.map { a ->
      DisplayFilterArtifactRecord(filter = a.filter, path = a.path, mediaType = a.mediaType)
    }
    val manifest =
      VariantsManifest(
        variants =
          records.map {
            VariantEntry(filter = it.filter.id, path = it.path, mediaType = it.mediaType)
          }
      )
    previewDir
      .resolve(FILE_VARIANTS)
      .writeText(json.encodeToString(VariantsManifest.serializer(), manifest))
    return records
  }
}

/**
 * Connector-side projection of `DisplayFilterArtifact` for callers that don't want to depend on the
 * typed data-product graph types.
 */
data class DisplayFilterArtifactRecord(
  val filter: DisplayFilter,
  val path: String,
  val mediaType: String,
)
