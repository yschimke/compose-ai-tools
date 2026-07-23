package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
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

  /** Push the first frame at the initial overrides when the connection opens. */
  fun onOpen() = renderCurrent()

  /** Handle one client text message: update overrides / re-render, or echo a protocol error. */
  fun onClientMessage(text: String) {
    when (val message = ServeStreamProtocol.parseClient(text)) {
      is ServeStreamProtocol.ClientMessage.SetOverrides ->
        // Validate before committing: a bad override message is reported but must not poison the
        // session — the previous (valid) overrides stay in effect for subsequent frames.
        when (val parsed = ServeOverrides.parse(message.overrides, knobKindsFor(previewId))) {
          is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
          is OverrideParse.Ok -> {
            overrides = message.overrides
            sendFrame(parsed.overrides)
          }
        }
      ServeStreamProtocol.ClientMessage.RequestFrame -> renderCurrent()
      is ServeStreamProtocol.ClientMessage.Switch -> switchTo(message)
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
    val nextOverrides = message.overrides ?: overrides
    val parsed =
      when (val p = ServeOverrides.parse(nextOverrides, knobKindsFor(message.previewId))) {
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
   * Declared knob kinds for [id], so a bare `knob.<key>=<value>` frame is typed from the preview.
   */
  private fun knobKindsFor(id: String): Map<String, String> =
    ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == id })

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
    when (val parsed = ServeOverrides.parse(overrides, knobKindsFor(previewId))) {
      is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
      is OverrideParse.Ok -> sendFrame(parsed.overrides)
    }
  }

  private fun sendFrame(overrides: PreviewOverrides) {
    when (val outcome = renderHost.render(previewId, overrides)) {
      is RenderOutcome.Ok -> {
        val (w, h) = WebEscaping.pngDimensions(outcome.png)
        send(ServeStreamProtocol.frameMessage(seq.getAndIncrement(), w, h, outcome.png))
      }
      RenderOutcome.NotFound -> send(ServeStreamProtocol.errorMessage("no such preview"))
      is RenderOutcome.Failed -> send(ServeStreamProtocol.errorMessage(outcome.reason))
      // Daemon mid-render — skip this frame silently rather than tear the stream down with an
      // error; the next frame request retries once the daemon frees. (A catalog host serves baked
      // instead of returning Busy, so this is only reachable for a bare daemon-backed stream.)
      RenderOutcome.Busy -> {}
    }
  }
}
