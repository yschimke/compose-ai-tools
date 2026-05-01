package ee.schimke.composeai.daemon

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
 */
class SandboxHoldingRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

  override fun createClassLoaderConfig(method: org.junit.runners.model.FrameworkMethod):
    InstrumentationConfiguration {
    val builder =
      InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
        .doNotAcquirePackage("ee.schimke.composeai.daemon.bridge")
    // SANDBOX-POOL.md (Layer 2) — pool worker index hint. When set, force a unique sandbox by
    // adding a synthetic discriminator. Read here on the calling worker thread because
    // `createClassLoaderConfig` runs synchronously inside `JUnitCore.runClasses`, before the
    // sandbox bootstrap proper.
    //
    // **Why doNotAcquireClass and not doNotAcquirePackage.** Robolectric's
    // [InstrumentationConfiguration.equals] checks `classesToNotAcquire` but NOT
    // `packagesToNotAcquire` — verified empirically via `javap -c` on Robolectric 4.16.1, see
    // SANDBOX-POOL.md "Layer 2 — empirical finding". Using a per-worker package-level
    // discriminator silently collides on the cache key and Robolectric returns the SAME cached
    // sandbox for every worker; using a class-level discriminator differs in equals as expected.
    // The class name itself is synthetic — never matches a real class — and only exists to break
    // the cache key.
    val workerIdx = SandboxHoldingHints.workerIndex.get()
    if (workerIdx != null) {
      builder.doNotAcquireClass("composeai.sandbox.uniq.Worker$workerIdx")
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
   */
  val workerIndex: ThreadLocal<Int?> = ThreadLocal()
}
