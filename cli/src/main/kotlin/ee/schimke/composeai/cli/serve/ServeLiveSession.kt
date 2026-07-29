package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * One **live** streamed-frame connection backed by the daemon's `stream/start` +
 * `interactive/input` protocol (tier-2). Frames are *pushed* by the daemon (animations,
 * recomposition, input results) — not re-requested per client message — and decoded inline (no
 * disk), which is the real upgrade over the [ServeStreamSession] snapshot fallback.
 *
 * Created via [tryStart], which returns `null` when the backend doesn't support streaming so the
 * WebSocket route can fall back to [ServeStreamSession]. Like that class it's transport-agnostic:
 * frames + errors go out through the [send] callback, so it's unit-testable without a socket.
 */
class ServeLiveSession
private constructor(
  private val renderHost: ServeHost,
  private var previewId: String,
  private var overrides: Map<String, String>,
  private val codec: StreamCodec?,
  private val maxFps: Int?,
  private val send: (String) -> Unit,
  private val system: String,
) {
  @Volatile private var handle: StreamHandle? = null

  /**
   * This catalog's always-dark (and any future per-system) override policy, applied to every
   * client-supplied override map before it is parsed. Resolved from [system] rather than injected,
   * so a socket lane can't be wired up without it — see [ServeWeb.SystemDisplay].
   */
  private fun normalize(overrides: Map<String, String>): Map<String, String> =
    ServeWeb.SystemDisplay.normalizeOverrideParams(system, overrides)

  /** Handle one client text message: forward input, restart the stream on new overrides, etc. */
  fun onClientMessage(text: String) {
    when (val message = ServeStreamProtocol.parseClient(text)) {
      is ServeStreamProtocol.ClientMessage.SetOverrides -> {
        val normalized = normalize(message.overrides)
        when (
          val parsed =
            ServeOverrides.parse(normalized, knobKindsFor(previewId), declaredThemeFqns())
        ) {
          is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
          is OverrideParse.Ok -> {
            // stream/start fixes overrides for the held session, so an override change restarts it.
            overrides = normalized
            restart(parsed.overrides)
          }
        }
      }
      is ServeStreamProtocol.ClientMessage.Input -> dispatchInput(message)
      is ServeStreamProtocol.ClientMessage.Switch -> switchTo(message)
      // Frames are pushed by the daemon; an explicit refresh is a no-op on the live lane.
      ServeStreamProtocol.ClientMessage.RequestFrame -> Unit
      is ServeStreamProtocol.ClientMessage.Unsupported ->
        send(ServeStreamProtocol.errorMessage(message.reason))
    }
  }

  /** Tear down the daemon stream. Idempotent. */
  fun close() {
    handle?.close()
    handle = null
  }

  private fun dispatchInput(input: ServeStreamProtocol.ClientMessage.Input) {
    val kind = parseKind(input.kind)
    if (kind == null) {
      send(ServeStreamProtocol.errorMessage("unknown input kind: ${input.kind}"))
      return
    }
    handle?.input(
      kind = kind,
      pixelX = input.pixelX,
      pixelY = input.pixelY,
      pointerId = input.pointerId,
      scrollDeltaY = input.scrollDeltaY,
      keyCode = input.keyCode,
    )
  }

  private fun restart(parsed: PreviewOverrides) {
    handle?.close()
    handle =
      renderHost.subscribeStream(previewId, parsed, codec, maxFps, onFrame = ::onFrame)
        ?: run {
          send(ServeStreamProtocol.errorMessage("live stream ended"))
          null
        }
  }

  /**
   * Move this connection to a different preview (optionally with new overrides) without
   * reconnecting. The new stream is opened *before* the old one is dropped, so a switch to a
   * missing preview (or a backend that can't stream it) reports an error and leaves the current
   * view intact rather than going blank.
   */
  private fun switchTo(message: ServeStreamProtocol.ClientMessage.Switch) {
    val nextOverrides = message.overrides?.let(::normalize) ?: overrides
    val parsed =
      when (
        val p =
          ServeOverrides.parse(nextOverrides, knobKindsFor(message.previewId), declaredThemeFqns())
      ) {
        is OverrideParse.Invalid -> {
          send(ServeStreamProtocol.errorMessage(p.message))
          return
        }
        is OverrideParse.Ok -> p.overrides
      }
    val next =
      renderHost.subscribeStream(message.previewId, parsed, codec, maxFps, onFrame = ::onFrame)
    if (next == null) {
      send(ServeStreamProtocol.errorMessage("cannot switch to preview: ${message.previewId}"))
      return
    }
    handle?.close()
    handle = next
    previewId = message.previewId
    overrides = nextOverrides
  }

  /**
   * Declared knob kinds for [id], so a bare `knob.<key>=<value>` message is typed from the preview.
   */
  private fun knobKindsFor(id: String): Map<String, String> =
    ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == id })

  /**
   * The session's declared `@ThemeCatalog` provider FQNs, so a `themeProvider` this catalog never
   * declared is reported as an error rather than silently rendering the default theme.
   */
  private fun declaredThemeFqns(): Set<String> =
    renderHost.declaredThemes.map { it.providerFqn }.toSet()

  private fun onFrame(frame: StreamFrameParams) {
    // `unchanged` heartbeats carry no payload — nothing to paint.
    val payload = frame.payloadBase64 ?: return
    val codec = frame.codec?.name?.lowercase() ?: "png"
    send(ServeStreamProtocol.frameMessage(frame.seq, frame.widthPx, frame.heightPx, payload, codec))
  }

  companion object {
    /** Wire spellings of the input kinds a browser can produce. */
    private fun parseKind(wire: String): InteractiveInputKind? =
      when (wire) {
        "click" -> InteractiveInputKind.CLICK
        "pointerDown" -> InteractiveInputKind.POINTER_DOWN
        "pointerMove" -> InteractiveInputKind.POINTER_MOVE
        "pointerUp" -> InteractiveInputKind.POINTER_UP
        "rotaryScroll" -> InteractiveInputKind.ROTARY_SCROLL
        "keyDown" -> InteractiveInputKind.KEY_DOWN
        "keyUp" -> InteractiveInputKind.KEY_UP
        else -> null
      }

    /**
     * Try to open a daemon-backed live stream. Returns `null` when streaming is unsupported, or
     * when the initial-overrides query is invalid — in both cases the caller falls back to the
     * snapshot lane, which re-parses the same overrides and reports the reason once. The invalid
     * case used to degrade to the preview's defaults and subscribe anyway; that quietly served a
     * default-themed stream to a client that had asked for something else.
     */
    fun tryStart(
      renderHost: ServeHost,
      previewId: String,
      overrides: Map<String, String>,
      codec: StreamCodec? = null,
      maxFps: Int? = null,
      send: (String) -> Unit,
      /** Catalog id, for the per-system override policy. Required: there is no "no policy" case. */
      system: String,
      onUnavailable: ((String) -> Unit)? = null,
    ): ServeLiveSession? {
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      val normalizedOverrides = ServeWeb.SystemDisplay.normalizeOverrideParams(system, overrides)
      // Validate the socket's *initial* query too, not just the later `setOverrides` / `switch`
      // messages. Degrading an invalid parse to `PreviewOverrides()` here would subscribe the
      // client to a stream rendered under the default theme while it believes it asked for another
      // one — the exact silent-default this validation exists to stop, and worse on a live stream
      // than on a snapshot because a later frame would clear the viewer's error overlay while the
      // wrong stream kept running. Refuse to start instead: the reason goes out through
      // [onUnavailable], and the caller's fallback to [ServeStreamSession] re-parses the same
      // overrides and reports it once, rather than this lane and that one both sending it.
      val initial =
        when (
          val parsed =
            ServeOverrides.parse(
              normalizedOverrides,
              knobKinds,
              renderHost.declaredThemes.map { it.providerFqn }.toSet(),
            )
        ) {
          is OverrideParse.Invalid -> {
            onUnavailable?.invoke(parsed.message)
            return null
          }
          is OverrideParse.Ok -> parsed.overrides
        }
      val session =
        ServeLiveSession(renderHost, previewId, normalizedOverrides, codec, maxFps, send, system)
      session.handle =
        renderHost.subscribeStream(
          previewId,
          initial,
          codec,
          maxFps,
          onUnavailable = onUnavailable,
          onFrame = session::onFrame,
        ) ?: return null
      return session
    }
  }
}
