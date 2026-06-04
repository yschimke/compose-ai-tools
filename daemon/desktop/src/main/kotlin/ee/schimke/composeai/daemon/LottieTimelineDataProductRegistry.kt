package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.preview.lottie.lottieTimelineInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metadata product describing a `kind=LOTTIE` preview's animation timeline — total frames, frame
 * rate, wall-clock duration, and canvas size. A VS Code timeline scrubber fetches this to label its
 * slider and map a slider position to `renderNow.overrides.lottie.progress` (see
 * [docs/LOTTIE_PREVIEWS.md](../../../../../../docs/LOTTIE_PREVIEWS.md) follow-up #2).
 *
 * The timeline is read straight from the asset (no render needed), so the product is available the
 * moment the preview is known — `requiresRerender = false`. The asset is resolved off the daemon's
 * render classpath via the same loader path [ee.schimke.composeai.preview.lottie.LottiePreview]
 * uses (the plugin links the consumer's processed-resources dir onto the daemon classpath), so a
 * `data/fetch` can answer before the first render lands.
 *
 * Only `kind=LOTTIE` previews carry a timeline; a fetch for any other preview returns
 * [DataProductRegistry.Outcome.NotAvailable] (the preview exists but has no Lottie timeline),
 * mirroring how the device-clip product declines previews without a round device.
 */
class LottieTimelineDataProductRegistry(private val previewIndex: PreviewIndex) :
  DataProductRegistry {

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Lottie timeline",
        facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.INTERACTIVE),
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = payloadFor(previewId) ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = payloadFor(previewId) ?: return emptyList()
    return listOf(
      DataProductAttachment(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  /**
   * Resolve the preview's Lottie asset from the index and parse its timeline. Returns null when the
   * preview is unknown, isn't a Lottie, carries no asset path, or the asset can't be parsed — every
   * "no timeline here" case the caller turns into [DataProductRegistry.Outcome.NotAvailable] / an
   * empty attachment list.
   */
  private fun payloadFor(previewId: String): JsonElement? {
    val preview = previewIndex.snapshot()[previewId] ?: return null
    val params = preview.params ?: return null
    if (!"LOTTIE".equals(params.kind, ignoreCase = true)) return null
    val asset = params.assetPath?.takeIf { it.isNotBlank() } ?: return null
    val info = lottieTimelineInfo(asset) ?: return null
    return buildJsonObject {
      put("totalFrames", info.durationFrames)
      put("frameRate", info.frameRate)
      put("durationMillis", info.durationMillis)
      put("width", info.widthPx)
      put("height", info.heightPx)
    }
  }

  companion object {
    const val KIND: String = "animation/lottie"
    const val SCHEMA_VERSION: Int = 1
  }
}
