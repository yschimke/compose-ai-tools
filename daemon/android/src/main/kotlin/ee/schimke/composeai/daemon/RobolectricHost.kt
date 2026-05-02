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
  /**
   * Number of Robolectric sandboxes to host in parallel — see
   * [SANDBOX-POOL.md](../../../../../../docs/daemon/SANDBOX-POOL.md). Default `1` preserves the
   * pre-pool single-sandbox behaviour bit-for-bit. `> 1` boots N sandboxes in this JVM and
   * dispatches concurrent renders across them via affinity-aware slot routing in [submit].
   *
   * For the legacy single-instance [userClassloaderHolder] param (sandboxCount=1 only): set
   * [userClassloaderHolderFactory] instead when you need the pool. Both paths are otherwise
   * equivalent.
   */
  val sandboxCount: Int = 1,
  /**
   * Per-slot user-class loader factory — SANDBOX-POOL-FOLLOWUPS.md (#1, per-slot child loaders).
   * When non-null, the host invokes the factory once per sandbox slot at first dispatch (with the
   * slot's sandbox classloader) to allocate that slot's [UserClassLoaderHolder]. Each slot's
   * holder owns a child `URLClassLoader` parented to its own sandbox loader, so the framework
   * classes the child resolves match the sandbox the render runs in (no classloader-identity
   * skew across slots).
   *
   * Mutually exclusive with the legacy single-instance [userClassloaderHolder] — at most one of
   * the two may be non-null. Per-slot is required for `sandboxCount > 1` when hot-reload (file
   * changed → swap user classloaders) is wired; the single-instance form is retained for
   * single-sandbox callers and existing tests.
   */
  val userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? =
    null,
) : RenderHost {

  /**
   * PROTOCOL.md § 3 (`InitializeResult.capabilities.supportedOverrides`) — the Robolectric
   * renderer applies all eight `PreviewOverrides` fields. Size / density / locale / uiMode /
   * orientation / device flow into `RuntimeEnvironment.setQualifiers`; `fontScale` flows into
   * `RuntimeEnvironment.setFontScale` (a Configuration knob, not a qualifier — see
   * `daemon/android/.../RenderEngine.kt` for the call site).
   */
  override val supportedOverrides: Set<String> =
    setOf(
      "widthPx",
      "heightPx",
      "density",
      "localeTag",
      "fontScale",
      "uiMode",
      "orientation",
      "device",
    )

  init {
    require(sandboxCount >= 1) { "sandboxCount must be >= 1, got $sandboxCount" }
    require(userClassloaderHolder == null || userClassloaderHolderFactory == null) {
      "RobolectricHost: pass either userClassloaderHolder OR userClassloaderHolderFactory, not " +
        "both. The factory form supersedes the single-instance form for sandboxCount > 1."
    }
    require(sandboxCount == 1 || userClassloaderHolder == null) {
      "RobolectricHost: sandboxCount > 1 with a non-null userClassloaderHolder is not supported " +
        "(per-slot child URLClassLoaders need their own per-slot parent). Use " +
        "userClassloaderHolderFactory for the pool path. Got sandboxCount=$sandboxCount."
    }
  }

  /**
   * Per-slot holders, allocated lazily on first dispatch to each slot. `null` when the host runs
   * without any user-class isolation (factory unset, holder unset) — that's the default for the
   * harness's in-process tests where testFixtures live on the sandbox classpath.
   *
   * Slot 0 is also populated by the legacy [userClassloaderHolder] path, so single-sandbox
   * callers using either constructor form end up with a holder at index 0.
   */
  private val perSlotHolders: java.util.concurrent.atomic.AtomicReferenceArray<UserClassLoaderHolder?> =
    java.util.concurrent.atomic.AtomicReferenceArray<UserClassLoaderHolder?>(sandboxCount).also {
      // Seed slot 0 with the legacy single-instance holder so `swapUserClassLoaders()` and
      // `publishChildLoader()` see one consistent source of truth.
      if (userClassloaderHolder != null) it.set(0, userClassloaderHolder)
    }

  private val workerThreads: List<Thread> =
    (0 until sandboxCount).map { i ->
      Thread({ runJUnit(workerIndex = i) }, threadName(i)).apply { isDaemon = false }
    }

  private fun threadName(i: Int): String =
    if (sandboxCount == 1) "compose-ai-daemon-host" else "compose-ai-daemon-host-$i"

  /**
   * Starts the host thread AND blocks until the Robolectric sandbox is fully bootstrapped.
   *
   * After this returns, [submit] is guaranteed to find a hot sandbox — no per-submit
   * sandbox-ready waits, no cold-start cliff where the first N submits each race a 60s timeout
   * before the sandbox finishes building.
   *
   * Cold-start cost on a fresh `~/.cache/robolectric` is dominated by downloading
   * `android-all-instrumented-{ver}-{sdk}.jar` (~150 MB) and instrumenting every class on the
   * daemon's classpath; that can run into minutes. Warm starts (cache hit + no incremental
   * rebuild) are ~5–15s. The configurable timeout below is the upper bound on the cold path.
   *
   * The protocol model is `daemonReady = sandboxReady`: `initialize` must not return success
   * while the sandbox is still bootstrapping, so the client surfaces a clean "warming" state on
   * its side rather than rendering against a half-built host. Blocking here in [start] delivers
   * that — `JsonRpcServer.run()` only enters its read loop once we return.
   *
   * Configurable via `composeai.daemon.sandboxBootTimeoutMs` (default 600_000 = 10 minutes). On
   * timeout the daemon refuses to start; the client surfaces the failure via initialize never
   * returning rather than via per-render timeouts.
   */
  override fun start() {
    StartupTimings.mark("RobolectricHost.start() entered")
    DaemonHostBridge.reset()
    DaemonHostBridge.configureSlotCount(sandboxCount)
    val timeoutMs =
      System.getProperty(SANDBOX_BOOT_TIMEOUT_PROP)?.toLongOrNull()
        ?: DEFAULT_SANDBOX_BOOT_TIMEOUT_MS

    // SANDBOX-POOL.md — boot sandboxes sequentially, one worker at a time. We could in principle
    // start them concurrently (each sandbox is independent now that the cache-key fix lands and
    // `SandboxManager.getAndroidSandbox` is internally synchronized), but sequenced boots keep
    // diagnosis simple if a future Robolectric upgrade reintroduces a global-state path. Total
    // cold-start is proportional to N × per-sandbox-boot — on warm cache 5–15s × N — acceptable
    // for typical pool sizes.
    var bootedThrough = -1
    try {
      for (i in 0 until sandboxCount) {
        workerThreads[i].start()
        StartupTimings.mark(
          if (sandboxCount == 1) "worker thread launched (Robolectric init begins)"
          else "worker $i launched (Robolectric init begins)"
        )
        val slot = DaemonHostBridge.slot(i)
        val ready = slot.awaitSandboxReady(timeoutMs)
        if (!ready) {
          if (sandboxCount > 1) dumpAllThreadsToStderr(slot = i, timeoutMs = timeoutMs)
          error(
            "Robolectric sandbox slot $i failed to bootstrap within ${timeoutMs}ms — " +
              "holdSandboxOpen never registered. On a cold cache the instrumented android-all " +
              "jar download can dominate; raise the timeout via -D$SANDBOX_BOOT_TIMEOUT_PROP=<ms>." +
              " Otherwise check the SandboxHoldingRunner / Robolectric sandbox bootstrap logs."
          )
        }
        bootedThrough = i
      }
    } catch (t: Throwable) {
      runCatching { abortPartialStart(bootedThrough) }
      throw t
    }
    StartupTimings.mark(
      if (sandboxCount == 1) "sandbox-ready latch fired" else "all sandbox-ready latches fired"
    )
  }

  /**
   * Cleanup helper for [start] failures. Sets the shared shutdown flag, poisons every (already
   * configured) slot's request queue, and joins each booted worker with a short bounded wait. A
   * worker still stuck inside Robolectric bootstrap won't see the poison pill — those threads
   * remain orphaned in this JVM, which is the best we can do without forcibly killing them.
   */
  private fun abortPartialStart(bootedThrough: Int) {
    DaemonHostBridge.shutdown.set(true)
    val slots = DaemonHostBridge.slots()
    for ((i, slot) in slots.withIndex()) {
      if (i <= bootedThrough) runCatching { slot.requests.put(RenderRequest.Shutdown) }
    }
    for ((i, t) in workerThreads.withIndex()) {
      if (i <= bootedThrough) runCatching { t.join(2_000) }
    }
  }

  /**
   * SANDBOX-POOL.md (Layer 2) diagnostic — dumps every thread's stack to stderr when a sandbox
   * slot misses its ready latch. The dump is the load-bearing input for choosing between a
   * Robolectric workaround (if a fixable lock is identifiable) and the lower-level Sandbox API
   * pivot. Always-on so the diagnostic is a first-class artifact of pool failures.
   */
  private fun dumpAllThreadsToStderr(slot: Int, timeoutMs: Long) {
    val sb = StringBuilder()
    sb.append(
      "===== sandbox-pool stall diagnostic — slot $slot did not boot within ${timeoutMs}ms =====\n"
    )
    val all = Thread.getAllStackTraces()
    for ((thread, stack) in all.entries.sortedBy { it.key.name }) {
      sb.append("[${thread.name}] state=${thread.state} daemon=${thread.isDaemon}\n")
      for (frame in stack) sb.append("    at $frame\n")
      sb.append("\n")
    }
    sb.append("===== end stall diagnostic =====\n")
    System.err.print(sb)
    System.err.flush()
  }

  /**
   * SANDBOX-POOL-FOLLOWUPS.md (#1) — lazily allocate the holder for [slotIdx] from
   * [userClassloaderHolderFactory], using the slot's already-claimed sandbox classloader as
   * parent. Idempotent (CAS); subsequent calls return the same instance until [swapUserClassLoaders]
   * drops it. Returns `null` when no factory is wired, in which case slot 0's
   * [DaemonHostBridge.SandboxSlot.childLoaderRef] may already hold the legacy single-instance
   * holder's child loader (seeded into [perSlotHolders]).
   */
  private fun ensureHolderForSlot(slotIdx: Int): UserClassLoaderHolder? {
    perSlotHolders.get(slotIdx)?.let { return it }
    val factory = userClassloaderHolderFactory ?: return null
    val sandboxLoader =
      DaemonHostBridge.slot(slotIdx).sandboxClassLoaderRef.get()
        ?: error(
          "RobolectricHost.ensureHolderForSlot($slotIdx): slot's sandbox classloader is null — " +
            "submit() should only be called after start() returns, by which point every slot has " +
            "registered its loader."
        )
    val candidate = factory(sandboxLoader)
    return if (perSlotHolders.compareAndSet(slotIdx, null, candidate)) candidate
    else perSlotHolders.get(slotIdx)
  }

  /**
   * Mirrors the slot's holder's current child classloader into the slot's
   * [DaemonHostBridge.SandboxSlot.childLoaderRef] so the sandbox-side render thread can read it.
   * Called per-submit so a fileChanged-driven [swapUserClassLoaders] on the JSON-RPC thread is
   * visible to the next render — preserves B2.0's no-mid-render-cancellation discipline.
   *
   * Per-slot since the affinity-aware dispatch can land each preview on a different sandbox; the
   * mirror needs to land on the slot the render is about to dispatch to.
   */
  private fun publishChildLoaderForSlot(slotIdx: Int) {
    val holder = ensureHolderForSlot(slotIdx) ?: return
    DaemonHostBridge.slot(slotIdx).childLoaderRef.set(holder.currentChildLoader())
  }

  /**
   * SANDBOX-POOL-FOLLOWUPS.md (#1) — broadcast `swap()` to every slot's holder. Each holder
   * lazily re-allocates its child `URLClassLoader` on next read, so the next render to that slot
   * sees the recompiled bytecode. No-op when no holder is wired (default in-process tests).
   */
  override fun swapUserClassLoaders() {
    for (i in 0 until sandboxCount) {
      perSlotHolders.get(i)?.swap()
    }
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
    // SANDBOX-POOL.md — slot dispatch. Hash the **previewId** to a slot so the same preview always
    // lands on the same sandbox; Compose snapshot caches and Robolectric shadow caches accumulate
    // per-sandbox and pay off on repeat renders. Falls back to the request id when the payload
    // doesn't carry `previewId=<id>` (legacy stub-render-N test payloads, or any future caller
    // that bypasses the manifest path) so legacy callers keep their stable-id hashing. With
    // sandboxCount=1 either dispatch path collapses to slot 0, which is byte-identical with the
    // pre-pool path (slot 0's `requests` queue is the same instance as the legacy top-level
    // queue).
    val slotIdx = chooseSlotIndex(typed)
    val slot = DaemonHostBridge.slot(slotIdx)
    // SANDBOX-POOL-FOLLOWUPS.md (#1) — publish the (possibly-just-swapped) child classloader to
    // the slot the render is about to land on. `currentChildLoader()` is lazily allocated on
    // first read after a swap, so this also amortises the allocation onto the host thread rather
    // than the render thread.
    publishChildLoaderForSlot(slotIdx)
    slot.requests.put(typed)
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

  /**
   * SANDBOX-POOL.md (affinity-aware dispatch) — picks a slot for [render]. Uses the previewId
   * extracted from the payload as the affinity key when present so the same preview always lands
   * on the same sandbox; falls back to the monotonic request id when the payload is the legacy
   * stub form (`render-N`) or has no `previewId=` key.
   *
   * `Math.floorMod` keeps the slot index non-negative for any hash. With `sandboxCount = 1` both
   * paths collapse to slot 0 — bit-identical with the pre-pool single-sandbox dispatch.
   */
  private fun chooseSlotIndex(render: RenderRequest.Render): Int {
    if (sandboxCount == 1) return 0
    val previewId = parsePreviewIdFromPayload(render.payload)
    val key: Int = previewId?.hashCode() ?: render.id.hashCode()
    return Math.floorMod(key, sandboxCount)
  }

  /**
   * Extracts `previewId=<id>` from a `;`-delimited payload. Mirrors the parsing
   * `PreviewManifestRouter.parsePreviewId` does in the same package — duplicated here rather than
   * shared via a top-level helper so the dispatch path stays a self-contained one-line read.
   * Returns `null` when the payload doesn't carry a previewId (legacy stub-payload tests).
   */
  private fun parsePreviewIdFromPayload(payload: String): String? {
    if (payload.isEmpty()) return null
    for (entry in payload.split(';')) {
      val trimmed = entry.trim()
      if (trimmed.startsWith(PREVIEW_ID_KEY)) {
        return trimmed.substring(PREVIEW_ID_KEY.length).trim().takeIf { it.isNotEmpty() }
      }
    }
    return null
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
     * Sysprop knob for [start]'s sandbox-bootstrap deadline. Cold first run on an empty
     * `~/.cache/robolectric` is dominated by the `android-all-instrumented-{ver}-{sdk}.jar`
     * download (~150 MB) plus instrumenting every class on the daemon's classpath; the default
     * 10-minute ceiling covers that. Warm boots (cache hit) are 5–15s and never approach this
     * limit. Override with `-Dcomposeai.daemon.sandboxBootTimeoutMs=<ms>` for slower CI
     * runners or constrained networks.
     */
    const val SANDBOX_BOOT_TIMEOUT_PROP: String = "composeai.daemon.sandboxBootTimeoutMs"

    /** 10 minutes — covers a cold-cache instrumented-android-all download + first-time scan. */
    const val DEFAULT_SANDBOX_BOOT_TIMEOUT_MS: Long = 10L * 60L * 1000L

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

    /**
     * Affinity-key prefix in the [RenderRequest.Render.payload] used by
     * [chooseSlotIndex]. `JsonRpcServer.handleRenderNow` and `PreviewManifestRouter` both encode
     * the previewId here; the dispatch path reads it to pin renders of the same preview to the
     * same sandbox slot. Mirrors the prefix `PreviewManifestRouter.parsePreviewId` recognises.
     */
    private const val PREVIEW_ID_KEY: String = "previewId="
  }

  override fun shutdown(timeoutMs: Long) {
    DaemonHostBridge.shutdown.set(true)
    // Belt-and-braces: also enqueue a Shutdown on every slot's queue so each worker wakes from
    // poll() promptly rather than waiting out the 100ms cycle.
    DaemonHostBridge.slots().forEach { runCatching { it.requests.put(RenderRequest.Shutdown) } }
    workerThreads.forEach { it.join(timeoutMs) }
    val stuck = workerThreads.filter { it.isAlive }
    if (stuck.isNotEmpty()) {
      error(
        "RobolectricHost worker(s) did not exit within ${timeoutMs}ms after shutdown: " +
          stuck.joinToString(", ") { it.name }
      )
    }
  }

  private fun runJUnit(workerIndex: Int) {
    // Pin the worker-index hint on this thread before invoking JUnit. SandboxHoldingRunner reads
    // it in `createClassLoaderConfig` to add a synthetic `doNotAcquirePackage` discriminator,
    // forcing Robolectric to allocate a distinct sandbox per worker. Skip for sandboxCount=1 so
    // the legacy single-sandbox bootstrap path stays bit-identical with pre-pool code.
    if (sandboxCount > 1) SandboxHoldingHints.workerIndex.set(workerIndex)
    try {
      val result = JUnitCore.runClasses(SandboxRunner::class.java)
      if (!result.wasSuccessful()) {
        for (failure in result.failures) {
          System.err.println(
            "RobolectricHost SandboxRunner[$workerIndex] failed: ${failure.message}"
          )
          failure.exception?.printStackTrace()
        }
      }
    } finally {
      if (sandboxCount > 1) SandboxHoldingHints.workerIndex.remove()
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
      // `this.javaClass.classLoader` is platform-typed to `ClassLoader?`, but
      // every JVM-loaded class except primitives / array stubs has a non-null
      // loader (we'd be looking at the bootstrap loader's `null` only for
      // primitive `Class` objects, which don't apply here — `SandboxRunner` is
      // loaded by Robolectric's `InstrumentingClassLoader`). Assert non-null
      // so the new strict Kotlin platform-type checks stop warning.
      // SANDBOX-POOL.md — registerSandbox CASs the first free slot and returns its index. For
      // sandboxCount=1 this resolves to slot 0 (aliased to the legacy top-level fields), so the
      // single-sandbox path stays bit-identical with the pre-pool registration.
      val slotIndex = DaemonHostBridge.registerSandbox(this.javaClass.classLoader!!)
      val slot = DaemonHostBridge.slot(slotIndex)

      while (!DaemonHostBridge.shutdown.get()) {
        val request = slot.requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
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
            List::class.java,
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
