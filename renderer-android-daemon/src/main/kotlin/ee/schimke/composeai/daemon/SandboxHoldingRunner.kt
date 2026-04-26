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
 */
class SandboxHoldingRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

  override fun createClassLoaderConfig(method: org.junit.runners.model.FrameworkMethod):
    InstrumentationConfiguration {
    return InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
      .doNotAcquirePackage("ee.schimke.composeai.daemon.bridge")
      .build()
  }
}
