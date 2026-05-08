package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.io.File
import kotlinx.serialization.json.Json
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
 * pull-on-demand. Variant PNGs ride along as `extras` so a panel that subscribed to the manifest
 * still has every PNG path handy without a follow-up `data/fetch`.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (defaults to `<outputDir.parent>/data`). Wired by
 * [DaemonMain].
 */
class DisplayFilterDataProductRegistry(private val rootDir: File) : DataProductRegistry {

  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
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

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != DisplayFilterDataProducer.KIND_VARIANTS) return DataProductRegistry.Outcome.Unknown
    val file = manifestFile(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    val payload =
      try {
        json.parseToJsonElement(file.readText())
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    val extras = variantExtras(payload).takeIf { it.isNotEmpty() }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = DisplayFilterDataProducer.SCHEMA_VERSION,
        payload = payload,
        extras = extras,
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (DisplayFilterDataProducer.KIND_VARIANTS !in kinds) return emptyList()
    val file = manifestFile(previewId)
    if (!file.exists()) return emptyList()
    val payload =
      try {
        json.parseToJsonElement(file.readText())
      } catch (t: Throwable) {
        System.err.println(
          "DisplayFilterDataProductRegistry: parse manifest failed for $previewId: ${t.message}"
        )
        return emptyList()
      }
    val extras = variantExtras(payload).takeIf { it.isNotEmpty() }
    return listOf(
      DataProductAttachment(
        kind = DisplayFilterDataProducer.KIND_VARIANTS,
        schemaVersion = DisplayFilterDataProducer.SCHEMA_VERSION,
        payload = payload,
        extras = extras,
      )
    )
  }

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

  private fun manifestFile(previewId: String): File =
    rootDir.resolve(previewId).resolve(DisplayFilterDataProducer.FILE_VARIANTS)
}
