package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.TestFailureDataProduct
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement

/** `test/failure` v1 backed by the latest failed render for a preview. */
class TestFailureDataProductRegistry : DataProductRegistry {

  private val latestFailures = ConcurrentHashMap<String, JsonElement>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = false,
        fetchable = true,
        requiresRerender = false,
        displayName = "Render failure",
        facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.DIAGNOSTIC),
        sampling = SamplingPolicy.Failure,
      )
    )

  override fun onRender(previewId: String, result: RenderResult) {
    latestFailures.remove(previewId)
  }

  override fun onRenderFailed(previewId: String, cause: Throwable) {
    latestFailures[previewId] = TestFailureDataProduct.payloadFrom(cause)
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestFailures[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> =
    emptyList()

  companion object {
    const val KIND: String = TestFailureDataProduct.KIND
    const val SCHEMA_VERSION: Int = TestFailureDataProduct.SCHEMA_VERSION
  }
}
