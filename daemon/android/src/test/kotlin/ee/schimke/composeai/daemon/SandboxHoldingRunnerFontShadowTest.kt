package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ShadowFontsContractCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod

/**
 * Locks the daemon sandbox's registration of [ShadowFontsContractCompat] through
 * [SandboxHoldingRunner.getExtraShadows].
 *
 * Without it the daemon render path (`bundle pack` / serve, including `--with-semantics` — the path
 * `design-artifacts` uses) hits the real GMS Fonts provider, which is absent under Robolectric, so
 * every `Font(GoogleFont(...))` silently rendered in the platform fallback (Roboto) and — because
 * the shadow is the only place that records a fallback — the render still "succeeded". The one-shot
 * `bundle render` path always got this shadow via its synthesized `robolectric.properties`; this
 * test guards the daemon parity so the gap can't silently reopen.
 *
 * Mirrors [SandboxHoldingRunnerApplicationOverrideTest]'s exposer pattern: construct the runner with
 * a dummy test class and read the resolved shadow set back via a subclass that widens the
 * `protected` [org.robolectric.RobolectricTestRunner.getExtraShadows].
 */
class SandboxHoldingRunnerFontShadowTest {

  @Test
  fun registersDownloadableFontShadow() {
    val runner = ExposedRunner(DummyTest::class.java)
    val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
    val shadows = runner.exposedExtraShadows(method).map { it.name }
    assertTrue(
      "daemon sandbox must register ShadowFontsContractCompat so downloadable GoogleFonts resolve " +
        "(or record a fallback for the fatal-on-fallback gate) instead of silently falling back to " +
        "Roboto; got $shadows",
      shadows.contains(ShadowFontsContractCompat::class.java.name),
    )
  }

  /** Exposes the `protected` [org.robolectric.RobolectricTestRunner.getExtraShadows]. */
  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)
  }

  // A minimal valid test class for the `RobolectricTestRunner(Class)` constructor the exposer uses.
  // Deliberately NOT `@RunWith(SandboxHoldingRunner::class)`: we only read `getExtraShadows` back
  // (no sandbox bootstrap), so it needn't — and shouldn't — spin up a Robolectric sandbox, which
  // needs the CI's Java 21 toolchain (SDK 36).
  class DummyTest {
    @Test fun stub() = Unit
  }
}
