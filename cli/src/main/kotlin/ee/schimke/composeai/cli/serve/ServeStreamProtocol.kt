package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The tiny JSON message protocol for the `serve` streamed-frame lane (`/ws/{previewId}`) — the
 * tier-2 streaming spike. Pure (no ktor / IO), so the wire format is unit-tested directly and the
 * WebSocket route stays a thin adapter.
 *
 * Client → server: `setOverrides` (replace the display overrides and re-render) and `requestFrame`
 * (re-render at the current overrides). Server → client: `frame` (a rendered PNG, base64, with its
 * pixel size and a monotonic `seq`) and `error`.
 *
 * This deliberately mirrors the daemon's native streaming protocol (`stream/start` + `streamFrame`,
 * `docs/daemon/STREAMING.md`): base64 frame payloads + a `codec` tag + a monotonic sequence, so the
 * follow-on that swaps the re-render backend for real `streamFrame` notifications keeps the same
 * browser-facing shape.
 */
object ServeStreamProtocol {

  /** A message from the browser. Unknown / malformed input is surfaced as [Unsupported]. */
  sealed interface ClientMessage {
    /** Replace the override set (same keys as `/render`) and push a fresh frame. */
    data class SetOverrides(val overrides: Map<String, String>) : ClientMessage

    /** Re-render and push a frame at the current overrides. */
    data object RequestFrame : ClientMessage

    /** Unrecognised message; [reason] is echoed back as an error rather than crashing the lane. */
    data class Unsupported(val reason: String) : ClientMessage
  }

  private val json = Json { ignoreUnknownKeys = true }

  /** Parse a client text frame. Never throws — bad input becomes [ClientMessage.Unsupported]. */
  fun parseClient(text: String): ClientMessage {
    val obj =
      try {
        json.parseToJsonElement(text).jsonObject
      } catch (e: Exception) {
        return ClientMessage.Unsupported("invalid JSON: ${e.message}")
      }
    return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
      "setOverrides" -> {
        val overrides =
          obj["overrides"]?.jsonObject?.entries?.mapNotNull { (k, v) ->
            v.jsonPrimitive.contentOrNull?.let { k to it }
          } ?: emptyList()
        ClientMessage.SetOverrides(overrides.toMap())
      }
      "requestFrame" -> ClientMessage.RequestFrame
      else -> ClientMessage.Unsupported("unknown message type: $type")
    }
  }

  /** A rendered frame: PNG bytes base64-encoded, with pixel size and a per-connection [seq]. */
  fun frameMessage(seq: Long, widthPx: Int, heightPx: Int, png: ByteArray): String {
    val obj = buildJsonObject {
      put("type", "frame")
      put("seq", seq)
      put("codec", "png")
      put("widthPx", widthPx)
      put("heightPx", heightPx)
      put("dataBase64", Base64.getEncoder().encodeToString(png))
    }
    return obj.toString()
  }

  /**
   * A non-fatal error (bad overrides, render failure); the lane stays open for the next message.
   */
  fun errorMessage(message: String): String {
    val obj = buildJsonObject {
      put("type", "error")
      put("message", message)
    }
    return obj.toString()
  }
}
