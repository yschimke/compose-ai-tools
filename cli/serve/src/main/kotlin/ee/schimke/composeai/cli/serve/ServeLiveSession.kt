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
  /**
   * Frame telemetry for `/status.json`, or null when nobody is collecting (tests, and the fallback
   * lanes). One recorder for the socket's whole life — see [LiveFramePerfStats.Socket].
   */
  private val frameStats: LiveFramePerfStats.Socket? = null,
) {
  @Volatile private var handle: StreamHandle? = null

  /**
   * The last visibility the client reported, re-applied to every stream this socket opens after it.
   * Streams start visible daemon-side, so a hidden socket that restarts its stream (an override
   * change, a `switch`) has to say so again or it silently returns to full rate.
   */
  @Volatile private var visible: Boolean = true

  @Volatile private var visibilityFps: Int? = null

  /**
   * Monotonic frame counter for the life of this socket. See [onFrame] for why it isn't the
   * daemon's.
   */
  private val seq = java.util.concurrent.atomic.AtomicLong(0)

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
        when (val parsed = parseFor(previewId, normalized)) {
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
      is ServeStreamProtocol.ClientMessage.Visibility -> {
        // Remembered, because every later stream this socket opens has to start where the client
        // left it: a `setOverrides` or `switch` while the tab is hidden would otherwise come back
        // at full rate against a client that never said it was looking again.
        visible = message.visible
        visibilityFps = message.fps
        handle?.visibility(message.visible, message.fps)
      }
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
    frameStats?.close()
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
      text = input.text,
      pointerType = input.pointerType,
    )
  }

  private fun restart(parsed: PreviewOverrides) {
    handle?.close()
    handle =
      renderHost.subscribeStream(previewId, parsed, codec, maxFps, onFrame = ::onFrame)?.also {
        applyVisibility(it)
      }
        ?: run {
          send(ServeStreamProtocol.errorMessage("live stream ended"))
          null
        }
  }

  /** Carry the client's last-reported visibility onto a freshly-opened stream. */
  private fun applyVisibility(handle: StreamHandle) {
    if (!visible) handle.visibility(false, visibilityFps)
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
      when (val p = parseFor(message.previewId, nextOverrides)) {
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
    applyVisibility(next)
    previewId = message.previewId
    overrides = nextOverrides
    frameStats?.watching(message.previewId)
  }

  /**
   * Parse [params] as [id]'s overrides, with a `themeProvider` first expanded into the named colour
   * seeds that apply it to a replayed document ([ServeThemeReplay]).
   *
   * The expansion belongs on this lane for the same reason it belongs on the render handlers: a
   * replayed preview has no composition to wrap, so forwarding the raw provider would stream frames
   * the theme never touched while the viewer showed it as selected. Every message that carries
   * overrides goes through here — `setOverrides` and `switch` — as does the socket's initial query
   * in [tryStart], so a stream can't be opened by a route that skipped it.
   *
   * [params] itself is stored un-expanded by the callers: the seeds are derived per preview, and a
   * `switch` that keeps the current overrides must re-derive them for the preview it lands on
   * rather than carrying the previous one's.
   */
  private fun parseFor(id: String, params: Map<String, String>): OverrideParse =
    ServeOverrides.parse(
      ServeThemeReplay.expand(renderHost, id, params).params,
      knobKindsFor(id),
      declaredThemeFqns(),
    )

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
    // `unchanged` heartbeats carry no payload — nothing to paint. Counted before the return: the
    // split between painted frames and heartbeats is what says whether a slow lane is the render
    // loop or the idle backoff doing its job (#4281).
    val payload =
      frame.payloadBase64
        ?: run {
          frameStats?.recordHeartbeat()
          return
        }
    frameStats?.recordFrame(payload.length)
    val codec = frame.codec?.name?.lowercase() ?: "png"
    // This connection's own sequence, NOT the daemon's `frame.seq`.
    //
    // The daemon numbers per *stream* and starts each one at zero, and one socket outlives several
    // of them: every `setOverrides` restarts the held session ([restart]) and every `switch` opens
    // a replacement ([switchTo]), each with a fresh `frameStreamId` counting from scratch. Relaying
    // those numbers made the socket's sequence jump backwards on any knob change.
    //
    // Nothing noticed while the browser painted every frame it received. It stops being harmless
    // the moment the client uses `seq` to order paints (issue #4285): a viewer forty frames into a
    // session that then restarts at 1 would reject every subsequent frame as stale and freeze the
    // lane for good. The socket is one logical stream to the browser and has to be numbered like
    // one — the same thing [ServeStreamSession] already does with its own counter on the snapshot
    // lane.
    send(
      ServeStreamProtocol.frameMessage(
        seq.getAndIncrement(),
        frame.widthPx,
        frame.heightPx,
        payload,
        codec,
      )
    )
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
      /** Where this socket's frame telemetry lands; null when nothing is collecting it. */
      frameStats: LiveFramePerfStats? = null,
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
              ServeThemeReplay.expand(renderHost, previewId, normalizedOverrides).params,
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
      // Opened *before* the subscribe because the broadcast hub replays its last painted frame
      // into `onFrame` synchronously for a late joiner — a recorder created after the call would
      // miss that frame. Closed again right below if the open fails, so a stream that never
      // started is never counted as an open socket.
      val recorder = frameStats?.openSocket(system, previewId)
      val session =
        ServeLiveSession(
          renderHost,
          previewId,
          normalizedOverrides,
          codec,
          maxFps,
          send,
          system,
          recorder,
        )
      session.handle =
        renderHost.subscribeStream(
          previewId,
          initial,
          codec,
          maxFps,
          onUnavailable = onUnavailable,
          onFrame = session::onFrame,
        )
          ?: run {
            recorder?.close()
            return null
          }
      return session
    }
  }
}
