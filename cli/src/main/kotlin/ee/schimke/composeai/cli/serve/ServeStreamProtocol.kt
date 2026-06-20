package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

    /**
     * A user input event to dispatch into a live (daemon-streamed) composition. [kind] is the wire
     * spelling of an `InteractiveInputKind` (`click`, `pointerDown`, …); coordinates are
     * image-natural pixels. Ignored by the snapshot fallback lane (which can't accept input).
     */
    data class Input(
      val kind: String,
      val pixelX: Int?,
      val pixelY: Int?,
      val pointerId: Int?,
      val scrollDeltaY: Float?,
      val keyCode: String?,
    ) : ClientMessage

    /** Unrecognised message; [reason] is echoed back as an error rather than crashing the lane. */
    data class Unsupported(val reason: String) : ClientMessage
  }

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Parse a client text frame. Never throws — malformed JSON *and* well-formed JSON of the wrong
   * shape (`{"type":{}}`, `{"overrides":[]}`, a non-string override value) both become
   * [ClientMessage.Unsupported] or degrade gracefully, so a bad message reports a non-fatal error
   * rather than tearing down the live stream. All element access uses `as?` and is guarded by a
   * catch-all for defence in depth.
   */
  fun parseClient(text: String): ClientMessage {
    return try {
      val obj = json.parseToJsonElement(text).jsonObject
      when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull) {
        "setOverrides" -> {
          val overrides =
            (obj["overrides"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
              (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            } ?: emptyList()
          ClientMessage.SetOverrides(overrides.toMap())
        }
        "requestFrame" -> ClientMessage.RequestFrame
        "input" ->
          ClientMessage.Input(
            kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "",
            pixelX = (obj["pixelX"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            pixelY = (obj["pixelY"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            pointerId = (obj["pointerId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            scrollDeltaY = (obj["scrollDeltaY"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
            keyCode = (obj["keyCode"] as? JsonPrimitive)?.contentOrNull,
          )
        else -> ClientMessage.Unsupported("unknown message type: $type")
      }
    } catch (e: Exception) {
      ClientMessage.Unsupported("malformed message: ${e.message}")
    }
  }

  /** A rendered frame from raw PNG bytes (snapshot lane) — base64-encodes them as a `png` frame. */
  fun frameMessage(seq: Long, widthPx: Int, heightPx: Int, png: ByteArray): String =
    frameMessage(seq, widthPx, heightPx, Base64.getEncoder().encodeToString(png), "png")

  /**
   * A rendered frame from an already-base64 payload (live daemon-stream lane), tagged with [codec]
   * (`png`/`webp`) so the browser builds the right `data:` URL. Pixel size + per-connection [seq].
   */
  fun frameMessage(
    seq: Long,
    widthPx: Int,
    heightPx: Int,
    dataBase64: String,
    codec: String,
  ): String {
    val obj = buildJsonObject {
      put("type", "frame")
      put("seq", seq)
      put("codec", codec)
      put("widthPx", widthPx)
      put("heightPx", heightPx)
      put("dataBase64", dataBase64)
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
