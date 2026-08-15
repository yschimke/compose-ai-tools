package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The metadata **gate** on `preview/rows` for the Android daemon (issue #3749).
 *
 * On Android, enumerating a `@PreviewParameter` provider means a request/reply round-trip into the
 * Robolectric sandbox — the provider lives on the slot's child classloader and its values touch
 * Android APIs that are only real in there. That is fine for the handful of previews that declare a
 * provider and absurd for the hundreds that don't, and a client is expected to call this for
 * *everything* it lists. So the host resolves discovery metadata first and returns an empty list
 * without ever allocating a request.
 *
 * These tests assert the gate as an **absence of work**, not just an empty result: they run against
 * an unstarted host, where any enqueued request would sit unconsumed, and check that slot 0's
 * request queue is still empty afterwards. The positive path (a real sandbox round-trip returning
 * real labels) can't run here — it needs a booted sandbox — so it lives in the harness scenario
 * `PreviewParameterAndroidRealModeTest.preview rows enumerates inside the sandbox…`.
 */
class RobolectricRowEnumerationTest {

  private fun entry(id: String, provider: String?) =
    PreviewManifestEntry(
      id = id,
      className = "com.example.PreviewsKt",
      functionName = "Screen",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      previewParameterProviderClassName = provider,
    )

  private fun router(vararg entries: PreviewManifestEntry) =
    PreviewManifestRouter(PreviewManifest(previews = entries.toList()))

  @Before
  fun drainBridge() {
    DaemonHostBridge.slot(0).requests.clear()
  }

  @Test
  fun `a preview with no provider answers empty without enqueuing a sandbox request`() {
    val rows = router(entry("Plain", null)).previewParameterRows("Plain")

    assertEquals(emptyList<PreviewParameterRow>(), rows)
    assertTrue(
      "the gate must not wake the sandbox for an ordinary preview",
      DaemonHostBridge.slot(0).requests.isEmpty(),
    )
  }

  /** A blank FQN is the same as none — discovery writes `""` rather than null in some manifests. */
  @Test
  fun `a blank provider is treated as no provider`() {
    assertEquals(
      emptyList<PreviewParameterRow>(),
      router(entry("Blank", "  ")).previewParameterRows("Blank"),
    )
    assertTrue(DaemonHostBridge.slot(0).requests.isEmpty())
  }

  /**
   * Review follow-up. `INTERACTIVE_SLOT_INDEX` is 0 (since #3072), the same in-process sandbox
   * enumeration needs — and slots 1..N-1 are worker JVMs with no ParameterRows lane, so there is
   * nowhere else to send it. While a session is held, that slot's loop sits in
   * `runHeldInteractiveSession` draining `interactiveCommands` and never polls `requests`.
   *
   * Enqueueing anyway would block for the full 30s timeout, and because this call is synchronous on
   * the JSON-RPC reader thread it would also stop the `interactive/stop` that releases the slot
   * from being read at all — a deterministic stall, not a slow answer. So it must refuse
   * immediately, and leave nothing on the queue for the session's loop to trip over when it
   * resumes.
   */
  @Test
  fun `a held interactive session makes enumeration refuse rather than stall`() {
    val router = router(entry("Screen", "com.example.TintProvider"))
    router.pinInteractiveSlotForTest("android-stream-1")
    try {
      val start = System.nanoTime()
      val failure = runCatching { router.previewParameterRows("Screen") }.exceptionOrNull()
      val elapsedMs = (System.nanoTime() - start) / 1_000_000

      assertTrue(
        "expected IllegalStateException naming the held session, got $failure",
        failure is IllegalStateException && failure.message?.contains("android-stream-1") == true,
      )
      assertTrue(
        "must fail fast, not wait out the timeout — took ${elapsedMs}ms",
        elapsedMs < 5_000,
      )
      assertTrue(
        "a refused enumeration must not leave a request for the held loop to find",
        DaemonHostBridge.slot(0).requests.isEmpty(),
      )
    } finally {
      router.pinInteractiveSlotForTest(null)
    }
  }

  @Test
  fun `an unknown previewId is rejected before any sandbox work`() {
    val failure = runCatching {
      router(entry("Screen", null)).previewParameterRows("Nope")
    }
      .exceptionOrNull()

    assertTrue(
      "expected IllegalArgumentException naming the id, got $failure",
      failure is IllegalArgumentException && failure.message?.contains("Nope") == true,
    )
    assertTrue(DaemonHostBridge.slot(0).requests.isEmpty())
  }
}
