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
 * Client → server: `setOverrides` (replace the display overrides and re-render), `requestFrame`
 * (re-render at the current overrides), `switch` (re-point the socket at another preview), `input`
 * (dispatch a pointer/key event into a live composition) and `visibility` (throttle the stream
 * while nobody is looking at it). Server → client: `frame` (a rendered PNG, base64, with its pixel
 * size and a monotonic `seq`) and `error`.
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
     * Switch the connection to a different preview without reconnecting. [overrides] is optional —
     * when omitted the current overrides carry over. Lets one socket walk a module's previews (the
     * "switch previews" lane) instead of opening a new `/ws/{id}` per preview.
     */
    data class Switch(val previewId: String, val overrides: Map<String, String>?) : ClientMessage

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
      /**
       * The character a `keyDown` typed — the browser's `KeyboardEvent.key` when printable. The
       * keycode alone identifies a physical key and cannot type (issue #3491).
       */
      val text: String?,
      /** DOM `PointerEvent.pointerType`: `"mouse"` / `"touch"` / `"pen"`. Absent means touch. */
      val pointerType: String?,
    ) : ClientMessage

    /**
     * Whether the client is still looking at this lane — a hidden tab, or a grid card scrolled out
     * of the viewport. Throttles the daemon's stream (both what it emits and what it renders)
     * without tearing the held session down, so coming back repaints from a keyframe rather than
     * reconnecting. [fps] optionally names the throttled rate; absent means the daemon's default (1
     * fps). Ignored by the snapshot fallback lane, which renders only when asked.
     */
    data class Visibility(val visible: Boolean, val fps: Int?) : ClientMessage

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
        "switch" -> {
          val previewId = (obj["previewId"] as? JsonPrimitive)?.contentOrNull
          // An override block is optional; when present it follows the same shape as setOverrides.
          val overrides =
            (obj["overrides"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
              (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }
          if (previewId.isNullOrBlank()) ClientMessage.Unsupported("switch missing previewId")
          else ClientMessage.Switch(previewId, overrides?.toMap())
        }
        "visibility" -> {
          val visible = (obj["visible"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
          // No usable `visible` is not a throttle request of either polarity, so it can't be
          // guessed at: reporting it back beats silently pinning the lane visible (a hidden tab
          // that keeps rendering) or hidden (a visible one stuck at 1 fps).
          if (visible == null) ClientMessage.Unsupported("visibility missing boolean visible")
          else
            ClientMessage.Visibility(
              visible = visible,
              // A non-positive fps would mean "never emit"; drop it and let the daemon apply its
              // own throttled default instead.
              fps = (obj["fps"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
        "input" ->
          ClientMessage.Input(
            kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "",
            pixelX = (obj["pixelX"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            pixelY = (obj["pixelY"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            pointerId = (obj["pointerId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            scrollDeltaY = (obj["scrollDeltaY"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
            keyCode = (obj["keyCode"] as? JsonPrimitive)?.contentOrNull,
            text = (obj["text"] as? JsonPrimitive)?.contentOrNull,
            pointerType = (obj["pointerType"] as? JsonPrimitive)?.contentOrNull,
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
