package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `render/trace` v1 backed by the latest host render metrics.
 *
 * This establishes the data-product surface with a stable trace-shaped payload. Renderer-side
 * nested `Trace.beginSection` capture can extend the `phases` tree later without changing the
 * fetch/attach contract.
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
      )
    )

  override fun onRender(previewId: String, result: RenderResult) {
    val metrics = result.metrics
    if (metrics == null) {
      latestPayloads.remove(previewId)
    } else {
      latestPayloads[previewId] = payloadFrom(metrics)
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

  private fun payloadFrom(metrics: Map<String, Long>): JsonElement {
    val totalMs = metrics["tookMs"]?.coerceAtLeast(0L) ?: 0L
    return buildJsonObject {
      put("totalMs", totalMs)
      put(
        "phases",
        buildJsonArray {
          add(
            buildJsonObject {
              put("name", "render")
              put("startMs", 0L)
              put("durationMs", totalMs)
            }
          )
        },
      )
      putJsonObject("metrics") {
        metrics.toSortedMap().forEach { (name, value) -> put(name, value) }
      }
    }
  }

  companion object {
    const val KIND: String = "render/trace"
    const val SCHEMA_VERSION: Int = 1
  }
}
