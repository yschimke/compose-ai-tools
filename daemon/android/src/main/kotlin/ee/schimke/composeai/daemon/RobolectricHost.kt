package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric-backed [RenderHost]. Holds a single Robolectric sandbox open
 * for the lifetime of the daemon — see docs/daemon/DESIGN.md § 9
 * ("Bootstrap").
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
 *
 * **Render-body exceptions propagate** — when [RenderEngine.render] throws (e.g.
 * `BoomComposable`'s `error("boom")` inside the composition), the loop posts the Throwable onto
 * the per-id result queue (typed `LinkedBlockingQueue<Any>` in [DaemonHostBridge], so the
 * Throwable rides through unchanged) and [submit] re-throws on the caller's thread. The Throwable
 * then surfaces upstream as a `renderFailed` notification (see `JsonRpcServer.submitRenderAsync`'s
 * Throwable catch). Earlier versions caught the exception inside [SandboxRunner.holdSandboxOpen]
 * and fell back to [SandboxRunner.renderStub], returning a misleading "successful" stub — the bug
 * `S5RenderFailedAndroidRealModeTest` was written to pin (matching the same pre-fix shape
 * `:daemon:desktop`'s `DesktopHost` had before the post-D-harness.v1.5b fix).
 *
 * The legacy stub-payload path (B1.3-era `payload="render-N"` that `DaemonHostTest` submits) is
 * unchanged — `dispatchRender`'s `parseFromPayloadOrNull` returns `null` and the call resolves to
 * [SandboxRunner.renderStub] without ever entering the engine. The discriminator stays "did
 * `parseFromPayloadOrNull` return a usable spec".
 */
open class RobolectricHost(
  /**
   * Disposable user-class loader holder (B2.0 — see
   * [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)). When non-null, every
   * sandbox-side [RenderEngine.render] dispatch resolves preview classes via the holder's
   * [UserClassLoaderHolder.currentChildLoader] rather than the sandbox classloader; the
   * `JsonRpcServer.handleFileChanged` path swaps it on `kind: "source"`. The host thread mirrors
   * the current loader into [DaemonHostBridge.childLoaderRef] so the sandbox-side render reads it
   * via the bridge (the holder itself is in `:daemon:core`'s package which Robolectric
   * re-loads inside the sandbox; the bridge package is do-not-acquire so its static state is
   * shared).
   *
   * `null` keeps the legacy "user classes are on the sandbox classpath" behaviour, preserving
   * existing unit tests where testFixtures live on `java.class.path` and Robolectric's
   * InstrumentingClassLoader resolves them.
   */
  override val userClassloaderHolder: UserClassLoaderHolder? = null,
) : RenderHost {

  private val workerThread =
    Thread({ runJUnit() }, "compose-ai-daemon-host").apply { isDaemon = false }

  /**
   * Starts the host thread. After this call the worker thread is alive and
   * waiting for requests on [DaemonHostBridge.requests]. The first [submit]
   * still blocks until the Robolectric sandbox is fully bootstrapped
   * (~5–15s on a typical dev machine), but subsequent submits hit a hot
   * sandbox and return in stub-render time.
   */
  override fun start() {
    DaemonHostBridge.reset()
    // B2.0-followup — do NOT pre-publish the child loader here. The holder's `parentSupplier`
    // resolves to the sandbox classloader via `DaemonHostBridge.sandboxClassLoaderRef`, which is
    // set inside `SandboxRunner.holdSandboxOpen` once the sandbox boots. Calling
    // `holder.currentChildLoader()` here would race the sandbox boot and throw — or worse, allocate
    // with a fallback parent. We defer publishing to `submit()`, which awaits the sandbox-ready
    // latch first.
    workerThread.start()
  }

  /**
   * Mirrors the holder's current child classloader into [DaemonHostBridge.childLoaderRef] so the
   * sandbox-side render thread can read it (the host-side holder lives in a package Robolectric
   * re-loads inside the sandbox; the bridge package is do-not-acquire). Called before every
   * [submit] so a fileChanged-driven swap on the JSON-RPC thread is visible to the next render —
   * see B2.0's no-mid-render-cancellation discipline.
   *
   * Awaits the sandbox-ready latch (via [DaemonHostBridge.awaitSandboxReady]) before allocating, so
   * the holder's `parentSupplier` (which reads `DaemonHostBridge.sandboxClassLoaderRef`) sees the
   * sandbox loader, not null. The 60-second timeout is generous for a cold Robolectric sandbox
   * boot (~5–15s in practice) and well under any realistic "the sandbox failed to bootstrap"
   * threshold.
   */
  private fun publishChildLoader() {
    val holder = userClassloaderHolder ?: return
    val sandboxReady = DaemonHostBridge.awaitSandboxReady(timeoutMs = 60_000)
    check(sandboxReady) {
      "sandbox didn't initialise within 60s — holdSandboxOpen never set sandboxClassLoaderRef. " +
        "Check the SandboxHoldingRunner / Robolectric sandbox bootstrap logs."
    }
    DaemonHostBridge.setCurrentChildLoader(holder.currentChildLoader())
  }

  /**
   * Submits one request and blocks until its [RenderResult] is available.
   *
   * @param timeoutMs upper bound on the wait; defaults to 60s which is
   *   generous for the first call (sandbox cold-boot dominates) and still
   *   well under any reasonable "sandbox failed to bootstrap" timeout.
   */
  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    // B2.0 — publish the (possibly-just-swapped) child classloader to the bridge so the
    // sandbox-side render dispatch picks it up. `holder.currentChildLoader()` is lazily allocated
    // on first read after a swap, so this also amortises the allocation onto the host thread
    // rather than the render thread.
    publishChildLoader()
    DaemonHostBridge.requests.put(typed)
    val resultQueue = DaemonHostBridge.results.computeIfAbsent(typed.id) { LinkedBlockingQueue() }
    val raw =
      resultQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: error("RobolectricHost.submit($typed) timed out after ${timeoutMs}ms")
    DaemonHostBridge.results.remove(typed.id)
    // The sandbox-side loop posts either a [RenderResult] (success / stub) or a [Throwable]
    // (engine body threw). Re-throw the Throwable so `JsonRpcServer.submitRenderAsync`'s catch
    // surfaces it as a `renderFailed` notification — the path `S5RenderFailedAndroidRealModeTest`
    // covers. Throwables ride the bridge's `LinkedBlockingQueue<Any>` cleanly: `java.lang.*` is
    // not instrumented by Robolectric's `InstrumentingClassLoader`, so a sandbox-thrown
    // `IllegalStateException` is the same class object the host-thread `is`-check sees.
    if (raw is Throwable) throw raw
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
    // pngPath / metrics fields landed in B1.4. Read them reflectively too so a sandbox-side
    // RenderResult instance carries its real-render payload back to the host caller.
    val pngPath = cls.getMethod("getPngPath").invoke(raw) as String?
    @Suppress("UNCHECKED_CAST")
    val metrics = cls.getMethod("getMetrics").invoke(raw) as Map<String, Long>?
    return RenderResult(
      id = id,
      classLoaderHashCode = hash,
      classLoaderName = name,
      pngPath = pngPath,
      // Re-wrap into the host-classloader's Map type — the sandbox-side instance is a
      // java.util.LinkedHashMap whose generic params survive the bridge unchanged (java.util.* is
      // a do-not-acquire boundary by default), so this is effectively a no-op copy. Done
      // explicitly so a future change to the metrics type is observable here.
      metrics = metrics?.let { LinkedHashMap(it) },
    )
  }

  /**
   * Sends the poison pill, waits up to [timeoutMs] for the worker thread to
   * exit. Idempotent.
   */
  companion object {
    /**
     * Forensic-dump payload prefix — see docs/daemon/CLASSLOADER-FORENSICS.md § Implementation
     * seam. Routed through the existing `RenderRequest.Render.payload` field rather than a new
     * sealed-hierarchy variant so `:daemon:core`'s public surface stays unchanged.
     *
     * Wire format: `forensic-dump=<absolute-path>;survey=<csv-of-fqns>`. Recognised by
     * [SandboxRunner.dispatchRender] which dispatches to `ClassloaderForensics.capture(...)`
     * inside the sandbox.
     */
    const val FORENSIC_DUMP_PREFIX: String = "forensic-dump="

    /** Output-path key inside the forensic payload (see [FORENSIC_DUMP_PREFIX]). */
    const val FORENSIC_DUMP_KEY: String = "forensic-dump"

    /** Survey-set key inside the forensic payload — comma-separated FQNs. */
    const val FORENSIC_SURVEY_KEY: String = "survey"
  }

  override fun shutdown(timeoutMs: Long) {
    DaemonHostBridge.shutdown.set(true)
    // Belt-and-braces: also enqueue a Shutdown so the worker wakes from
    // poll() promptly rather than waiting out the 100ms cycle.
    DaemonHostBridge.requests.put(RenderRequest.Shutdown)
    workerThread.join(timeoutMs)
    if (workerThread.isAlive) {
      error("RobolectricHost worker did not exit within ${timeoutMs}ms after shutdown")
    }
  }

  private fun runJUnit() {
    val result = JUnitCore.runClasses(SandboxRunner::class.java)
    if (!result.wasSuccessful()) {
      // Surface to stderr; the caller's shutdown() join will time out and
      // explicit logging helps diagnostics.
      for (failure in result.failures) {
        System.err.println("RobolectricHost SandboxRunner failed: ${failure.message}")
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
   *
   * `@GraphicsMode(NATIVE)` is required by B1.4's real render body — Roborazzi's
   * `captureRoboImage` walks `HardwareRenderer` to materialise the bitmap, which
   * is only available under NATIVE graphics mode. The B1.3-era stub render
   * didn't need it but the annotation is harmless when the body is a stub.
   */
  @RunWith(SandboxHoldingRunner::class)
  @Config(sdk = [35])
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  class SandboxRunner {

    /**
     * Lazily-constructed [RenderEngine] — created inside the sandbox so its companion-object
     * default `outputDir` (which reads `composeai.render.outputDir`) resolves against the
     * sandbox JVM, not the test thread's. One engine per sandbox per host lifetime; no
     * per-render reconstruction.
     */
    private val engine: RenderEngine by lazy { RenderEngine() }

    /**
     * B2.3 — per-sandbox lifecycle counters, instantiated inside the sandbox so `sandboxAgeMs`
     * is wall-clock since `holdSandboxOpen` started (i.e. since the sandbox booted, not since
     * the host thread spawned). One stats instance per sandbox lifetime; bumped on every
     * render-completion by [SandboxMeasurement.collect] via [RenderEngine.render]. Sandbox
     * recycle (B2.5) will replace this; until then it counts up forever.
     */
    private val sandboxStats: SandboxLifecycleStats by lazy { SandboxLifecycleStats() }

    @Test
    fun holdSandboxOpen() {
      // B2.0-followup — register the sandbox classloader on the bridge as the very first
      // prologue line, BEFORE we start polling for requests. The host's `UserClassLoaderHolder`'s
      // `parentSupplier` reads this ref to allocate the child URLClassLoader with the sandbox
      // loader as parent (not the host thread's app loader). Forensics-confirmed root cause for
      // the Android save-loop's classloader-identity skew. See
      // `docs/daemon/classloader-forensics-diff.md` and the `sandboxClassLoaderRef` KDoc on
      // `DaemonHostBridge`.
      DaemonHostBridge.setSandboxClassLoader(this.javaClass.classLoader)

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
            val payload = request.javaClass.getMethod("getPayload").invoke(request) as String
            // Two failure modes are routed differently — same shape as
            // `:daemon:desktop`'s `DesktopHost.runRenderLoop`:
            //   1. Spec-payload-not-recognised (legacy `payload="render-N"` from
            //      `DaemonHostTest`'s 10-render reuse assertion): `dispatchRender` selects
            //      [renderStub], which never throws — the result is a successful stub-path
            //      [RenderResult].
            //   2. Render-body exception (e.g. `BoomComposable` calling `error("boom")` inside
            //      the composition): `dispatchRender` calls into [RenderEngine.render], which
            //      propagates Throwables. We must NOT swallow them here — `submit()`'s caller
            //      is `JsonRpcServer.submitRenderAsync`, which already catches Throwable and
            //      emits `renderFailed` via the watcher loop. Catching here and falling back to
            //      [renderStub] would suppress the failure into a misleading "successful" stub
            //      render — the bug surfaced by `S5RenderFailedAndroidRealModeTest`.
            //
            // We do still catch the throwable on this thread — propagating it out of the
            // `@Test` body would make JUnit fail the dummy test and tear the sandbox down,
            // which would in turn make every subsequent `submit()` time out (the daemon's
            // whole value proposition is one long-lived sandbox). Instead we post the Throwable
            // onto the per-id result queue; [submit] re-throws it on the caller's thread, which
            // surfaces it as `renderFailed` upstream.
            val result: Any =
              try {
                dispatchRender(id, payload)
              } catch (t: Throwable) {
                // [RenderEngine] dispatches the @Composable via `Method.invoke`, which wraps
                // user-thrown exceptions in [java.lang.reflect.InvocationTargetException].
                // Unwrap here so the upstream `renderFailed.error.message` carries the original
                // message (e.g. "java.lang.IllegalStateException: boom" instead of the opaque
                // "InvocationTargetException"). Keeps the wire-level error informative without
                // leaking reflection details into S5RenderFailedAndroidRealModeTest's
                // assertions.
                unwrapInvocationTarget(t)
              }
            DaemonHostBridge.results
              .computeIfAbsent(id) { LinkedBlockingQueue() }
              .put(result)
          }
          else -> error("unknown RenderRequest subtype: ${request.javaClass.name}")
        }
      }
    }

    /**
     * Routes one render request to either [RenderEngine.render] (if the payload parses as a
     * [RenderSpec]) or to the legacy classloader-identity stub. Same discriminator pattern
     * `:daemon:desktop`'s [DesktopHost.dispatchRender] uses — payloads that don't carry
     * `className=` (B1.3-era unit-test payloads of the form `render-N`) take the stub path so
     * `RobolectricHostTest`'s sandbox-reuse assertion keeps working through B1.4.
     */
    private fun dispatchRender(id: Long, payload: String): RenderResult {
      // Forensic-dump branch — see docs/daemon/CLASSLOADER-FORENSICS.md § Implementation seam.
      // Routed through the existing `RenderRequest.Render.payload` free-form field rather than a
      // new `RenderRequest` variant, per CLASSLOADER-FORENSICS.md's "don't widen the core's
      // sealed hierarchy" constraint. The payload format is
      // `forensic-dump=<absolute-path>;survey=<comma-separated-fqns>` — `;`-delimited like
      // `RenderSpec.parseFromPayloadOrNull` so a future merge into a single dispatch table is a
      // small refactor rather than a redesign. The dump runs *here*, inside the sandbox
      // classloader (Robolectric's `InstrumentingClassLoader`), with the child `URLClassLoader`
      // active via `Thread.currentThread().contextClassLoader` — exactly the state a real render
      // sees, which is the whole point of the daemon-path dump.
      if (payload.startsWith(FORENSIC_DUMP_PREFIX)) {
        return runForensicDump(id, payload)
      }
      val spec = RenderSpec.parseFromPayloadOrNull(payload) ?: return renderStub(id)
      // B2.0 — pick up the disposable child classloader that the host thread has published into
      // the bridge. When no holder is wired (legacy in-process tests where the testFixtures live
      // on the sandbox classpath), the bridge returns null and we fall through to the sandbox's
      // own context classloader — the pre-B2.0 behaviour.
      val classLoader: ClassLoader =
        DaemonHostBridge.currentChildLoader()
          ?: Thread.currentThread().contextClassLoader
          ?: RenderEngine::class.java.classLoader
      return engine.render(spec, id, classLoader, sandboxStats = sandboxStats)
    }

    /**
     * Forensic-dump dispatch — mirrors the design's Configuration B requirement: the dump call
     * must run inside the sandbox classloader with the child `URLClassLoader` active so the survey
     * reflects what the daemon actually sees during a render. We install the child loader as the
     * context classloader for the duration of the capture (same discipline [RenderEngine.render]
     * uses), then restore.
     *
     * Loaded reflectively because `:daemon:android`'s main classpath doesn't include the
     * forensics library at compile time — it's a `:daemon:core` library and the dispatch
     * decision lives in `:daemon:android`. The reflective call surface is tiny (one
     * static `capture(List, Object?, String, File)` method) so dropping the compile-time link is
     * cheap relative to the dependency-cycle risk of pulling forensics into the host's main code.
     */
    private fun runForensicDump(id: Long, payload: String): RenderResult {
      val parsed = parseForensicPayload(payload)
      val outFile = java.io.File(parsed.outPath)
      val survey = parsed.survey

      val previousContext = Thread.currentThread().contextClassLoader
      val effectiveLoader: ClassLoader =
        DaemonHostBridge.currentChildLoader()
          ?: previousContext
          ?: RenderEngine::class.java.classLoader
      Thread.currentThread().contextClassLoader = effectiveLoader
      try {
        // Resolve `ClassloaderForensics` and `RobolectricConfigSnapshot` reflectively against the
        // sandbox loader so we don't pull `:daemon:core` onto the host module's main
        // compile classpath. The forensics library lives on the sandbox runtime classpath via
        // the daemon's normal :daemon:core dep.
        val forensicsClass =
          Class.forName(
            "ee.schimke.composeai.daemon.forensics.ClassloaderForensics",
            true,
            effectiveLoader,
          )
        // The library is `object ClassloaderForensics` — its singleton instance is reachable via
        // the synthetic `INSTANCE` field that Kotlin emits.
        val instance = forensicsClass.getField("INSTANCE").get(null)
        val captureMethod =
          forensicsClass.getMethod(
            "capture",
            java.util.List::class.java,
            Class.forName(
              "ee.schimke.composeai.daemon.forensics.RobolectricConfigSnapshot",
              true,
              effectiveLoader,
            ),
            String::class.java,
            java.io.File::class.java,
          )
        captureMethod.invoke(instance, survey, /*robolectricConfig=*/ null, "daemon-subject", outFile)
      } finally {
        Thread.currentThread().contextClassLoader = previousContext
      }

      val cl = Thread.currentThread().contextClassLoader
      return RenderResult(
        id = id,
        classLoaderHashCode = System.identityHashCode(cl),
        classLoaderName = cl?.javaClass?.name ?: "<null>",
        pngPath = outFile.absolutePath,
        metrics = null,
      )
    }

    private data class ForensicPayload(val outPath: String, val survey: List<String>)

    private fun parseForensicPayload(payload: String): ForensicPayload {
      val map = mutableMapOf<String, String>()
      for (entry in payload.split(';')) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
      }
      val outPath = map[FORENSIC_DUMP_KEY] ?: error("forensic-dump payload missing $FORENSIC_DUMP_KEY=")
      val survey = map[FORENSIC_SURVEY_KEY]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList()
      return ForensicPayload(outPath = outPath, survey = survey)
    }

    /**
     * Stub render — returns a [RenderResult] capturing the sandbox classloader identity. Used by
     * the B1.3-era sandbox-reuse test (which submits `payload="render-N"` strings without a
     * spec) so the `DaemonHostTest` 10-render classloader-reuse assertion keeps working.
     *
     * **Not** invoked when the real engine throws — that case propagates the Throwable through
     * the result queue so [submit] can re-raise it (see the comment in [holdSandboxOpen]).
     * Falling back to a successful stub here would suppress real render failures.
     */
    private fun renderStub(id: Long): RenderResult {
      val cl = Thread.currentThread().contextClassLoader
      return RenderResult(
        id = id,
        classLoaderHashCode = System.identityHashCode(cl),
        classLoaderName = cl?.javaClass?.name ?: "<null>",
      )
    }

    /**
     * If [t] is a [java.lang.reflect.InvocationTargetException] (or chain thereof), return its
     * underlying cause; otherwise return [t]. Used to surface the original user-thrown
     * exception across [RenderEngine]'s reflective composable dispatch.
     *
     * Mirrors the same-named helper in `:daemon:desktop`'s `DesktopHost`. Duplicated
     * rather than promoted to `:daemon:core` because the renderer-agnostic-surface
     * invariant says "no compose/renderer types in core"; while `InvocationTargetException` is
     * fully renderer-agnostic (`java.lang.reflect.*`), promoting a single helper for two
     * call-sites isn't worth widening the core surface for. Re-evaluate when a third backend
     * materialises.
     */
    private fun unwrapInvocationTarget(t: Throwable): Throwable {
      var current: Throwable = t
      while (current is java.lang.reflect.InvocationTargetException) {
        current = current.targetException ?: return current
      }
      return current
    }
  }
}
