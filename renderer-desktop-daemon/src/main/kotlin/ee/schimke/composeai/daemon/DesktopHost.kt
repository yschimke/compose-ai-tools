package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Compose-Desktop-backed [RenderHost]. Holds a single warm render thread + queue open for the
 * lifetime of the daemon — desktop counterpart of [RobolectricHost][ee.schimke.composeai.daemon] in
 * `:renderer-android-daemon`.
 *
 * Pattern: [start] starts a single render thread that polls [requests] for a [RenderRequest.Render]
 * (or [RenderRequest.Shutdown] poison pill); for every render request it hands control to a render
 * function (a stub for B-desktop.1.3, the real engine for B-desktop.1.4) and posts the result onto
 * the matching per-id queue in [results].
 *
 * **Where the warm Compose runtime lives.** On desktop the canonical render primitive is
 * `ImageComposeScene` (see `:renderer-desktop`'s `DesktopRendererMain`), which manages its own
 * `Recomposer` + Skiko `Surface` internally. B-desktop.1.4 decides whether to instantiate one
 * `ImageComposeScene` per render or hold one open across renders; either way it lives behind
 * [renderStub] / its successor and runs on this host's render thread. For B-desktop.1.3 the "warm
 * runtime" is just the JVM + render thread + queue infrastructure — the Compose runtime is a no-op
 * until B-desktop.1.4 lands. The 10-render reuse test still proves the thread-and-classloader
 * invariance that the warm-runtime case will inherit.
 *
 * **Why much simpler than Android.** No Robolectric `InstrumentingClassLoader`, no dummy-`@Test`
 * runner trick, no `bridge` package. Compose Desktop runs in plain JVM classloaders, so the
 * sandbox-vs-host classloader bookkeeping that dominates [RobolectricHost] is irrelevant here. The
 * "sandbox reuse" assertion still holds — every render observes the same JVM app classloader — and
 * exists so that a future regression that accidentally spawns a new thread per submission is
 * caught.
 *
 * **No-mid-render-cancellation invariant** (DESIGN.md § 9, PREDICTIVE.md § 9):
 * - The render thread does NOT poll [Thread.interrupted]; the daemon never calls [Thread.interrupt]
 *   on it.
 * - [shutdown] is a poison pill on [requests], not a thread abort. The in-flight render finishes
 *   before the host returns control to the caller.
 * - The only `Thread.currentThread().interrupt()` calls in this file are the standard "restore
 *   interrupt status after a caught [InterruptedException]" pattern on the *current* thread — never
 *   on the render thread from outside.
 *
 * For B-desktop.1.3 the render body is intentionally a stub — it does not touch `ImageComposeScene`
 * or `setContent`. B-desktop.1.4 (separate task) duplicates the real render body in here; this task
 * only proves the queue + worker-thread plumbing actually works and that submissions reuse the same
 * render thread.
 */
open class DesktopHost : RenderHost {

  private val requests: LinkedBlockingQueue<RenderRequest> = LinkedBlockingQueue()
  private val results: ConcurrentHashMap<Long, LinkedBlockingQueue<Any>> = ConcurrentHashMap()

  /**
   * Set if any [InterruptedException] is observed on the render thread. Production code never
   * causes one (we hold the no-mid-render-cancellation invariant); the test asserts this stays
   * `false` after a clean shutdown to detect a future regression that introduces a stray
   * `interrupt()`.
   */
  @Volatile
  var renderThreadInterrupted: Boolean = false
    private set

  private val renderThread =
    Thread({ runRenderLoop() }, "compose-ai-daemon-host").apply { isDaemon = false }

  /**
   * Starts the render worker thread. After this call the worker is alive and waiting for requests
   * on [requests]; submissions land in stub-render time. No multi-second cold-start cost here —
   * that's the desktop-vs-Android win.
   */
  override fun start() {
    renderThread.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    requests.put(typed)
    val resultQueue = results.computeIfAbsent(typed.id) { LinkedBlockingQueue() }
    val raw =
      resultQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: error("DesktopHost.submit($typed) timed out after ${timeoutMs}ms")
    results.remove(typed.id)
    return raw as RenderResult
  }

  /**
   * Sends the poison pill, drains the in-flight render (DESIGN § 9 invariant: never aborts a render
   * mid-flight), waits up to [timeoutMs] for the worker thread to exit. Idempotent.
   */
  override fun shutdown(timeoutMs: Long) {
    if (renderThread.state == Thread.State.NEW) return
    if (!renderThread.isAlive) return

    requests.put(RenderRequest.Shutdown)
    renderThread.join(timeoutMs)
    if (renderThread.isAlive) {
      error("DesktopHost worker did not exit within ${timeoutMs}ms after shutdown")
    }
  }

  private fun runRenderLoop() {
    while (true) {
      val request: RenderRequest =
        try {
          requests.take()
        } catch (e: InterruptedException) {
          // Should never happen — daemon code never interrupts this thread (DESIGN § 9). If it
          // does, record the violation, restore the flag on the *current* thread (standard
          // pattern), and bail cleanly so the test can observe it.
          renderThreadInterrupted = true
          Thread.currentThread().interrupt()
          return
        }
      when (request) {
        is RenderRequest.Shutdown -> return
        is RenderRequest.Render -> {
          val result = renderStub(request.id)
          results.computeIfAbsent(request.id) { LinkedBlockingQueue() }.put(result)
        }
      }
    }
  }

  /**
   * Stub render for B-desktop.1.3 — returns a [RenderResult] capturing the render-thread
   * classloader identity so the test can verify reuse across many submissions. B-desktop.1.4
   * replaces the body of this function with the real Compose / `ImageComposeScene` render path.
   *
   * The 1ms sleep keeps the latency shape recognisable as "did some work" without inflating test
   * wall-clock; mirrors the placeholder shape `RobolectricHost.renderStub` adopted before B1.4
   * landed.
   */
  private fun renderStub(id: Long): RenderResult {
    Thread.sleep(1)
    val cl = Thread.currentThread().contextClassLoader ?: DesktopHost::class.java.classLoader
    return RenderResult(
      id = id,
      classLoaderHashCode = System.identityHashCode(cl),
      classLoaderName = cl?.javaClass?.name ?: "<null>",
    )
  }
}
