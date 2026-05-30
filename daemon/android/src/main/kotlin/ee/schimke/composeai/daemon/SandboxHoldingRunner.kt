package ee.schimke.composeai.daemon

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
open class SandboxHoldingRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

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

  /**
   * Mirrors the `application=android.app.Application` line that
   * [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask] writes to the consumer's
   * `ee/schimke/composeai/renderer/robolectric.properties` for the Gradle `composePreviewRender`
   * path. That properties file is package-scoped — Robolectric only finds it for tests under
   * `ee.schimke.composeai.renderer`, so the daemon's [RobolectricHost.SandboxRunner] (package
   * `ee.schimke.composeai.daemon`) never picks it up. Without an explicit override here, Robolectric
   * falls back to the consumer's `AndroidManifest.xml` and invokes the production `Application`
   * subclass, defeating the renderer's "previews shouldn't run app-lifecycle init" contract.
   *
   * Setting the global config's `application` field is equivalent to the properties-file line: it
   * supplies the default that gets merged with `@Config` on the test class. The host loads
   * `android.app.Application` via the daemon-classpath's `android.jar`; the sandbox re-resolves it
   * by FQN through its instrumenting loader, same as Robolectric's own internals.
   *
   * `composeai.daemon.useConsumerApplication=true` (sourced from `composePreview.useConsumerApplication`
   * via [ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask]) restores the historical "Robolectric
   * reads the manifest" behaviour for previews that genuinely depend on consumer-Application init
   * (Koin/Hilt seeded in `onCreate`, etc.). Consumers opting in are responsible for making their
   * `Application.onCreate` Robolectric-safe — multiple sandbox workers run in the same JVM, so any
   * process-global side effect (`URL.setURLStreamHandlerFactory`, static `init {}` blocks that throw
   * on second invocation) must be idempotent.
   *
   * `buildGlobalConfig` is `@Deprecated` in Robolectric 4.16 in favour of a `Configurer` extension,
   * but it remains the documented seam for "default for tests in this runner" overrides and is still
   * invoked by `RobolectricTestRunner.getConfig`. Migrating to a `Configurer` would require
   * registering it via `META-INF/services` and rebuilding the merge ordering by hand; the deprecated
   * hook does exactly what the consumer-side `robolectric.properties` line does without that churn.
   */
  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun buildGlobalConfig(): Config {
    val parent = super.buildGlobalConfig()
    val useConsumerApp =
      System.getProperty("composeai.daemon.useConsumerApplication", "false").toBoolean()
    if (useConsumerApp) return parent
    return Config.Builder(parent).setApplication(android.app.Application::class.java).build()
  }

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
   * Conditionally registers [ShadowAmbientLifecycleObserver].
   *
   * Two interlocking precautions keep non-Wear consumers safe (issue #1244, PR #891 regression hit
   * by `run-agent-audit-samples.py`):
   * 1. **The shadow uses `@Implements(className = …)` instead of the `value = …` class-literal
   *    form**, so Robolectric's [org.robolectric.internal.bytecode.ShadowMap.obtainShadowInfo]
   *    reads the FQN as a `String` rather than dereferencing a deferred `Class<?>` proxy that the
   *    JVM annotation parser would resolve via the shadow's defining loader. That deferred
   *    resolution was the path that threw `TypeNotPresentException` mid-sandbox-bootstrap on Wear
   *    sample classpaths shipping the shadow next to a stale/mismatched wear AAR.
   * 2. **This gate still hides the shadow when wear-ambient isn't loadable**, so the shadow class
   *    — which references `AmbientLifecycleObserver` in field and method-parameter types — is
   *    only ever returned to Robolectric on classpaths where its symbolic links can be resolved.
   *
   * The same gate is mirrored on the engine side in [ee.schimke.composeai.daemon.RobolectricHost]
   * for `AmbientPreviewOverrideExtension` / `AmbientInputDispatchObserver` instantiation —
   * those concrete classes call into `AmbientStateController` which directly imports wear API.
   */
  override fun getExtraShadows(method: FrameworkMethod): Array<Class<*>> {
    val shadows = mutableListOf<Class<*>>()
    // Wear ambient shadow — only when the wear AAR is on the classpath (issue #1244) AND
    // the daemon's connector module is also available. The connector (ShadowAmbientLifecycleObserver
    // and friends) is bundled inside the extension artifact; when the artifact predates the
    // connector being added, the wear AAR check passes but the connector class is absent,
    // causing NoClassDefFoundError mid-sandbox-bootstrap. Catching here lets the daemon
    // limp along without ambient support rather than crashing all sandbox workers.
    if (isWearAmbientAvailable(javaClass.classLoader)) {
      try {
        shadows += ShadowAmbientLifecycleObserver::class.java
      } catch (_: NoClassDefFoundError) {
        // connector not on classpath — skip ambient shadow
      }
    }
    // Runtime-permissions tracker shadow. Always registered — `android.content.ContextWrapper`
    // is core Android, present on every consumer classpath, so the symbolic links the shadow
    // declares always resolve. The shadow forwards `checkPermission(...)` to the real
    // implementation and records the query in `PermissionsController` for the
    // `compose/permissions` data product.
    shadows += ShadowContextWrapperPermissionTracker::class.java
    return shadows.toTypedArray()
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
 * Returns `true` when `androidx.compose.remote.creation.compose.action.HostAction` is on the
 * supplied classloader. Used to gate `:data-remotecompose-connector` registration on the consumer
 * actually shipping the alpha `compose-remote` artifacts (`:samples:remotecompose` for the
 * reference setup) — the connector's own classes reference these alpha types directly, so
 * instantiating any of them on a non-Remote-Compose classpath raises `NoClassDefFoundError` at
 * the lazy-init line. Mirrors [isWearAmbientAvailable] for the Wear ambient connector.
 */
internal fun isRemoteComposeAvailable(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName(
      "androidx.compose.remote.creation.compose.action.HostAction",
      false,
      effective,
    )
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
