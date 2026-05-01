package ee.schimke.composeai.daemon

import org.junit.Test

/**
 * SANDBOX-POOL.md (Layer 2) — diagnostic test that intentionally tries to bring up two
 * Robolectric sandboxes in one JVM. On the empirical findings from
 * agent/sandbox-pool-multi-worker, slot 1's bootstrap stalls indefinitely while slot 0 is alive
 * in its hold-open poll loop.
 *
 * **This test is a one-shot diagnostic.** It runs with a short
 * `composeai.daemon.sandboxBootTimeoutMs` so the stall surfaces in seconds rather than minutes,
 * then [RobolectricHost.start]'s diagnostic dumps every thread's stack to stderr. The captured
 * dump is the load-bearing input for choosing between a Robolectric workaround and the
 * lower-level `Sandbox` API rewrite.
 *
 * Once the diagnosis is in hand and the fix path is chosen, this test will either become the
 * positive assertion ("two sandboxes serve distinct classloaders") or be deleted in favour of
 * the lower-level-API equivalent. It is **not** intended to be a long-lived part of the test
 * suite.
 *
 * The test is intentionally not annotated `@Ignore` — we want CI to surface the stall (and the
 * thread-dump artefact) so the diagnostic is reproducible and reviewable.
 */
class SandboxPoolDiagnosticTest {

  @Test
  fun captureStallDiagnosticForSandboxCountTwo() {
    System.setProperty(RobolectricHost.SANDBOX_BOOT_TIMEOUT_PROP, "60000")
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()
      // If start returns we have two booted sandboxes — record that as a passing observation
      // and exit. The thread dump is a fallback only.
      System.err.println(
        "SandboxPoolDiagnosticTest: start succeeded (sandboxCount=2). " +
          "If this is the first time you're seeing this, the Robolectric multi-live-sandbox path " +
          "now works — switch this diagnostic to a positive assertion."
      )
    } catch (t: Throwable) {
      // Surface the failure but don't fail the build — we want this run's primary value to be
      // the thread dump emitted from RobolectricHost.start. The diagnostic is meant to be read
      // from CI logs, not consumed as a pass/fail signal.
      System.err.println(
        "SandboxPoolDiagnosticTest: captured stall as expected: ${t::class.java.name}: " +
          t.message
      )
    } finally {
      runCatching { host.shutdown(timeoutMs = 5_000) }
      System.clearProperty(RobolectricHost.SANDBOX_BOOT_TIMEOUT_PROP)
    }
  }
}
