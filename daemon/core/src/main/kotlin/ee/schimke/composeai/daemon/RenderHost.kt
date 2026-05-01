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
