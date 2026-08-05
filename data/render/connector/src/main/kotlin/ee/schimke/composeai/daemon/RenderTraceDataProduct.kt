package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.RenderTraceDataProduct
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement

/**
 * `render/trace` v2 backed by the latest host render: the engine's recorded phase spans, with the
 * render metrics alongside.
 *
 * The payload builder lives in `:data-render-core`; this class only adapts it to the daemon
 * registry surface. A result with no [RenderResult.trace] still produces a payload — the v1
 * single-phase shape over `tookMs` — so hosts that don't run a recorder degrade rather than
 * disappear.
 */
class RenderTraceDataProductRegistry : DataProductRegistry {

  private val latestPayloads = ConcurrentHashMap<String, JsonElement>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Render trace",
        facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.PROFILE),
        sampling = SamplingPolicy.Aggregate,
      )
    )

  override fun onRender(previewId: String, result: RenderResult) {
    val metrics = result.metrics
    if (metrics == null && result.trace == null) {
      latestPayloads.remove(previewId)
    } else {
      latestPayloads[previewId] =
        RenderTraceDataProduct.payloadFrom(metrics.orEmpty(), result.trace)
    }
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestPayloads[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latestPayloads[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  companion object {
    const val KIND: String = RenderTraceDataProduct.KIND
    const val SCHEMA_VERSION: Int = RenderTraceDataProduct.SCHEMA_VERSION
  }
}
