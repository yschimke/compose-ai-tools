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
 * function (a stub before B-desktop.1.4, [RenderEngine.render] from B-desktop.1.4 onwards) and
 * posts the result onto the matching per-id queue in [results].
 *
 * **Where the warm Compose runtime lives.** On desktop the canonical render primitive is
 * `ImageComposeScene` (see `:renderer-desktop`'s `DesktopRendererMain` and the duplicated body in
 * [RenderEngine]). The scene is constructed and disposed *per render* — the runtime amortisation
 * the daemon delivers is at the JVM + JIT + Skiko-native-bundle level, not the scene level.
 * B-desktop.1.4 deliberately doesn't hold one scene across renders; doing so would require tearing
 * down the previous content tree between previews, which is roughly the same wall-clock as just
 * constructing a fresh scene against the warm Compose/Skiko native code.
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
 * - [RenderEngine] takes the same invariant inwards: every `ImageComposeScene` is closed in a
 *   `try/finally`, even if the render body throws.
 *
 * **Payload format.** `RenderRequest.Render.payload` is parsed via [RenderSpec.parseFromPayload]: a
 * `;`-delimited `key=value` string carrying at minimum `className=...` and `functionName=...`. When
 * [RenderRequest] grows a typed `previewId: String?` field, [DesktopHost] will look the spec up in
 * `previews.json` instead. Until then, callers — `JsonRpcServer` (forwarding from the
 * `renderNow.previews[i]` ID), the harness's `HarnessClient`, and direct unit tests — encode the
 * spec into `payload`. A blank or non-spec payload falls back to a deterministic stub render
 * ([renderStubFallback]) so the legacy [DesktopHostTest] (which submits `payload="render-N"`) keeps
 * working through the B-desktop.1.4 transition.
 */
open class DesktopHost(
  /**
   * The render engine bound to this host. Visible as a constructor parameter so tests can swap in a
   * stub or a fixture-pinned variant; production code uses the default zero-arg [RenderEngine]
   * which honours the `composeai.render.outputDir` system property.
   */
  private val engine: RenderEngine = RenderEngine()
) : RenderHost {

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
          val result =
            try {
              dispatchRender(request)
            } catch (t: Throwable) {
              // Surface to stderr so the failure is observable in CI logs; still post a result so
              // the caller's poll() doesn't time out. The result encodes "no PNG, no metrics" via
              // null fields — JsonRpcServer will treat that as the stub-path placeholder, and the
              // caller can decide whether to fail the test from there.
              System.err.println(
                "DesktopHost: render dispatch failed for id=${request.id} " +
                  "(payload='${request.payload}'): ${t.message}"
              )
              t.printStackTrace(System.err)
              renderStubFallback(request.id)
            }
          results.computeIfAbsent(request.id) { LinkedBlockingQueue() }.put(result)
        }
      }
    }
  }

  /**
   * Dispatches a render to [engine], or to [renderStubFallback] when the request payload is empty
   * or doesn't look like a spec.
   *
   * The non-spec escape hatch keeps the B-desktop.1.3 [DesktopHostTest] (which submits
   * `payload="render-N"` strings) working through the B-desktop.1.4 transition — it doesn't carry a
   * `className=`/`functionName=` pair, so we recognise it as "no spec; just verify the queue
   * plumbing" and fall back to the classloader-stamped result. Real callers (JsonRpcServer + the
   * harness) always encode a parseable payload.
   */
  private fun dispatchRender(request: RenderRequest.Render): RenderResult {
    val parseable = request.payload.contains("className=")
    return if (!parseable) {
      renderStubFallback(request.id)
    } else {
      val spec = RenderSpec.parseFromPayload(request.payload)
      engine.render(spec, request.id)
    }
  }

  /**
   * Fallback render for non-spec payloads — returns a [RenderResult] capturing the render-thread
   * classloader identity. Used by [DesktopHostTest]'s 10-render reuse assertion (which submits
   * payloads of the form `render-N`) and as the catch-all when the real engine throws.
   */
  private fun renderStubFallback(id: Long): RenderResult {
    val cl = Thread.currentThread().contextClassLoader ?: DesktopHost::class.java.classLoader
    return RenderResult(
      id = id,
      classLoaderHashCode = System.identityHashCode(cl),
      classLoaderName = cl?.javaClass?.name ?: "<null>",
    )
  }
}
