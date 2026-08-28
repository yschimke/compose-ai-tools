package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.web.WebEscaping
import java.util.concurrent.atomic.AtomicLong

/**
 * One streamed-frame connection's logic, independent of the WebSocket transport — the tier-2
 * streaming spike's core. Holds the current overrides for a single preview, renders through the
 * shared [ServeRenderHost] (so the tier-1 mutex + cache + multi-client serialisation are reused),
 * and emits frames via the [send] callback. The Ktor `webSocket` route is a thin adapter that wires
 * [send] to the socket's outgoing channel and feeds incoming text to [onClientMessage].
 *
 * Keeping the transport out means this is unit-testable headlessly: drive it with raw client
 * messages and a capturing [send], and assert the frames/errors emitted — no socket, no browser.
 *
 * This re-renders on every client message (input → frame), which is the spike's proof of the
 * transport. The follow-on swaps this backend for the daemon's native `streamFrame` push (so frames
 * also arrive *unprompted* as the composition changes) without changing [send]'s wire shape.
 */
class ServeStreamSession(
  private val renderHost: ServeHost,
  previewId: String,
  initialOverrides: Map<String, String> = emptyMap(),
  private val send: (String) -> Unit,
  /** Catalog id, for the per-system override policy. Required: there is no "no policy" case. */
  private val system: String,
  /**
   * Why this connection is on the snapshot-fallback lane rather than the live daemon stream — the
   * daemon's original failure captured by [ServeLiveSession.tryStart]'s `onUnavailable` (e.g.
   * `interactive session already held`, a `stream/start` timeout, or "no live daemon twin"). Folded
   * into the input-rejection message so a click reports *why* input isn't live instead of the
   * opaque "input requires a live stream". Null when the reason is unknown (e.g. a backend that
   * returned null without one).
   */
  private val liveUnavailableReason: String? = null,
) {
  private var previewId: String = previewId
  private var overrides: Map<String, String> = initialOverrides
  private val seq = AtomicLong(0)

  /**
   * This catalog's always-dark (and any future per-system) override policy, applied to every
   * client-supplied override map before it is parsed. Resolved from [system] rather than injected,
   * so a socket lane can't be wired up without it — see [ServeWeb.SystemDisplay].
   */
  private fun normalize(overrides: Map<String, String>): Map<String, String> =
    ServeWeb.SystemDisplay.normalizeOverrideParams(system, overrides)

  /** Push the first frame at the initial overrides when the connection opens. */
  fun onOpen() = renderCurrent()

  /** Handle one client text message: update overrides / re-render, or echo a protocol error. */
  fun onClientMessage(text: String) {
    when (val message = ServeStreamProtocol.parseClient(text)) {
      is ServeStreamProtocol.ClientMessage.SetOverrides -> {
        // Validate before committing: a bad override message is reported but must not poison the
        // session — the previous (valid) overrides stay in effect for subsequent frames.
        val normalized = normalize(message.overrides)
        when (val parsed = parseFor(previewId, normalized)) {
          is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
          is OverrideParse.Ok -> {
            overrides = normalized
            sendFrame(parsed.overrides)
          }
        }
      }
      ServeStreamProtocol.ClientMessage.RequestFrame -> renderCurrent()
      is ServeStreamProtocol.ClientMessage.Switch -> switchTo(message)
      is ServeStreamProtocol.ClientMessage.Visibility ->
        // Nothing to throttle: this lane renders only when the client asks for a frame, so a
        // hidden tab already costs nothing. Accepted silently rather than reported like `input`
        // is — the client sends one of these on every tab switch and card scroll, and answering
        // each with an error would paint the viewer's error banner over a lane that is working
        // exactly as intended.
        Unit
      is ServeStreamProtocol.ClientMessage.Input ->
        // The snapshot fallback can't dispatch input into a live composition — only the daemon
        // stream lane ([ServeLiveSession]) can. Report it — with the original reason the live lane
        // was unavailable when known — rather than silently dropping or showing a bare message.
        send(ServeStreamProtocol.errorMessage(inputUnavailableMessage()))
      is ServeStreamProtocol.ClientMessage.Unsupported ->
        send(ServeStreamProtocol.errorMessage(message.reason))
    }
  }

  /**
   * Switch this connection to another preview (optionally with new overrides) and re-render. An
   * unknown preview is reported and the current one is kept, mirroring the live lane's behaviour.
   */
  private fun switchTo(message: ServeStreamProtocol.ClientMessage.Switch) {
    if (renderHost.previews.none { it.id == message.previewId }) {
      send(ServeStreamProtocol.errorMessage("cannot switch to preview: ${message.previewId}"))
      return
    }
    // Validate before committing either field: a bad override must leave the current preview +
    // overrides intact (so later requestFrame keeps working), mirroring setOverrides / the live
    // lane.
    val nextOverrides = message.overrides?.let(::normalize) ?: overrides
    val parsed =
      when (val p = parseFor(message.previewId, nextOverrides)) {
        is OverrideParse.Invalid -> {
          send(ServeStreamProtocol.errorMessage(p.message))
          return
        }
        is OverrideParse.Ok -> p.overrides
      }
    previewId = message.previewId
    overrides = nextOverrides
    sendFrame(parsed)
  }

  /**
   * Parse [params] as [id]'s overrides, with a `themeProvider` first expanded into the named colour
   * seeds that apply it to a replayed document ([ServeThemeReplay]).
   *
   * The expansion belongs on this lane for the same reason it belongs on the render handlers: a
   * replayed preview has no composition to wrap, so forwarding the raw provider would stream frames
   * the theme never touched while the viewer showed it as selected. Every message that carries
   * overrides goes through here — `setOverrides`, `switch`, and the re-parse behind `requestFrame`
   * — so a socket can't reach the renderer by a route that skipped it.
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
   * Declared knob kinds for [id], so a bare `knob.<key>=<value>` frame is typed from the preview.
   */
  private fun knobKindsFor(id: String): Map<String, String> =
    ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == id })

  /**
   * The session's declared `@ThemeCatalog` provider FQNs, so a `themeProvider` this catalog never
   * declared is reported as an error rather than silently rendering the default theme.
   */
  private fun declaredThemeFqns(): Set<String> =
    renderHost.declaredThemes.map { it.providerFqn }.toSet()

  /**
   * The message for an [input][ServeStreamProtocol.ClientMessage.Input] the snapshot lane can't
   * dispatch. When the live lane's original failure was captured, surface it so the user sees *why*
   * (e.g. "…: the daemon could not hold an interactive session for this preview") instead of a bare
   * "input requires a live stream".
   */
  private fun inputUnavailableMessage(): String =
    if (liveUnavailableReason.isNullOrBlank()) "input requires a live stream"
    else "input requires a live stream — unavailable: $liveUnavailableReason"

  private fun renderCurrent() {
    when (val parsed = parseFor(previewId, overrides)) {
      is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
      is OverrideParse.Ok -> sendFrame(parsed.overrides)
    }
  }

  private fun sendFrame(overrides: PreviewOverrides) {
    // The viewer sends its initial `setOverrides` on open and doesn't poll for frames, so a
    // swallowed frame would leave the socket stuck at "connecting…". When the daemon is mid-render
    // (RenderOutcome.Busy from the serve host's bounded lock), retry a few times as it frees, then
    // surface an error rather than hang. (A catalog host serves baked instead of returning Busy, so
    // Busy only reaches here for a bare daemon-backed stream.)
    var busyAttempts = 0
    while (true) {
      when (val outcome = renderHost.render(previewId, overrides)) {
        is RenderOutcome.Ok -> {
          val (w, h) = WebEscaping.pngDimensions(outcome.png)
          send(ServeStreamProtocol.frameMessage(seq.getAndIncrement(), w, h, outcome.png))
          return
        }
        RenderOutcome.NotFound -> {
          send(ServeStreamProtocol.errorMessage("no such preview"))
          return
        }
        is RenderOutcome.Failed -> {
          send(ServeStreamProtocol.errorMessage(outcome.reason))
          return
        }
        RenderOutcome.Busy -> {
          if (busyAttempts++ >= BUSY_FRAME_RETRIES) {
            send(ServeStreamProtocol.errorMessage("render busy; please retry"))
            return
          }
          Thread.sleep(BUSY_FRAME_RETRY_BACKOFF_MS)
        }
      }
    }
  }

  private companion object {
    /** Retries when a snapshot frame's render backs off Busy before surfacing an error. */
    private const val BUSY_FRAME_RETRIES = 2
    private const val BUSY_FRAME_RETRY_BACKOFF_MS = 250L
  }
}
