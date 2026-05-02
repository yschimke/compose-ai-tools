package ee.schimke.composeai.daemon

import java.util.concurrent.atomic.AtomicLong

/**
 * Renderer-agnostic seam between [JsonRpcServer] and the per-target render backend — see
 * docs/daemon/DESIGN.md § 4 ("Renderer-agnostic surface").
 *
 * One implementation per backend:
 *
 * - `RobolectricHost` (in `:daemon:android`) holds a Robolectric sandbox open via the dummy-`@Test`
 *   runner trick (DESIGN.md § 9) and bridges work across the sandbox classloader boundary.
 * - `DesktopHost` (planned, in `:daemon:desktop`, Stream B-desktop) holds a long-lived
 *   `Recomposer` + Skiko `Surface` warm.
 *
 * The surface is intentionally minimal: only the methods [JsonRpcServer] actually invokes ([start],
 * [submit], [shutdown]) plus the shared monotonic id source via [Companion.nextRequestId]. New
 * methods only appear here when `JsonRpcServer` needs them on every backend; per-backend extras
 * stay on the concrete class.
 *
 * **Threading contract.** Implementations expose a single render thread — see the
 * no-mid-render-cancellation invariant in DESIGN.md § 9. [submit] blocks the caller until the host
 * returns a [RenderResult]; [JsonRpcServer] already runs each `submit` on a fire-and-forget worker
 * so the JSON-RPC read loop is never blocked.
 */
interface RenderHost {

  /**
   * Lifecycle: must be called once before the first [submit]. After this returns the host is alive
   * and ready (though the first [submit] may still pay a cold-start cost, e.g. Robolectric sandbox
   * bootstrap).
   */
  fun start()

  /**
   * Submits one render request and blocks until its [RenderResult] is available, or until
   * [timeoutMs] elapses (in which case the implementation throws — typically
   * `IllegalStateException`).
   *
   * @param request must be a [RenderRequest.Render]; the [RenderRequest.Shutdown] poison pill is
   *   implementation-internal and not legal here.
   */
  fun submit(request: RenderRequest, timeoutMs: Long = 60_000): RenderResult

  /**
   * Drains in-flight renders cleanly, then stops the render thread. Never aborts a render
   * mid-flight (DESIGN.md § 9 invariant). Idempotent.
   *
   * @param timeoutMs upper bound for the worker thread to exit after the poison pill is enqueued.
   */
  fun shutdown(timeoutMs: Long = 30_000)

  /**
   * The disposable user-class [UserClassLoaderHolder] this host renders against (B2.0 — see
   * [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)). The host's render path reads
   * `currentChildLoader()` to resolve preview classes via `Class.forName`.
   *
   * Returns `null` when the host doesn't participate in the parent/child split (the harness's
   * `FakeHost`, the Stream A B1.3 stub-render hosts, etc.) — those hosts don't load user classes,
   * so the swap is a no-op and the existing v1 fake-mode scenarios stay unchanged. Real backends
   * (`DesktopHost`, `RobolectricHost`) override.
   *
   * **Sandbox pool note (SANDBOX-POOL.md).** Under multi-sandbox mode `RobolectricHost` carries one
   * holder per slot rather than a single shared instance. This property still returns one
   * representative holder (slot 0) so callers that only need "is this host classloader-aware?" keep
   * working; callers that mutate state should use [swapUserClassLoaders] for the broadcast.
   */
  val userClassloaderHolder: UserClassLoaderHolder?
    get() = null

  /**
   * Swap (drop and lazily re-allocate on next read) every user-class child classloader this host
   * holds. SANDBOX-POOL.md (per-slot child loaders): a host with `sandboxCount > 1` broadcasts to
   * every slot's holder so all slots see the recompiled bytecode on their next render.
   *
   * Default no-op for hosts that don't participate in the parent/child split.
   * [JsonRpcServer.handleFileChanged] calls this on `kind: "source"` instead of dereferencing
   * [userClassloaderHolder]?.swap() directly so the broadcast is the same call site for both
   * single-sandbox and pool modes.
   */
  fun swapUserClassLoaders() {
    userClassloaderHolder?.swap()
  }

  /**
   * `true` when this host's [acquireInteractiveSession] returns a real held-scene session that
   * dispatches `interactive/input` into the composition (v2). `false` (the default) when the host
   * inherits the throwing default and `interactive/input` falls back to v1 (re-render trigger,
   * input does not reach the composition). Surfaced verbatim as
   * `InitializeResult.capabilities.interactive` so clients can render an "unsupported host" hint
   * without a per-call probe.
   *
   * Implementations MUST keep this in sync with their [acquireInteractiveSession] override —
   * advertising `true` while throwing is a contract violation.
   */
  val supportsInteractive: Boolean
    get() = false

  /**
   * Field names from `PreviewOverrides` (see PROTOCOL.md § 5 `renderNow.overrides`) that this host
   * actually applies during a render. Names match the JSON spelling on the wire: `widthPx`,
   * `heightPx`, `density`, `localeTag`, `fontScale`, `uiMode`, `orientation`, `device`. Surfaced
   * verbatim as `InitializeResult.capabilities.supportedOverrides` so clients can grey out
   * unsupported sliders and MCP can warn agents who set fields the backend would silently ignore.
   *
   * The default empty set is the safe pre-feature value — clients treat absent and `[]` identically
   * and assume any field they pass might be ignored. Real backends override: `RobolectricHost`
   * advertises all eight; `DesktopHost` omits `localeTag` (no `LocalLocale` CompositionLocal +
   * `Locale.setDefault` is JVM-thread-unsafe — see `daemon/desktop/.../RenderEngine.kt`) and
   * `orientation` (no rotation concept on `ImageComposeScene`).
   */
  val supportedOverrides: Set<String>
    get() = emptySet()

  /**
   * Identifier for the renderer backend this host implements. Surfaced verbatim as
   * `InitializeResult.capabilities.backend` so clients can render backend-specific UI hints (e.g.
   * "Wear preview unsupported on desktop") without per-call probing. `null` (the default) for hosts
   * that haven't been classified — `FakeHost` in `:daemon:harness`, the in-test `FakeRenderHost`,
   * etc. Real backends override: `RobolectricHost` returns `ANDROID`, `DesktopHost` returns
   * `DESKTOP`.
   */
  val backendKind: ee.schimke.composeai.daemon.protocol.BackendKind?
    get() = null

  /**
   * Fixed Android SDK level this host renders against. Android/Robolectric backends expose the
   * `@Config(sdk = ...)` value so clients can reason about backend compatibility without scraping
   * daemon logs. Non-Android backends return `null`.
   */
  val androidSdk: Int?
    get() = null

  /**
   * Allocate an [InteractiveSession] for [previewId] — the v2 click-into-composition surface
   * documented in
   * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
   *
   * Hosts that support interactive mode (today: `:daemon:desktop`'s `DesktopHost`) override and
   * return a session holding a warm `ImageComposeScene` (or per-host equivalent) so `remember`'d
   * state survives across `interactive/input` notifications. Such hosts MUST also override
   * [supportsInteractive] to return `true`.
   *
   * The default body throws [UnsupportedOperationException] — which
   * [JsonRpcServer.handleInteractiveStart] translates to `MethodNotFound (-32601)` on the wire. v1
   * panels handle that by falling back to the legacy `setFocus + renderNow` path; v2 panels surface
   * a status-bar hint. The default keeps every existing host (`FakeHost` in `:daemon:harness`,
   * `RobolectricHost` in `:daemon:android`, the in-test
   * [JsonRpcServerIntegrationTest.FakeRenderHost]) on the v1 behaviour without any code change.
   *
   * @param classLoader the disposable child loader from [UserClassLoaderHolder.currentChildLoader]
   *   (B2.0 — see [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)). The session
   *   resolves the preview's class against this loader so a recompile during the session's lifetime
   *   doesn't drag stale bytecode into the held scene — the next `interactive/start` after a save
   *   gets a fresh loader.
   */
  fun acquireInteractiveSession(previewId: String, classLoader: ClassLoader): InteractiveSession =
    throw UnsupportedOperationException(
      "interactive mode unsupported by ${this::class.simpleName ?: this::class.java.name}"
    )

  /**
   * `true` when this host's [acquireRecordingSession] returns a real held-scene session that drives
   * a virtual frame clock and writes per-frame PNGs. `false` (the default) when the host inherits
   * the throwing default and `recording/start` is rejected with `MethodNotFound (-32601)`. Surfaced
   * verbatim as `InitializeResult.capabilities.recording`.
   *
   * Implementations MUST keep this in sync with their [acquireRecordingSession] override.
   */
  val supportsRecording: Boolean
    get() = false

  /**
   * Encoded video formats this host can produce — surfaced verbatim as
   * `InitializeResult.capabilities.recordingFormats` (wire spellings from
   * [ee.schimke.composeai.daemon.protocol.RecordingFormat]). Implementations that override
   * [supportsRecording] to `true` MUST include `"apng"` (pure-JVM, always available); MP4 / WEBM
   * appear only when the host has detected an `ffmpeg` binary at construction time.
   *
   * Default empty list keeps pre-feature hosts (FakeHost, RobolectricHost today) consistent with
   * `supportsRecording = false` — clients see "no formats" and don't offer the toggle.
   */
  val supportedRecordingFormats: List<String>
    get() = emptyList()

  /**
   * Allocate a [RecordingSession] for [previewId] — the scripted screen-record surface. The session
   * holds a warm `ImageComposeScene` (or per-host equivalent) for the duration of the recording so
   * `remember`'d state and animation timing are continuous across the virtual timeline.
   *
   * Hosts that support recording (today: `:daemon:desktop`'s `DesktopHost`) override and return a
   * concrete session. The default body throws [UnsupportedOperationException] which
   * [JsonRpcServer.handleRecordingStart] translates to `MethodNotFound (-32601)` on the wire.
   *
   * @param recordingId opaque session id assigned by [JsonRpcServer]; passed back on every
   *   `recording/script` / `recording/stop` / `recording/encode` so the daemon can route to the
   *   right held session.
   * @param classLoader the disposable child loader from [UserClassLoaderHolder.currentChildLoader]
   *   (B2.0 — see [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)).
   * @param fps frames per second at the virtual clock. Caller-validated to be in `[1, 120]`.
   * @param scale output-frame size multiplier. Caller-validated to be in `(0, 8]`. Coordinates stay
   *   in image-natural pixel space — the host scales the captured surface at encode time, not at
   *   composition time.
   * @param overrides per-render display overrides applied to the held scene; same shape and
   *   semantics as `renderNow.overrides`. Lets a `Button`-sized component preview be recorded at
   *   its natural size with a custom background.
   * @param live when `true`, the session runs in live (real-time) mode — a background tick thread
   *   captures frames at [fps] cadence using a wall-clock-driven virtual nanoTime, and
   *   `recording/input` notifications drive the held scene as they arrive. When `false` (the
   *   default), the session is scripted: callers post a full timeline via
   *   [RecordingSession.postScript] and [RecordingSession.stop] plays it back. See RECORDING.md §
   *   "live mode".
   */
  fun acquireRecordingSession(
    previewId: String,
    recordingId: String,
    classLoader: ClassLoader,
    fps: Int,
    scale: Float,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
    live: Boolean = false,
  ): RecordingSession =
    throw UnsupportedOperationException(
      "recording unsupported by ${this::class.simpleName ?: this::class.java.name}"
    )

  companion object {
    /**
     * Monotonic id source shared across [JsonRpcServer] (which assigns ids to incoming render
     * requests) and any host-side bookkeeping. Stays monotonic across host restarts within a single
     * JVM so log correlation remains unambiguous.
     */
    private val nextId: AtomicLong = AtomicLong(1)

    fun nextRequestId(): Long = nextId.getAndIncrement()
  }
}

/** Request envelope. [Shutdown] is the poison pill; everything else is a [Render]. */
sealed interface RenderRequest {

  data class Render(
    val id: Long = RenderHost.nextRequestId(),
    /**
     * Free-form payload the stub host doesn't read. Real backends will replace this with a typed
     * `PreviewInfo` / output-dir tuple in subsequent tasks.
     */
    val payload: String = "",
  ) : RenderRequest

  /** Singleton poison pill. */
  data object Shutdown : RenderRequest
}

/**
 * Result of a single render. Backend-agnostic shape — fields are protocol concerns (id, classloader
 * identity for diagnostics), not Robolectric- or Compose-specific.
 *
 * The `classLoaderHashCode`/`classLoaderName` pair lets host-internal tests verify that long-lived
 * backends genuinely reuse a single sandbox / classloader across renders (DESIGN.md § 9 — the
 * load-bearing daemon invariant).
 *
 * `pngPath` and `metrics` are populated by hosts that actually render bytes (`FakeHost` in
 * `:daemon:harness`, `DesktopHost`/`RobolectricHost` once their B1.4 render-engine bodies land).
 * They map directly onto the [`renderFinished`](../../docs/daemon/PROTOCOL.md#renderfinished) wire
 * shape: `pngPath` becomes `renderFinished.pngPath`; `metrics` becomes a flat
 * `renderFinished.metrics` (numeric counters; the structured `RenderMetrics` shape is filled in by
 * B2.3 once the daemon tracks heap / sandbox-age etc.). Both default to `null` so the B1.5-era stub
 * paths in `JsonRpcServer.renderFinishedFromResult` keep emitting the placeholder
 * `daemon-stub-${id}.png`.
 */
data class RenderResult(
  val id: Long,
  val classLoaderHashCode: Int,
  val classLoaderName: String,
  val pngPath: String? = null,
  val metrics: Map<String, Long>? = null,
)
