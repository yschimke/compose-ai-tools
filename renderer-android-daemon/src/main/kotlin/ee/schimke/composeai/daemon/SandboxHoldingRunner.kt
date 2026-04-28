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
