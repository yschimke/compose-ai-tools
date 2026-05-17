package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Surfaces the `displayfilter/variants` kind by reading the manifest JSON
 * [DisplayFilterDataProducer.writeArtifacts] writes during each render. The manifest enumerates the
 * variant PNGs (`displayfilter_<filterId>.png`) the post-capture pipeline produced; clients use the
 * listed paths to fetch each filtered image.
 *
 * `attachable: true` so the manifest rides `renderFinished.dataProducts`; `fetchable: true` for
 * pull-on-demand. Variant PNGs ride along as `extras` (the [extras] override below) so a panel that
 * subscribed to the manifest still has every PNG path handy without a follow-up `data/fetch`.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (defaults to `<outputDir.parent>/data`). Wired by
 * [DaemonMain].
 */
class DisplayFilterDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = DisplayFilterDataProducer.KIND_VARIANTS,
          schemaVersion = DisplayFilterDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.INLINE,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Display filter variants",
          facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.IMAGE),
          mediaTypes = listOf("application/json"),
          sampling = SamplingPolicy.End,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == DisplayFilterDataProducer.KIND_VARIANTS)
      rootDir.resolve(previewId).resolve(DisplayFilterDataProducer.FILE_VARIANTS)
    else null

  override fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? = payload?.let(::variantExtras)?.takeIf { it.isNotEmpty() }

  /**
   * Maps each `variants[]` entry in the parsed manifest to a [DataProductExtra]. Reading the
   * manifest as the source of truth (rather than globbing the filesystem) keeps the registry
   * decoupled from `DisplayFilterExtension`'s on-disk filename scheme — if the renamer changes the
   * only thing that has to follow is the producer.
   */
  private fun variantExtras(payload: JsonElement): List<DataProductExtra> {
    val obj = payload as? JsonObject ?: return emptyList()
    val variants = obj["variants"] as? JsonArray ?: return emptyList()
    return variants.mapNotNull { element ->
      val variant = element as? JsonObject ?: return@mapNotNull null
      val filterId = variant["filter"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val path = variant["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val mediaType = variant["mediaType"]?.jsonPrimitive?.content ?: "image/png"
      val file = File(path)
      DataProductExtra(
        name = filterId,
        path = path,
        mediaType = mediaType,
        sizeBytes = file.length().takeIf { file.isFile && it > 0 },
      )
    }
  }
}
