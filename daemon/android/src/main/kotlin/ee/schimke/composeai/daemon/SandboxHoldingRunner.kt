package ee.schimke.composeai.daemon

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * Robolectric runner that excludes [ee.schimke.composeai.daemon.bridge] from
 * instrumentation so its static state (the request queue, result map, and
 * shutdown flag) is shared identically between the test thread and the
 * sandbox thread.
 *
 * See [ee.schimke.composeai.daemon.bridge.DaemonHostBridge] for the rationale
 * — without this rule, Robolectric's `InstrumentingClassLoader` re-loads
 * `ee.schimke.composeai.daemon.*` classes in the sandbox, producing two
 * independent copies of the static handoff state.
 *
 * **B2.0 — disposable user-class loader.** When `composeai.daemon.userClassPackages` is set
 * (colon-delimited list of user-module package prefixes — emitted by the Gradle plugin's launch
 * descriptor when known, otherwise unset), each prefix is registered as `doNotAcquirePackage` so
 * Robolectric's `InstrumentingClassLoader` defers loading those classes to the parent
 * (system-classloader) chain. The disposable child [java.net.URLClassLoader] in
 * `UserClassLoaderHolder` then resolves them against the user's `build/intermediates/...` URLs.
 * Without an explicit packages list the v1 implementation relies on the child-first delegation
 * inside [UserClassLoaderHolder]'s `ChildFirstURLClassLoader` to win against the parent — see
 * CLASSLOADER.md for the trade-off discussion.
 *
 * **Sandbox pool (SANDBOX-POOL.md).** When [SandboxHoldingHints.workerIndex] is set on the worker
 * thread that constructs this runner, [createClassLoaderConfig] adds a synthetic per-runner
 * discriminator so each pool worker's [InstrumentationConfiguration] differs and Robolectric's
 * sandbox cache builds a fresh sandbox per worker. Without this, multi-worker hosts share a
 * single cached sandbox (the cache key would be identical) and concurrent renders queue on one
 * single-thread executor.
 */
class SandboxHoldingRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

  /**
   * Snapshot of the worker-index hint at construction time. The ThreadLocal is set on the pool
   * worker thread before `JUnitCore.runClasses` instantiates the runner; capture it now because
   * Robolectric subsequently invokes [createClassLoaderConfig] from at least two different
   * threads (the worker thread initially, then the sandbox's main thread later) and ThreadLocal
   * would silently miss on the latter — collapsing the cache to a single shared sandbox. Verified
   * empirically with a probe on Robolectric 4.16.1 (see SANDBOX-POOL.md "Layer 2 — empirical
   * finding").
   *
   * Null when the runner is constructed on a thread without the hint set — i.e., the legacy
   * single-sandbox path. In that case the discriminator is not applied, preserving cache hits
   * across runs.
   */
  private val poolWorkerIndex: Int? = SandboxHoldingHints.workerIndex.get()

  override fun createClassLoaderConfig(method: org.junit.runners.model.FrameworkMethod):
    InstrumentationConfiguration {
    val builder =
      InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
        .doNotAcquirePackage("ee.schimke.composeai.daemon.bridge")
    // SANDBOX-POOL.md (Layer 2) — pool discriminator. Two interlocking subtleties:
    //
    //   1. Use `doNotAcquireClass`, not `doNotAcquirePackage`. Robolectric's
    //      [InstrumentationConfiguration.equals] checks `classesToNotAcquire` but NOT
    //      `packagesToNotAcquire` — verified empirically via `javap -c` on Robolectric 4.16.1.
    //      A package-level discriminator silently collides on the cache key.
    //   2. Read the snapshot, NOT the ThreadLocal. Robolectric calls this method twice for one
    //      runner — first on the worker thread, then on the sandbox's main thread — and
    //      ThreadLocal.get on the second call returns null (different thread). Snapshotting in
    //      the constructor (which runs on the worker thread under JUnitCore.runClasses) keeps the
    //      discriminator stable across both calls. The runner instance's identity hash is the
    //      discriminator value so per-runner configs stay distinct.
    //
    // The synthetic class name never matches a real class — it exists purely to break the cache
    // key.
    if (poolWorkerIndex != null) {
      builder.doNotAcquireClass(
        "composeai.sandbox.uniq.Runner${System.identityHashCode(this)}"
      )
    }
    // B2.0: optional user-package exclusion. Empty when sysprop is unset; existing in-process
    // tests that rely on the default sandbox-classpath path are unaffected.
    val raw = System.getProperty("composeai.daemon.userClassPackages")
    if (!raw.isNullOrBlank()) {
      raw.split(java.io.File.pathSeparator)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { builder.doNotAcquirePackage(it) }
    }
    return builder.build()
  }

  /**
   * Conditionally registers [ShadowAmbientLifecycleObserver]. The shadow's
   * `@Implements(AmbientLifecycleObserver::class)` value forces Robolectric's
   * [org.robolectric.internal.bytecode.ShadowMap.obtainShadowInfo] to resolve
   * `androidx.wear.ambient.AmbientLifecycleObserver` via reflection during sandbox bootstrap; on
   * non-Wear consumers (e.g. `:samples:android`, plain Android apps) that class isn't on the
   * runtime classpath and the resolution throws `TypeNotPresentException`, killing the daemon
   * before any render runs (issue: PR #891 regression hit by run-agent-audit-samples.py).
   *
   * Returning the shadow only when wear-ambient is loadable keeps the existing wear path intact
   * while leaving non-wear consumers untouched. The same gate is mirrored on the engine side in
   * [ee.schimke.composeai.daemon.RobolectricHost] for `AmbientPreviewOverrideExtension` /
   * `AmbientInputDispatchObserver` instantiation.
   */
  override fun getExtraShadows(method: FrameworkMethod): Array<Class<*>> =
    if (isWearAmbientAvailable(javaClass.classLoader)) {
      arrayOf(ShadowAmbientLifecycleObserver::class.java)
    } else {
      emptyArray()
    }
}

/**
 * Returns `true` when `androidx.wear.ambient.AmbientLifecycleObserver` is on the supplied
 * classloader. Used by [SandboxHoldingRunner] (host loader) and the daemon's `SandboxRunner`
 * (sandbox loader) to gate ambient connector registration on the consumer's classpath shape, so a
 * plain Android consumer doesn't pull `:data-ambient-connector` classes through reflection paths
 * that need the wear AAR.
 */
internal fun isWearAmbientAvailable(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName("androidx.wear.ambient.AmbientLifecycleObserver", false, effective)
    true
  } catch (_: ClassNotFoundException) {
    false
  } catch (_: NoClassDefFoundError) {
    false
  }
}

/**
 * Cross-thread hints consumed by [SandboxHoldingRunner] during sandbox bootstrap. Lives at file
 * scope (not on a companion) so set/get is cheap and readable without instantiating a runner.
 *
 * Used by SANDBOX-POOL.md (Layer 2) — the host's pool worker thread sets [workerIndex] before
 * calling `JUnitCore.runClasses` so each pool worker bootstraps a distinct Robolectric sandbox
 * (otherwise the sandbox cache key — which includes the [InstrumentationConfiguration] — matches
 * across workers and the pool collapses to a single cached sandbox).
 */
internal object SandboxHoldingHints {
  /**
   * Worker index hint. Non-null on the pool worker thread between
   * [ee.schimke.composeai.daemon.RobolectricHost.runJUnit]'s `set` and `remove` calls; null
   * otherwise so the pre-pool single-sandbox bootstrap path keeps its historical
   * [InstrumentationConfiguration] (and therefore Robolectric's sandbox cache hits across runs).
   *
   * Read **only** at runner construction (snapshotted into [SandboxHoldingRunner.poolWorkerIndex]).
   * Reading it elsewhere — particularly inside [SandboxHoldingRunner.createClassLoaderConfig] —
   * silently misses on the second invocation (which Robolectric makes on the sandbox's main
   * thread, where the ThreadLocal isn't set), so the discriminator vanishes and the cache key
   * collapses across workers.
   */
  val workerIndex: ThreadLocal<Int?> = ThreadLocal()
}
