package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence

/**
 * Per-event dispatch context passed to each [RecordingScriptEventHandler] invocation.
 *
 * Carries the virtual-clock stamps the handler needs when driving the held scene/rule. `tNanos` and
 * `tMs` are the **frame's** virtual time, not the event's `tMs` — events whose `event.tMs <=
 * frame.tMs` are dispatched against the upcoming frame, so handlers should send pointer events at
 * the frame's nanoTime so `scene.render(nanoTime)` and `sendPointerEvent` agree.
 *
 * Live tick-loop callers reuse the same shape with the wall-clock-anchored virtual nanoTime.
 */
interface RecordingDispatchContext {
  /** Virtual nanoTime of the frame this event is dispatched at. */
  val tNanos: Long

  /** Virtual milliseconds (`tNanos / 1_000_000`). */
  val tMs: Long
}

/** Default value-class implementation — backends can pass an instance per event. */
data class SimpleRecordingDispatchContext(override val tNanos: Long, override val tMs: Long) :
  RecordingDispatchContext

/**
 * Single-event dispatch interface. One implementation per `(host, event-kind)` pair. The session
 * owns a [RecordingScriptHandlerRegistry] mapping kind → handler and looks up by `event.kind`.
 *
 * Implementations close over host-specific scene/rule state (Skiko `ImageComposeScene` on desktop,
 * `AndroidInteractiveSession` on Android). They MUST return well-formed [RecordingScriptEvidence] —
 * never throw — so a malformed event aborts at most a single dispatch slot, not the whole
 * recording. Use [appliedEvidence] / [unsupportedEvidence] to build the result.
 */
fun interface RecordingScriptEventHandler {
  fun apply(event: RecordingScriptEvent, ctx: RecordingDispatchContext): RecordingScriptEvidence
}

/**
 * Side-band dispatch hook for cross-cutting connectors that need to react to every recording script
 * event (analytics, tracing, the ambient connector's wake-on-input). Observers fire **before** the
 * registered handler so they can mutate connector-side state the handler reads — the ambient
 * connector flips [AmbientStateController] before the touch click reaches the held rule's pointer
 * pipeline.
 *
 * Observer faults are isolated by [RecordingScriptHandlerRegistry.dispatch] via `runCatching` — the
 * recording session is the source of truth for evidence shape and shouldn't fail because an
 * extension threw.
 */
fun interface RecordingScriptDispatchObserver {
  fun beforeDispatch(event: RecordingScriptEvent, ctx: RecordingDispatchContext)
}

/**
 * Map from event-kind wire string to its [RecordingScriptEventHandler]. Built by each
 * [RenderHost.acquireRecordingSession] call so handlers can close over the per-session held
 * scene/rule. The registry shape itself is renderer-agnostic — desktop and Android both build one,
 * registering whichever input + extension kinds the host actually dispatches.
 *
 * Lookup is by `event.kind` (the wire string the agent sent). Kinds with no registered handler
 * yield [unsupportedEvidence] — defense in depth for events the MCP layer didn't filter (older MCP
 * servers, direct daemon clients).
 *
 * [observers] receive every dispatched event before the handler runs — see
 * [RecordingScriptDispatchObserver] for the contract.
 */
class RecordingScriptHandlerRegistry(
  private val handlers: Map<String, RecordingScriptEventHandler>,
  private val observers: List<RecordingScriptDispatchObserver> = emptyList(),
) {

  fun dispatch(
    event: RecordingScriptEvent,
    ctx: RecordingDispatchContext,
  ): RecordingScriptEvidence {
    for (observer in observers) {
      runCatching { observer.beforeDispatch(event, ctx) }
        .onFailure { t ->
          System.err.println(
            "compose-ai-daemon: RecordingScriptDispatchObserver " +
              "${observer.javaClass.name} threw on '${event.kind}': " +
              "${t.javaClass.simpleName}: ${t.message}"
          )
        }
    }
    val handler = handlers[event.kind]
    return if (handler == null) {
      unsupportedEvidence(event, "no recording-script handler registered for kind '${event.kind}'")
    } else {
      handler.apply(event, ctx)
    }
  }

  /** Wire-string keys this registry knows about. Useful for tests + capability derivation. */
  fun knownKinds(): Set<String> = handlers.keys
}

/**
 * Build an [RecordingScriptEvidence] for an applied event. Optional [message] is forwarded as the
 * `message` field for human-readable trace context.
 */
fun appliedEvidence(event: RecordingScriptEvent, message: String? = null): RecordingScriptEvidence =
  RecordingScriptEvidence(
    tMs = event.tMs,
    kind = event.kind,
    status = RecordingScriptEventStatus.APPLIED,
    label = event.label,
    checkpointId = event.checkpointId,
    lifecycleEvent = event.lifecycleEvent,
    tags = event.tags,
    message = message,
  )

/**
 * Build an [RecordingScriptEvidence] for an unsupported event. The [message] is required so the
 * agent always has a concrete reason for the unsupported status (which kind, on which backend, why
 * — e.g. "key dispatch is not implemented for desktop recording").
 *
 * Optional [unsupportedReason] carries a structured cause for `uia.*` dispatches (#874 item #2) —
 * agents that decode it get the matched-count + nearest-match shape and can iterate on selectors
 * without re-rendering. Other event kinds leave it `null`.
 */
fun unsupportedEvidence(
  event: RecordingScriptEvent,
  message: String,
  unsupportedReason: ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReason? = null,
): RecordingScriptEvidence =
  RecordingScriptEvidence(
    tMs = event.tMs,
    kind = event.kind,
    status = RecordingScriptEventStatus.UNSUPPORTED,
    label = event.label,
    checkpointId = event.checkpointId,
    lifecycleEvent = event.lifecycleEvent,
    tags = event.tags,
    message = message,
    unsupportedReason = unsupportedReason,
  )
