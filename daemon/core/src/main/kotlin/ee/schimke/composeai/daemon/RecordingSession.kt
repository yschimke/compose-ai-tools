package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence

/**
 * Held-scene recording session for one `recordingId` — see
 * [RECORDING.md](../../../../../../docs/daemon/RECORDING.md) (planned) and
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
 *
 * Backends that support recording (today: `:daemon:desktop`'s `DesktopRecordingSession`) implement
 * this interface to keep an `ImageComposeScene` (or per-host equivalent) warm for the duration of
 * the recording. The session owns a **virtual frame clock** at a fixed `fps`: dispatched pointer
 * events and scene render ticks all key off the same `nanoTime`, so a script of `(tMs=0, click) +
 * (tMs=500, click)` always produces 500 ms of inter-click animation regardless of how long the
 * agent took to assemble the script. Without virtual time the renderer would tick on wall-clock,
 * stretching agent latency into the recorded video.
 *
 * Lifecycle owned by [JsonRpcServer]:
 * - **Allocate** at `recording/start` via [RenderHost.acquireRecordingSession]. The session holds
 *   one fresh scene; concurrent recordings allocate independent sessions.
 * - **Append** events at `recording/script` — [postScript] sorts and merges into the timeline.
 * - **Drive** at `recording/stop` — [stop] plays the timeline back in virtual time, writing one PNG
 *   per virtual frame, and returns the frame metadata.
 * - **Encode** at `recording/encode` — [encode] assembles the on-disk frames into a single video
 *   file (APNG today; mp4 / webm later).
 * - **Release** at [close] (or daemon shutdown) — frees the scene and any native resources. Frame
 *   PNGs and any encoded video stay on disk so a subsequent `recording/encode` can re-encode
 *   without holding the scene open.
 *
 * **Threading.** Implementations may assume calls are serialised per-instance. JsonRpcServer
 * dispatches recording RPCs on a per-recording worker thread (similar to the per-input worker for
 * interactive sessions) so concurrent recordings on different sessions are independent. Skiko isn't
 * thread-safe so the contract matches the underlying constraint.
 *
 * **No-mid-render-cancellation invariant** ([DESIGN.md §
 * 9](../../../../../../docs/daemon/DESIGN.md)). [close] must drain any in-flight render before
 * tearing down the scene, the same way `RenderHost.shutdown` drains its queue.
 */
interface RecordingSession : AutoCloseable {

  /** The preview id this session is recording. Frozen at allocation time. */
  val previewId: String

  /** Opaque session id assigned by [JsonRpcServer] at `recording/start`. Frozen at allocation. */
  val recordingId: String

  /** Frame rate of the virtual clock, in frames per second. Frozen at allocation. */
  val fps: Int

  /** Output-frame size multiplier (≥ 0). Frozen at allocation. */
  val scale: Float

  /**
   * `true` when this session captures real-time interactions instead of replaying a scripted
   * timeline — see RECORDING.md § "live mode". Frozen at allocation. Implementations spin a
   * background tick thread at [fps] cadence on construction; [postInput] feeds the queue that tick
   * thread drains. [postScript] is rejected; [postInput] is the only legal append.
   *
   * `false` (the default) is the scripted path that's been live since v1: post a full timeline via
   * [postScript], then [stop] plays it back at virtual frame time.
   */
  val live: Boolean

  /**
   * **Scripted mode only.** Append a batch of events to the virtual timeline. May be called
   * multiple times before [stop]; each call merges and re-sorts by `tMs` so out-of-order client
   * batches still play in order. Throws [IllegalStateException] when [live] is `true`.
   */
  fun postScript(events: List<RecordingScriptEvent>)

  /**
   * **Live mode only.** Append one input event to the live tick loop's pending queue. The
   * implementation stamps it with the current virtual `tMs` (= wall-clock elapsed since
   * construction) and dispatches it on the next frame boundary. Throws [IllegalStateException] when
   * [live] is `false` — scripted callers should use [postScript] instead.
   *
   * Fire-and-forget by design: the daemon's `recording/input` notification handler doesn't wait for
   * the tick to consume the event. Inputs that arrive after [stop] are dropped silently.
   */
  fun postInput(input: RecordingInputParams)

  /**
   * Stop the recording and return its frame metadata. Behaviour differs by mode:
   * - **Scripted** ([live] = `false`): plays the posted script back on the virtual clock, writing
   *   one PNG per virtual frame to disk.
   * - **Live** ([live] = `true`): joins the background tick thread, waits for any in-flight frame
   *   write to finish, then returns metadata for the frames already on disk.
   *
   * After [stop] returns, the held scene is closed and further [postScript] / [postInput] calls are
   * illegal — but the PNGs remain on disk so [encode] can be invoked. Idempotent in the limited
   * sense that a second call returns the same [RecordingResult] without re-rendering.
   */
  fun stop(): RecordingResult

  /**
   * Encode the on-disk frames produced by [stop] into a single video file. Must be called after
   * [stop] (the implementation throws otherwise). Idempotent: encoding the same format twice
   * returns the same path; encoding different formats writes side-by-side files.
   */
  fun encode(format: RecordingFormat): EncodedRecording

  /**
   * Drains any in-flight playback, frees the held scene + native resources, and removes any
   * filesystem state owned by the session that survives past final encoding (the per-frame PNGs and
   * encoded video files stay — the agent may want to re-encode later). Idempotent.
   */
  override fun close()
}

/**
 * Metadata returned by [RecordingSession.stop]. The on-disk PNGs at `<framesDir>/frame-NNNNN.png`
 * outlive the session.
 */
data class RecordingResult(
  val frameCount: Int,
  val durationMs: Long,
  val framesDir: String,
  val frameWidthPx: Int,
  val frameHeightPx: Int,
  val scriptEvents: List<RecordingScriptEvidence> = emptyList(),
)

/** Metadata returned by [RecordingSession.encode]. */
data class EncodedRecording(val videoPath: String, val mimeType: String, val sizeBytes: Long)

fun String.toInteractiveInputKindOrNull(): InteractiveInputKind? =
  INTERACTIVE_INPUT_KIND_BY_WIRE_NAME[this]

/**
 * Wire-name table for [InteractiveInputKind]. Single source of truth for the recording-script
 * `kind` strings these enum values map to — used by the live-mode tick loops' `wireName()`
 * synthesis and the registry's per-kind handler lookup.
 *
 * Naming follows the per-extension `<extension>.<event>` convention: `input.click`,
 * `input.pointerDown`, etc. Three input extensions advertise these ids — see
 * `InputTouchRecordingScriptEvents`, `InputKeyboardRecordingScriptEvents`, and
 * `InputRsbRecordingScriptEvents`. The MCP `validateRecordingScriptKinds` checks every kind
 * against the daemon's advertised set — no special-case branch for input kinds anymore.
 */
val INTERACTIVE_INPUT_KIND_BY_WIRE_NAME: Map<String, InteractiveInputKind> =
  mapOf(
    "input.click" to InteractiveInputKind.CLICK,
    "input.pointerDown" to InteractiveInputKind.POINTER_DOWN,
    "input.pointerMove" to InteractiveInputKind.POINTER_MOVE,
    "input.pointerUp" to InteractiveInputKind.POINTER_UP,
    "input.rotaryScroll" to InteractiveInputKind.ROTARY_SCROLL,
    "input.keyDown" to InteractiveInputKind.KEY_DOWN,
    "input.keyUp" to InteractiveInputKind.KEY_UP,
  )

/**
 * Reverse lookup — typed [InteractiveInputKind] back to its wire-name string. Used by live-mode
 * recording sessions to translate a typed `RecordingInputParams` into a synthetic
 * [RecordingScriptEvent] for dispatch through the same [RecordingScriptHandlerRegistry] scripted
 * playback uses. Derived from [INTERACTIVE_INPUT_KIND_BY_WIRE_NAME] so the round-trip stays in
 * lockstep — adding a new enum constant + wire name extends both directions automatically.
 */
private val INTERACTIVE_INPUT_KIND_TO_WIRE_NAME: Map<InteractiveInputKind, String> =
  INTERACTIVE_INPUT_KIND_BY_WIRE_NAME.entries.associate { (wire, kind) -> kind to wire }

/**
 * Wire-name string for an [InteractiveInputKind] (the reverse of [toInteractiveInputKindOrNull]).
 */
fun InteractiveInputKind.wireName(): String = INTERACTIVE_INPUT_KIND_TO_WIRE_NAME.getValue(this)
