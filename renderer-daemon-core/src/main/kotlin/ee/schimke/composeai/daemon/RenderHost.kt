package ee.schimke.composeai.daemon

import java.util.concurrent.atomic.AtomicLong

/**
 * Renderer-agnostic seam between [JsonRpcServer] and the per-target render backend — see
 * docs/daemon/DESIGN.md § 4 ("Renderer-agnostic surface").
 *
 * One implementation per backend:
 *
 * - `RobolectricHost` (in `:renderer-android-daemon`) holds a Robolectric sandbox open via the
 *   dummy-`@Test` runner trick (DESIGN.md § 9) and bridges work across the sandbox classloader
 *   boundary.
 * - `DesktopHost` (planned, in `:renderer-desktop-daemon`, Stream B-desktop) holds a long-lived
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
 */
data class RenderResult(val id: Long, val classLoaderHashCode: Int, val classLoaderName: String)
