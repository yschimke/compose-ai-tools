package ee.schimke.composeai.data.render

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object RenderTraceDataProduct {
  const val KIND: String = "render/trace"
  const val SCHEMA_VERSION: Int = 1

  fun payloadFrom(metrics: Map<String, Long>): JsonElement {
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
}
