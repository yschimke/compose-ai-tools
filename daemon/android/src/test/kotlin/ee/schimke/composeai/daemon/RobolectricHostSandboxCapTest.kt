package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sandbox cap (#3072).
 *
 * Robolectric's native-graphics runtime binds to one classloader per JVM, so a second in-process
 * sandbox cannot boot — it dies in `Typeface.setSystemFontMap` and takes the JVM with it via
 * `SIGSEGV` in `libandroid_runtime.so`. [RobolectricHost] therefore clamps the requested count
 * rather than letting the pool half-start.
 *
 * These assertions are cheap on purpose: they read the effective count off a constructed host and
 * never call `start()`, so they cost nothing and can't themselves trip the crash they describe.
 */
class RobolectricHostSandboxCapTest {

  @Test
  fun requestingAPoolIsCappedToOneSandbox() {
    assertEquals(
      "a pooled request must be capped, not honoured",
      RobolectricHost.MAX_SANDBOXES_PER_JVM,
      RobolectricHost(requestedSandboxCount = 4).sandboxCount,
    )
  }

  @Test
  fun aSingleSandboxRequestIsUntouched() {
    assertEquals(1, RobolectricHost(requestedSandboxCount = 1).sandboxCount)
  }

  @Test
  fun zeroOrNegativeSandboxCountsStillFailFast() {
    for (bad in listOf(0, -1)) {
      val thrown =
        try {
          RobolectricHost(requestedSandboxCount = bad)
          null
        } catch (e: IllegalArgumentException) {
          e
        }
      assertTrue("requestedSandboxCount=$bad must be rejected", thrown != null)
    }
  }

  @Test
  fun cappedHostDoesNotAdvertiseInteractiveOrRecording() {
    // The capability flags are what `initialize` publishes, so they must follow the *effective*
    // count — advertising an interactive lane the host can't pin a slot for is what would strand
    // the panel waiting on a session that never arrives.
    val host = RobolectricHost(requestedSandboxCount = 2, previewSpecResolver = { null })
    assertFalse("interactive needs a real second sandbox", host.supportsInteractive)
    assertFalse("recording rides on the same held loop", host.supportsRecording)
    assertTrue(
      "and no interactive control kinds are offered",
      host.supportedInteractiveControlKinds.isEmpty(),
    )
  }
}
