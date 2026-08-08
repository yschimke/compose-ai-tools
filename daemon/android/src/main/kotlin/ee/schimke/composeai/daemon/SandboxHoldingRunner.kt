package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ShadowFontsContractCompat
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * Robolectric runner that excludes [ee.schimke.composeai.daemon.bridge] from instrumentation so its
 * static state (the request queue, result map, and shutdown flag) is shared identically between the
 * test thread and the sandbox thread.
 *
 * See [ee.schimke.composeai.daemon.bridge.DaemonHostBridge] for the rationale — without this rule,
 * Robolectric's `InstrumentingClassLoader` re-loads `ee.schimke.composeai.daemon.*` classes in the
 * sandbox, producing two independent copies of the static handoff state.
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
 * **Sandbox pool (SANDBOX-POOL.md).** This runner carries no per-worker cache-key discriminator any
 * more (issue #3072). It had one — a synthetic `doNotAcquireClass` that forced Robolectric to build
 * a distinct sandbox per pool worker in one JVM — and that is exactly the arrangement Robolectric's
 * native runtime cannot survive: the second sandbox's `Typeface` init fails against an already-
 * loaded `libandroid_runtime.so` and the process dies. The pool now scales by processes, one
 * sandbox each, so every runner in a JVM shares one [InstrumentationConfiguration] and hits
 * Robolectric's sandbox cache — which is what lets sequential hosts in a test suite reuse one
 * sandbox instead of building a second.
 */
open class SandboxHoldingRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

  /**
   * Mirrors the `application=android.app.Application` line that
   * [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask] writes to the consumer's
   * `ee/schimke/composeai/renderer/robolectric.properties` for the Gradle `composePreviewRender`
   * path. That properties file is package-scoped — Robolectric only finds it for tests under
   * `ee.schimke.composeai.renderer`, so the daemon's [RobolectricHost.SandboxRunner] (package
   * `ee.schimke.composeai.daemon`) never picks it up. Without an explicit override here,
   * Robolectric falls back to the consumer's `AndroidManifest.xml` and invokes the production
   * `Application` subclass, defeating the renderer's "previews shouldn't run app-lifecycle init"
   * contract.
   *
   * Setting the global config's `application` field is equivalent to the properties-file line: it
   * supplies the default that gets merged with `@Config` on the test class. The host loads
   * `android.app.Application` via the daemon-classpath's `android.jar`; the sandbox re-resolves it
   * by FQN through its instrumenting loader, same as Robolectric's own internals.
   *
   * `composeai.daemon.useConsumerApplication=true` (sourced from
   * `composePreview.useConsumerApplication` via
   * [ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask]) restores the historical "Robolectric
   * reads the manifest" behaviour for previews that genuinely depend on consumer-Application init
   * (Koin/Hilt seeded in `onCreate`, etc.). Consumers opting in are responsible for making their
   * `Application.onCreate` Robolectric-safe — multiple sandbox workers run in the same JVM, so any
   * process-global side effect (`URL.setURLStreamHandlerFactory`, static `init {}` blocks that
   * throw on second invocation) must be idempotent.
   *
   * `buildGlobalConfig` is `@Deprecated` in Robolectric 4.16 in favour of a `Configurer` extension,
   * but it remains the documented seam for "default for tests in this runner" overrides and is
   * still invoked by `RobolectricTestRunner.getConfig`. Migrating to a `Configurer` would require
   * registering it via `META-INF/services` and rebuilding the merge ordering by hand; the
   * deprecated hook does exactly what the consumer-side `robolectric.properties` line does without
   * that churn.
   */
  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun buildGlobalConfig(): Config {
    val parent = super.buildGlobalConfig()
    val useConsumerApp =
      System.getProperty("composeai.daemon.useConsumerApplication", "false").toBoolean()
    if (useConsumerApp) return parent
    return Config.Builder(parent).setApplication(android.app.Application::class.java).build()
  }

  override fun createClassLoaderConfig(
    method: org.junit.runners.model.FrameworkMethod
  ): InstrumentationConfiguration {
    val builder =
      InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
        .doNotAcquirePackage("ee.schimke.composeai.daemon.bridge")
    // Coil 2's `AsyncImagePainter` lives in a plain library package, which Robolectric does NOT
    // instrument by default — and an uninstrumented class can't be shadowed. Instrument it so
    // [ee.schimke.composeai.renderer.ShadowAsyncImagePainter] (registered in `getExtraShadows`
    // below) can force `isPreview` off and let coil-backed previews actually load their images
    // (issue #2952). The Gradle `composePreviewRender` path gets the equivalent from the
    // `instrumentedPackages=coil.compose` line in its generated `robolectric.properties`. Inert
    // when the consumer has no coil: there is simply nothing in that package to instrument.
    if (isCoil2Available(javaClass.classLoader)) {
      builder.addInstrumentedPackage("coil.compose")
    }
    // Wear's `TimeText` reads the wall clock through `System.currentTimeMillis()` in ONE class,
    // `androidx.wear.compose.materialcore.ResourcesKt` (both Material and Material3 route there).
    // Robolectric only rewrites that call inside instrumented classes, so without this the clock a
    // Wear preview paints is the host's and the render diffs every minute (issue #3239).
    // `addInstrumentedPackage` matches class-name prefixes, so naming the class instruments exactly
    // it. Mirrors the `instrumentedPackages=` line the Gradle path's generated
    // `robolectric.properties` carries; [ee.schimke.composeai.renderer.PreviewClock] is the other
    // half, pinning the emulated clock the rewritten call now reads.
    if (isWearComposeMaterialCoreAvailable(javaClass.classLoader)) {
      builder.addInstrumentedPackage(WEAR_MATERIAL_CORE_RESOURCES)
    }
    // B2.0: optional user-package exclusion. Empty when sysprop is unset; existing in-process
    // tests that rely on the default sandbox-classpath path are unaffected.
    val raw = System.getProperty("composeai.daemon.userClassPackages")
    if (!raw.isNullOrBlank()) {
      raw
        .split(java.io.File.pathSeparator)
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
   * 2. **This gate still hides the shadow when wear-ambient isn't loadable**, so the shadow class —
   *    which references `AmbientLifecycleObserver` in field and method-parameter types — is only
   *    ever returned to Robolectric on classpaths where its symbolic links can be resolved.
   *
   * The same gate is mirrored on the engine side in [ee.schimke.composeai.daemon.RobolectricHost]
   * for `AmbientPreviewOverrideExtension` / `AmbientInputDispatchObserver` instantiation — those
   * concrete classes call into `AmbientStateController` which directly imports wear API.
   */
  override fun getExtraShadows(method: FrameworkMethod): Array<Class<*>> {
    val shadows = mutableListOf<Class<*>>()
    // Wear ambient shadow — only when the wear AAR is on the classpath (issue #1244) AND
    // the daemon's connector module is also available. The connector
    // (ShadowAmbientLifecycleObserver
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
    // Wear one-handed-gesture SDK-manager shadow — only when the wear-compose gesture API is on the
    // classpath. Makes the framework's registration + indicator pipeline observable under the render
    // (arms via `GestureStateController.detectionArmed`), so a raw `Modifier.oneHandedGesture` app is
    // surfaced in `compose/gestures` and its hint can be shown. Same NoClassDefFoundError guard as
    // ambient for artifacts predating the gesture connector.
    if (isWearGestureAvailable(javaClass.classLoader)) {
      try {
        shadows += ShadowSdkGestureInputManager::class.java
      } catch (_: NoClassDefFoundError) {
        // connector not on classpath — skip gesture shadow
      }
    }
    // Runtime-permissions tracker shadow. Always registered — `android.content.ContextWrapper`
    // is core Android, present on every consumer classpath, so the symbolic links the shadow
    // declares always resolve. The shadow forwards `checkPermission(...)` to the real
    // implementation and records the query in `PermissionsController` for the
    // `compose/permissions` data product.
    shadows += ShadowContextWrapperPermissionTracker::class.java
    // Downloadable-GoogleFont shadow. Always registered — androidx.core's `FontsContractCompat` is
    // on every Compose consumer classpath, so the `@Implements` link always resolves (same safety as
    // the permission-tracker shadow above). Without it the daemon render path (`bundle pack` /
    // serve, incl. `--with-semantics`) hits the real GMS Fonts provider — absent under Robolectric —
    // so every `Font(GoogleFont(...))` silently rendered in the platform fallback (Roboto). The
    // one-shot `bundle render` path got this shadow via its synthesized `robolectric.properties`, but
    // the daemon never did. With it, a face resolves from the shared cache
    // (`composeai.fonts.cacheDir`) / a live fetch, and a genuinely unresolvable face is recorded for
    // `RenderEngine`'s fatal-on-fallback gate instead of vanishing.
    shadows += ShadowFontsContractCompat::class.java
    // Coil 2 preview-branch shadow. Gated on coil 2 actually being on the classpath purely to keep
    // the sandbox's instrumentation config honest — the shadow itself declares its target by
    // `className`, so unlike the Wear shadows above it carries no unresolvable symbolic links and
    // could not throw here. Pairs with the `addInstrumentedPackage("coil.compose")` call in
    // `createClassLoaderConfig`; both are needed for the shadow to take effect. See issue #2952.
    if (isCoil2Available(javaClass.classLoader)) {
      shadows += ee.schimke.composeai.renderer.ShadowAsyncImagePainter::class.java
    }
    // Wear clock shadow — same shape and same gating rationale as the coil one above, and pairs with
    // the `addInstrumentedPackage(WEAR_MATERIAL_CORE_RESOURCES)` call in `createClassLoaderConfig`;
    // both are needed for it to take effect. Without it a daemon render of a clock-bearing Wear
    // screen paints the host wall clock and disagrees with the batch render's fixed `10:10`
    // (issue #3239).
    if (isWearComposeMaterialCoreAvailable(javaClass.classLoader)) {
      shadows += ee.schimke.composeai.renderer.ShadowWearTimeSource::class.java
    }
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
 * Returns `true` when the Wear one-handed-gesture API
 * (`androidx.wear.compose.material3.onehandedgesture.OneHandedGestureModifierKt`, added in
 * `wear-compose 1.7.0-alpha`) is on the supplied classloader. Gates `:data-gestures-connector`
 * registration so a plain-Android consumer — or a Wear consumer still on `wear-compose 1.6.x` —
 * doesn't drive `GestureOverrideExtension`'s composition into unresolved gesture types.
 */
internal fun isWearGestureAvailable(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName(
      "androidx.wear.compose.material3.onehandedgesture.OneHandedGestureModifierKt",
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
 * The single Wear class whose `currentTimeMillis()` both Wear Material and Wear Material3 `TimeText`
 * read. Instrumented (see [SandboxHoldingRunner.createClassLoaderConfig] and the Gradle path's
 * generated `robolectric.properties`) so Robolectric rewrites its `System.currentTimeMillis()` call
 * into the emulated clock [ee.schimke.composeai.renderer.PreviewClock] pins.
 */
internal const val WEAR_MATERIAL_CORE_RESOURCES: String =
  "androidx.wear.compose.materialcore.ResourcesKt"

/**
 * Returns `true` when [WEAR_MATERIAL_CORE_RESOURCES] is on the supplied classloader — i.e. the
 * consumer is a Wear app whose `TimeText` would otherwise paint the host's wall clock. Mirrors
 * [isCoil2Available]: instrumenting a class that isn't there costs nothing, but probing keeps the
 * sandbox config honest about what it actually rewrote.
 */
internal fun isWearComposeMaterialCoreAvailable(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName(WEAR_MATERIAL_CORE_RESOURCES, false, effective)
    true
  } catch (_: ClassNotFoundException) {
    false
  } catch (_: NoClassDefFoundError) {
    false
  }
}

/**
 * Returns `true` when coil 2's Compose integration (`coil.compose.AsyncImagePainter`) is on the
 * supplied classloader. Gates the `coil.compose` instrumentation + shadow registration that make
 * coil-backed previews resolve instead of painting a null placeholder — see
 * [ee.schimke.composeai.renderer.ShadowAsyncImagePainter]. Coil 3 needs neither (it exposes
 * `LocalAsyncImagePreviewHandler` as a supported hook), so only the 2.x package is probed.
 */
internal fun isCoil2Available(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName("coil.compose.AsyncImagePainter", false, effective)
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
 * instantiating any of them on a non-Remote-Compose classpath raises `NoClassDefFoundError` at the
 * lazy-init line. Mirrors [isWearAmbientAvailable] for the Wear ambient connector.
 */
internal fun isRemoteComposeAvailable(loader: ClassLoader?): Boolean {
  val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return false
  return try {
    Class.forName("androidx.compose.remote.creation.compose.action.HostAction", false, effective)
    true
  } catch (_: ClassNotFoundException) {
    false
  } catch (_: NoClassDefFoundError) {
    false
  }
}

