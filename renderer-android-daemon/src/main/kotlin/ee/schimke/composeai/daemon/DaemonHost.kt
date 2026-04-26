package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Holds a single Robolectric sandbox open for the lifetime of the daemon — see
 * docs/daemon/DESIGN.md § 9 ("Bootstrap").
 *
 * Pattern: [start] launches a worker thread that runs JUnit against the
 * [SandboxRunner] inner class. [SandboxHoldingRunner] (a custom
 * `RobolectricTestRunner`) bootstraps a sandbox, calls
 * [SandboxRunner.holdSandboxOpen] (the dummy `@Test`), and tears down only
 * when that test method returns. Inside the test method we drain
 * [DaemonHostBridge.requests] until the [DaemonHostBridge.shutdown] flag is
 * set; for every request we hand control to a render function (a stub for
 * B1.3, the real engine for B1.4) and post the result to the matching
 * per-id queue in [DaemonHostBridge.results].
 *
 * **Cross-classloader handoff** lives in the dedicated
 * [ee.schimke.composeai.daemon.bridge] package, which
 * [SandboxHoldingRunner] excludes from Robolectric instrumentation. That is
 * the **load-bearing** detail: without the do-not-acquire rule, Robolectric
 * re-loads `ee.schimke.composeai.daemon.*` classes inside the sandbox and
 * the test-thread-side queue is *not* the same instance as the
 * sandbox-side queue. See [DaemonHostBridge] for the long form.
 *
 * **Sandbox reuse verification** lives in `DaemonHostTest`: submit N
 * requests through one host and assert that the recorded sandbox
 * `contextClassLoader` identity is the same for every render. That is the
 * load-bearing invariant for the daemon's whole value proposition
 * (DESIGN § 2 verdict on feasibility).
 *
 * For B1.3 the render body is intentionally a stub — it does not touch
 * Compose, Roborazzi, or `setContent`. B1.4 (separate task) duplicates the
 * real render body in here; this task only proves that the dummy-`@Test`
 * holding-the-sandbox-open pattern actually works. Per TODO.md "Risks to
 * track", if this pattern fails for any reason we escalate rather than
 * silently switching to Robolectric's lower-level `Sandbox` API.
 */
class DaemonHost {

  private val workerThread =
    Thread({ runJUnit() }, "compose-ai-daemon-host").apply { isDaemon = false }

  /**
   * Starts the host thread. After this call the worker thread is alive and
   * waiting for requests on [DaemonHostBridge.requests]. The first [submit]
   * still blocks until the Robolectric sandbox is fully bootstrapped
   * (~5–15s on a typical dev machine), but subsequent submits hit a hot
   * sandbox and return in stub-render time.
   */
  fun start() {
    DaemonHostBridge.reset()
    workerThread.start()
  }

  /**
   * Submits one request and blocks until its [RenderResult] is available.
   *
   * @param timeoutMs upper bound on the wait; defaults to 60s which is
   *   generous for the first call (sandbox cold-boot dominates) and still
   *   well under any reasonable "sandbox failed to bootstrap" timeout.
   */
  fun submit(request: RenderRequest, timeoutMs: Long = 60_000): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    DaemonHostBridge.requests.put(typed)
    val resultQueue = DaemonHostBridge.results.computeIfAbsent(typed.id) { LinkedBlockingQueue() }
    val raw =
      resultQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: error("DaemonHost.submit($typed) timed out after ${timeoutMs}ms")
    DaemonHostBridge.results.remove(typed.id)
    // Result instances are constructed inside the sandbox classloader; copy
    // their fields out via reflection so the host-side caller gets an
    // instance of the host-side RenderResult class.
    return raw as? RenderResult ?: copyResultAcrossClassloaders(raw)
  }

  private fun copyResultAcrossClassloaders(raw: Any): RenderResult {
    val cls = raw.javaClass
    val id = cls.getMethod("getId").invoke(raw) as Long
    val hash = cls.getMethod("getClassLoaderHashCode").invoke(raw) as Int
    val name = cls.getMethod("getClassLoaderName").invoke(raw) as String
    return RenderResult(id = id, classLoaderHashCode = hash, classLoaderName = name)
  }

  /**
   * Sends the poison pill, waits up to [timeoutMs] for the worker thread to
   * exit. Idempotent.
   */
  fun shutdown(timeoutMs: Long = 30_000) {
    DaemonHostBridge.shutdown.set(true)
    // Belt-and-braces: also enqueue a Shutdown so the worker wakes from
    // poll() promptly rather than waiting out the 100ms cycle.
    DaemonHostBridge.requests.put(RenderRequest.Shutdown)
    workerThread.join(timeoutMs)
    if (workerThread.isAlive) {
      error("DaemonHost worker did not exit within ${timeoutMs}ms after shutdown")
    }
  }

  private fun runJUnit() {
    val result = JUnitCore.runClasses(SandboxRunner::class.java)
    if (!result.wasSuccessful()) {
      // Surface to stderr; the caller's shutdown() join will time out and
      // explicit logging helps diagnostics.
      for (failure in result.failures) {
        System.err.println("DaemonHost SandboxRunner failed: ${failure.message}")
        failure.exception?.printStackTrace()
      }
    }
  }

  /**
   * The dummy test class. Loaded by Robolectric's `InstrumentingClassLoader`
   * once `@RunWith` triggers sandbox bootstrap. Its single `@Test` method
   * holds the sandbox open until it returns.
   *
   * `@Config(sdk = [35])` matches the SDK pinned in renderer-android's
   * generated `robolectric.properties`. We declare it here directly because
   * the daemon module doesn't generate that file (the consumer module does
   * for the existing JUnit path).
   */
  @RunWith(SandboxHoldingRunner::class)
  @Config(sdk = [35])
  class SandboxRunner {

    @Test
    fun holdSandboxOpen() {
      while (!DaemonHostBridge.shutdown.get()) {
        val request =
          DaemonHostBridge.requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
        // Match by simple class name rather than `is` so a future
        // classloader-rule change reintroducing instrumentation of
        // `RenderRequest` is observable as a clean failure rather than a
        // silent skip.
        when (request.javaClass.simpleName) {
          "Shutdown" -> return
          "Render" -> {
            val id = request.javaClass.getMethod("getId").invoke(request) as Long
            val result = renderStub(id)
            DaemonHostBridge.results
              .computeIfAbsent(id) { LinkedBlockingQueue() }
              .put(result)
          }
          else -> error("unknown RenderRequest subtype: ${request.javaClass.name}")
        }
      }
    }

    /**
     * Stub render for B1.3 — returns a [RenderResult] capturing the
     * sandbox classloader identity so the test can verify reuse across
     * many submissions. B1.4 replaces the body of this function with the
     * real Compose/Robolectric render path.
     */
    private fun renderStub(id: Long): RenderResult {
      val cl = Thread.currentThread().contextClassLoader
      return RenderResult(
        id = id,
        classLoaderHashCode = System.identityHashCode(cl),
        classLoaderName = cl?.javaClass?.name ?: "<null>",
      )
    }
  }

  companion object {
    /** Monotonic ID source; callers may use it to label requests. */
    fun nextRequestId(): Long = DaemonHostBridge.nextId.getAndIncrement()
  }
}

/** Request envelope. [Shutdown] is the poison pill; everything else is a [Render]. */
sealed interface RenderRequest {

  data class Render(
    val id: Long = DaemonHost.nextRequestId(),
    /**
     * Free-form payload the stub doesn't read. B1.4 will replace this with
     * the typed `PreviewInfo` / output-dir tuple.
     */
    val payload: String = "",
  ) : RenderRequest

  /** Singleton poison pill. */
  data object Shutdown : RenderRequest
}

/**
 * Result of a single render. For B1.3 the body is a stub; the only field
 * that matters is [classLoaderHashCode], which `DaemonHostTest` uses to
 * verify the sandbox classloader is reused across submissions.
 *
 * Constructed inside the sandbox; copied across the classloader boundary
 * by [DaemonHost.submit] before being handed to the caller.
 */
data class RenderResult(
  val id: Long,
  val classLoaderHashCode: Int,
  val classLoaderName: String,
)
