package ee.schimke.composeai.data.render

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object TestFailureDataProduct {
  const val KIND: String = "test/failure"
  const val SCHEMA_VERSION: Int = 1

  fun payloadFrom(cause: Throwable, phase: String = "unknown"): JsonElement {
    val stackFrames = cause.stackTrace.map { it.toString() }
    return buildJsonObject {
      put("status", "failed")
      put("phase", phase)
      putJsonObject("error") {
        put("type", cause.javaClass.simpleName.ifBlank { cause.javaClass.name })
        put("message", cause.message ?: cause.javaClass.name)
        put("topFrame", topFrame(cause))
        put(
          "stackTrace",
          buildJsonArray { stackFrames.take(MAX_STACK_FRAMES).forEach { add(JsonPrimitive(it)) } },
        )
      }
      put("partialScreenshot", JsonNull)
      put("partialScreenshotAvailable", false)
      put("pendingEffects", buildJsonArray {})
      put("runningAnimations", buildJsonArray {})
      putJsonObject("frameClockState") { put("status", "unknown") }
      putJsonObject("lastSnapshotSummary") {
        put("stateObjects", JsonNull)
        put("valuesCaptured", false)
        put("redaction", "state values are not captured in schema v1")
      }
    }
  }

  private fun topFrame(cause: Throwable): String? =
    cause.stackTrace.firstOrNull()?.let { frame ->
      when {
        frame.fileName != null && frame.lineNumber > 0 -> "${frame.fileName}:${frame.lineNumber}"
        frame.fileName != null -> frame.fileName
        else -> "${frame.className}.${frame.methodName}"
      }
    }

  private const val MAX_STACK_FRAMES = 32
}
