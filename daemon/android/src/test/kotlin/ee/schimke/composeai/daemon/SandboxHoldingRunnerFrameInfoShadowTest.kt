package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ShadowPausedClockHardwareRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod

/**
 * Locks the daemon sandbox's registration of [ShadowPausedClockHardwareRenderer] through
 * [SandboxHoldingRunner.getExtraShadows].
 *
 * The shadow is what keeps a held session's render-thread animations on the clock the daemon
 * advances rather than on host wall-clock time (issue #4549 — Robolectric 4.17-beta-3 started
 * translating `FrameInfo` timestamps into the host domain unconditionally). Dropping the
 * registration does not fail a build: it silently returns the live lane to a press whose ripple a
 * viewer never sees animate, which is the regression `AndroidRippleFrameTest` and
 * `LivePressRippleTest` measure in pixels. Those two are the real acceptance check; this test is
 * the cheap one that says *why* they went red when someone deletes a line here.
 *
 * Same exposer pattern as [SandboxHoldingRunnerFontShadowTest] — construct the runner with a dummy
 * test class and read the resolved shadow set back through a subclass that widens the `protected`
 * [org.robolectric.RobolectricTestRunner.getExtraShadows].
 */
class SandboxHoldingRunnerFrameInfoShadowTest {

  @Test
  fun registersPausedClockHardwareRendererShadow() {
    val runner = ExposedRunner(DummyTest::class.java)
    val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
    val shadows = runner.exposedExtraShadows(method).map { it.name }
    assertTrue(
      "daemon sandbox must register ShadowPausedClockHardwareRenderer so a held session's " +
        "render-thread animations are paced by the simulated clock instead of host wall-clock " +
        "time (#4549); got $shadows",
      shadows.contains(ShadowPausedClockHardwareRenderer::class.java.name),
    )
  }

  /** Exposes the `protected` [org.robolectric.RobolectricTestRunner.getExtraShadows]. */
  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)
  }

  // Minimal valid test class for the `RobolectricTestRunner(Class)` constructor, and deliberately
  // not `@RunWith(SandboxHoldingRunner::class)` — reading `getExtraShadows` back needs no sandbox
  // bootstrap. Mirrors [SandboxHoldingRunnerFontShadowTest.DummyTest].
  class DummyTest {
    @Test fun stub() = Unit
  }
}
