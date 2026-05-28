package ee.schimke.composeai.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the daemon's [SandboxHoldingRunner.buildGlobalConfig] override that mirrors the
 * `application=android.app.Application` line [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask]
 * writes for the consumer's `composePreviewRender` Test path. Without this override the daemon
 * sandbox falls back to the manifest-declared `Application`, whose `onCreate` runs once per
 * sandbox worker — and any process-global side effect (e.g. `URL.setURLStreamHandlerFactory`)
 * throws on worker 1+, taking the pool down.
 *
 * The dummy test class supplies the `RobolectricTestRunner(Class)` constructor with a valid
 * test class; we read the resolved [Config] back via the protected `buildGlobalConfig` exposer.
 */
class SandboxHoldingRunnerApplicationOverrideTest {

  @After
  fun clearSysprop() {
    System.clearProperty("composeai.daemon.useConsumerApplication")
  }

  @Test
  fun defaultPinsAndroidAppApplication() {
    System.clearProperty("composeai.daemon.useConsumerApplication")
    val runner = ExposedRunner(DummyTest::class.java)
    // FQN comparison (not class-identity) because the runner is constructed under Robolectric's
    // shadow-aware classloader chain; `android.app.Application::class.java` resolved here and the
    // class the runner installs into the Config may originate from different `Class<?>` instances
    // even though they refer to the same type. The renderer cares about the FQN match — that's
    // what Robolectric's manifest-vs-explicit dispatch compares.
    assertEquals(
      "Default daemon config should pin android.app.Application so consumer onCreate is skipped",
      "android.app.Application",
      runner.exposedGlobalConfig().application.java.name,
    )
  }

  @Test
  fun useConsumerApplicationFalseStillPinsStub() {
    System.setProperty("composeai.daemon.useConsumerApplication", "false")
    val runner = ExposedRunner(DummyTest::class.java)
    assertEquals("android.app.Application", runner.exposedGlobalConfig().application.java.name)
  }

  @Test
  fun useConsumerApplicationTrueRestoresParentDefault() {
    System.setProperty("composeai.daemon.useConsumerApplication", "true")
    val runner = ExposedRunner(DummyTest::class.java)
    // Opt-in must NOT pin android.app.Application — the consumer's manifest Application wins.
    // Robolectric's sentinel default ("look at manifest") is its own class
    // (`org.robolectric.DefaultTestLifecycle$$DefaultApplication$$` family), not
    // `android.app.Application`, so checking the FQN is enough to lock the opt-in path.
    assertNotEquals(
      "useConsumerApplication=true must not pin the stub Application",
      "android.app.Application",
      runner.exposedGlobalConfig().application.java.name,
    )
  }

  /** Exposes the `protected` [RobolectricTestRunner.buildGlobalConfig] for the assertions above. */
  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedGlobalConfig(): Config = buildGlobalConfig()
  }

  @RunWith(SandboxHoldingRunner::class)
  class DummyTest {
    @Test fun stub() = Unit
  }
}
