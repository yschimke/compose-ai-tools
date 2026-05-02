package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Compose-Desktop-backed [RenderHost]. Holds a single warm render thread + queue open for the
 * lifetime of the daemon — desktop counterpart of [RobolectricHost][ee.schimke.composeai.daemon] in
 * `:daemon:android`.
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
 *
 * **Render-body exceptions propagate** — when [RenderEngine.render] throws (e.g. `BoomComposable`'s
 * `error("boom")` inside the composition), the loop posts the Throwable onto the per-id result
 * queue and [submit] re-throws on the caller's thread. The Throwable then surfaces upstream as a
 * `renderFailed` notification (see `JsonRpcServer.submitRenderAsync`'s Throwable catch). Earlier
 * versions caught the exception on the render thread and returned a misleading "successful" stub
 * render — the bug `S5RenderFailedRealModeTest` was written to pin and is now closed.
 */
open class DesktopHost(
  /**
   * The render engine bound to this host. Visible as a constructor parameter so tests can swap in a
   * stub or a fixture-pinned variant; production code uses the default zero-arg [RenderEngine]
   * which honours the `composeai.render.outputDir` system property.
   */
  private val engine: RenderEngine = RenderEngine(),
  /**
   * Disposable user-class loader holder (B2.0 — see
   * [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)). When non-null, every
   * [RenderEngine.render] dispatch resolves preview classes via the holder's
   * [UserClassLoaderHolder.currentChildLoader] rather than the host's own classloader; the
   * `JsonRpcServer.handleFileChanged` path swaps it on `kind: "source"` so the next render reads
   * recompiled bytecode.
   *
   * `null` keeps the legacy "user classes are on the host classpath" behaviour, preserving existing
   * unit tests that rely on `RedSquare` / `BlueSquare` being loaded by the host classloader
   * (testFixtures live on `java.class.path`).
   */
  override val userClassloaderHolder: UserClassLoaderHolder? = null,
  /**
   * v2 — resolves a `previewId` (the wire-side string the panel passes in `interactive/start`) to a
   * concrete [RenderSpec] for the held interactive scene. Without a resolver,
   * [acquireInteractiveSession] throws `UnsupportedOperationException` and `JsonRpcServer` falls
   * back to the v1 stateless dispatch path; the panel still works, clicks just don't mutate
   * composition state.
   *
   * `null` (the default) keeps every existing test's behaviour — the host advertises no interactive
   * support. Production wiring in [DaemonMain] passes a resolver backed by [PreviewIndex], with
   * sensible 320x320 / density 2.0 defaults for fields the index doesn't carry. See
   * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
   */
  private val previewSpecResolver: ((String) -> RenderSpec?)? = null,
  /**
   * D5 — interactive-session lifecycle listener for the `compose/recomposition` producer
   * ([RecompositionDataProductRegistry]). Called with the held
   * [androidx.compose.ui.ImageComposeScene] after [acquireInteractiveSession] succeeds, and with
   * `null` when the session is closed (either via the returned session's `close()` or via daemon
   * shutdown).
   *
   * Decoupled from the registry interface itself because (a) the held-scene contract is desktop-
   * only — no Android equivalent exists today — and (b) the renderer-agnostic [DataProductRegistry]
   * surface intentionally doesn't expose [androidx.compose.ui.ImageComposeScene]. The listener seam
   * stays in the desktop module.
   */
  private val interactiveSessionListener: InteractiveSessionListener? = null,
) : RenderHost {

  /**
   * D5 — session lifecycle hook. Fires on `acquireInteractiveSession` success (with the held scene)
   * and on session `close()` (with `scene = null`). Implementations are expected to be cheap and
   * side-effect-isolated — they run on whatever thread called `acquire` / `close`.
   */
  fun interface InteractiveSessionListener {
    fun onSessionLifecycle(previewId: String, scene: androidx.compose.ui.ImageComposeScene?)
  }

  /**
   * INTERACTIVE.md § 9 — `true` only when a [previewSpecResolver] was wired at construction.
   * Without one, [acquireInteractiveSession] throws unconditionally; advertising `true` in that
   * shape would mislead the client into thinking it can dispatch into a held composition. The
   * production daemon-main path always passes a resolver, so the production wire is `true` for
   * desktop daemons and stays `false` for in-process tests that don't wire one (which are the same
   * tests that exercise the v1 fallback path).
   */
  override val supportsInteractive: Boolean
    get() = previewSpecResolver != null

  /**
   * PROTOCOL.md § 3 (`InitializeResult.capabilities.supportedOverrides`) — the desktop renderer
   * applies size / density / fontScale (via `Density(density, fontScale)` on the
   * `ImageComposeScene` constructor + `LocalDensity`), `uiMode` (via `LocalSystemTheme`), and
   * `device` (resolved by `DeviceDimensions`). `localeTag` is no-op on desktop because Compose
   * Desktop has no `LocalLocale` CompositionLocal and `Locale.setDefault(...)` is JVM-thread-
   * unsafe (every other thread would see the override during the render). `orientation` is no-op
   * because `ImageComposeScene` has no rotation concept — see `daemon/desktop/.../RenderEngine.kt`
   * for the docstring.
   */
  override val supportedOverrides: Set<String> =
    setOf("widthPx", "heightPx", "density", "fontScale", "uiMode", "device")

  /** PROTOCOL.md § 3 — desktop backend identifier surfaced via `capabilities.backend`. */
  override val backendKind: ee.schimke.composeai.daemon.protocol.BackendKind =
    ee.schimke.composeai.daemon.protocol.BackendKind.DESKTOP

  private val requests: LinkedBlockingQueue<RenderRequest> = LinkedBlockingQueue()
  private val results: ConcurrentHashMap<Long, LinkedBlockingQueue<Any>> = ConcurrentHashMap()

  /**
   * B2.3 — per-host sandbox-lifecycle counters. Captured at host construction so `sandboxAgeMs` is
   * wall-clock since the desktop host was instantiated; bumped per render- completion by
   * [SandboxMeasurement.collect] (called from [RenderEngine.render]). Sandbox recycle (B2.5) will
   * reset these once it lands; for B2.3 v1 the counter just keeps growing over the host's lifetime
   * — documented behaviour.
   */
  private val sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats()

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
    // The render loop posts either a [RenderResult] (success / stub) or a [Throwable] (engine
    // body threw). Re-throw the Throwable so `JsonRpcServer.submitRenderAsync`'s catch surfaces
    // it as a `renderFailed` notification — the path the v1 `S5RenderFailedRealModeTest` covers.
    if (raw is Throwable) throw raw
    return raw as RenderResult
  }

  /**
   * v2 — allocate a [DesktopInteractiveSession] holding a long-lived
   * [androidx.compose.ui.ImageComposeScene] for [previewId]. The session resolves [previewId] to a
   * [RenderSpec] via the [previewSpecResolver] supplied at construction; if no resolver is wired,
   * or the resolver returns `null` (unknown previewId), this method throws
   * [UnsupportedOperationException] which `JsonRpcServer` catches and falls back to the v1
   * stateless dispatch path.
   *
   * The held scene composes with `LocalInspectionMode = false` so `Modifier.clickable {}` and other
   * pointer-input modifiers fire on `interactive/input` notifications — the v2 payoff.
   */
  override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
  ): InteractiveSession {
    val resolver =
      previewSpecResolver
        ?: throw UnsupportedOperationException(
          "DesktopHost has no previewSpecResolver; pass one at construction time to enable v2 " +
            "interactive sessions"
        )
    val spec =
      resolver(previewId)
        ?: throw UnsupportedOperationException(
          "DesktopHost.previewSpecResolver returned null for previewId='$previewId'; " +
            "interactive session not allocated"
        )
    val state = engine.setUp(spec, classLoader, inspectionMode = false)
    val session =
      DesktopInteractiveSession(
        previewId = previewId,
        engine = engine,
        state = state,
        sandboxStats = sandboxStats,
      )
    val listener = interactiveSessionListener
    if (listener == null) {
      return session
    }
    // D5 — fire onSessionLifecycle(previewId, scene) so the recomposition producer can install
    // its CompositionObserver against the live scene. Wrap the session so that close() also
    // fires onSessionLifecycle(previewId, null) — without the wrapper the producer would leak
    // the observer handle past `interactive/stop`.
    try {
      listener.onSessionLifecycle(previewId, state.scene)
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: DesktopHost: interactiveSessionListener.acquire threw " +
          "(${t.javaClass.simpleName}: ${t.message}); continuing with session"
      )
    }
    return ListenerNotifyingSession(delegate = session, listener = listener, previewId = previewId)
  }

  /**
   * Thin [InteractiveSession] wrapper that fires [interactiveSessionListener]'s "released"
   * notification on [close]. All other calls forward to the wrapped [DesktopInteractiveSession].
   * Idempotent: a second [close] is a no-op (matches the [DesktopInteractiveSession.close]
   * contract).
   */
  private class ListenerNotifyingSession(
    private val delegate: InteractiveSession,
    private val listener: InteractiveSessionListener,
    override val previewId: String,
  ) : InteractiveSession {

    @Volatile private var closed = false

    override fun dispatch(input: ee.schimke.composeai.daemon.protocol.InteractiveInputParams) =
      delegate.dispatch(input)

    override fun render(requestId: Long): RenderResult = delegate.render(requestId)

    override fun close() {
      if (closed) return
      closed = true
      try {
        listener.onSessionLifecycle(previewId, null)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: DesktopHost: interactiveSessionListener.release threw " +
            "(${t.javaClass.simpleName}: ${t.message}); continuing with session.close()"
        )
      }
      delegate.close()
    }
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
          // Two failure modes are routed differently:
          //   1. Spec-payload-not-recognised (legacy `payload="render-N"` from DesktopHostTest):
          //      `dispatchRender` selects [renderStubFallback], which never throws — the result is
          //      a successful stub-path RenderResult.
          //   2. Render-body exception (e.g. `BoomComposable` calling `error("boom")` inside the
          //      composition): `dispatchRender` calls into [RenderEngine.render], which propagates
          //      Throwables. We must NOT swallow them here — `submit()`'s caller is
          //      `JsonRpcServer.submitRenderAsync`, which already catches Throwable and emits
          //      `renderFailed` via the watcher loop. Catching here and falling back to
          //      [renderStubFallback] would suppress the failure into a misleading "successful"
          //      stub render — the bug surfaced by `S5RenderFailedRealModeTest`.
          //
          // We do still catch the throwable on this thread — propagating it out of the loop would
          // kill the render worker, which would in turn make every subsequent `submit()` time out
          // (the daemon's whole value proposition is one long-lived render thread). Instead we
          // post the Throwable onto the per-id result queue; [submit] re-throws it on the caller's
          // thread, which surfaces it as `renderFailed` upstream.
          val result: Any =
            try {
              dispatchRender(request)
            } catch (t: Throwable) {
              // [RenderEngine] dispatches the @Composable via `Method.invoke`, which wraps
              // user-thrown exceptions in [java.lang.reflect.InvocationTargetException]. Unwrap
              // here so the upstream `renderFailed.error.message` carries the original message
              // (e.g. "java.lang.IllegalStateException: boom" instead of the opaque
              // "InvocationTargetException"). Keeps the wire-level error informative without
              // leaking reflection details into S5RenderFailedRealModeTest's assertions.
              unwrapInvocationTarget(t)
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
      // B2.0 — resolve preview classes via the disposable child loader when the holder is wired
      // (production path; the Gradle plugin's daemon launch descriptor sets
      // `composeai.daemon.userClassDirs` and DaemonMain mounts the holder). Falls back to the
      // engine's default classloader when the holder is null (the in-process unit-test path,
      // where testFixtures live on `java.class.path`).
      val classLoader: ClassLoader =
        userClassloaderHolder?.currentChildLoader()
          ?: RenderEngine::class.java.classLoader
          ?: ClassLoader.getSystemClassLoader()
      engine.render(spec, request.id, classLoader, sandboxStats = sandboxStats)
    }
  }

  /**
   * Fallback render for non-spec payloads — returns a [RenderResult] capturing the render-thread
   * classloader identity. Used by [DesktopHostTest]'s 10-render reuse assertion (which submits
   * payloads of the form `render-N`).
   *
   * **Not** invoked when the real engine throws — that case propagates the Throwable through the
   * result queue so [submit] can re-raise it (see the comment in [runRenderLoop]). Falling back to
   * a successful stub here would suppress real render failures.
   */
  private fun renderStubFallback(id: Long): RenderResult {
    val cl = Thread.currentThread().contextClassLoader ?: DesktopHost::class.java.classLoader
    return RenderResult(
      id = id,
      classLoaderHashCode = System.identityHashCode(cl),
      classLoaderName = cl?.javaClass?.name ?: "<null>",
    )
  }

  /**
   * If [t] is a [java.lang.reflect.InvocationTargetException] (or has one in its cause chain),
   * return its underlying cause; otherwise return [t]. Used to surface the original user-thrown
   * exception across [RenderEngine]'s reflective composable dispatch.
   */
  private fun unwrapInvocationTarget(t: Throwable): Throwable {
    var current: Throwable = t
    while (current is java.lang.reflect.InvocationTargetException) {
      current = current.targetException ?: return current
    }
    return current
  }
}
