package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import ee.schimke.composeai.daemon.bridge.InteractiveCommand
import ee.schimke.composeai.daemon.bridge.SandboxSlot
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wall-clock substitution coverage for [AndroidInteractiveSession.render]. The held-rule loop in
 * `RobolectricHost` advances the held composition's `mainClock` by whatever `advanceTimeMs` the
 * Render command carries (defaulting to 32ms when null). Live-preview callers
 * (`JsonRpcServer.submitInteractiveRenderAsync`) leave the field null, so the historical 32ms
 * default produced animations that walked ~6× slower than wall-clock when each Robolectric capture
 * took ~200ms. The session-level substitution covered here keeps animations on real time:
 *
 *  * **Null advance, first render** → floored at the recompose-settle window so the initial
 *    capture still flushes the post-`setContent` recomposition pass.
 *  * **Null advance, subsequent render** → wall-clock delta since the previous render, clamped
 *    into `[floor, cap]`.
 *  * **Null advance after a long idle** → capped so a paused session doesn't lurch animations
 *    forward when the user returns.
 *  * **Explicit advance** → preserved verbatim (recording sessions pace frames themselves).
 *
 * The test runs on plain JUnit (no Robolectric sandbox boot) by driving `slot.interactiveCommands`
 * directly: a poller thread drains each Render envelope and posts a synthetic [RenderResult] back
 * through `DaemonHostBridge.results` so the foreground `render()` call returns. That sidesteps the
 * minute-long `RENDER_TIMEOUT_SEC` blocking wait without changing production code.
 */
class AndroidInteractiveSessionWallClockTest {

  private lateinit var slot: SandboxSlot
  private lateinit var activeStreamRef: AtomicReference<String?>
  private lateinit var session: AndroidInteractiveSession

  @Before
  fun setUp() {
    DaemonHostBridge.reset()
    slot = DaemonHostBridge.slot(0)
    activeStreamRef = AtomicReference(STREAM_ID)
    session =
      AndroidInteractiveSession(
        previewId = "preview-wall-clock",
        streamId = STREAM_ID,
        slot = slot,
        activeStreamRef = activeStreamRef,
        // Disable the watchdog — these tests don't exercise the idle-lease path and we don't want
        // a scheduled close() racing with the fake reply harness.
        idleLeaseMs = 0L,
      )
  }

  @After
  fun tearDown() {
    DaemonHostBridge.reset()
  }

  @Test
  fun firstRenderWithNullAdvanceUsesFloor() {
    val cmd = renderAndCaptureCommand(advanceTimeMs = null, requestId = 1L)
    assertNotNull("expected an advanceTimeMs to be substituted", cmd.advanceTimeMs)
    assertEquals(
      "first render with null advance must substitute the floor (no previous render to subtract)",
      32L,
      cmd.advanceTimeMs,
    )
  }

  @Test
  fun secondRenderWithNullAdvanceTracksWallClockDelta() {
    renderAndCaptureCommand(advanceTimeMs = null, requestId = 1L)
    val gapMs = 200L
    Thread.sleep(gapMs)
    val cmd = renderAndCaptureCommand(advanceTimeMs = null, requestId = 2L)
    val advance =
      cmd.advanceTimeMs
        ?: error("second render must substitute a wall-clock advance, not leave it null")
    assertTrue(
      "expected substituted advance ≈ ${gapMs}ms, got ${advance}ms — wall-clock delta should " +
        "drive the held mainClock so animations track real time",
      advance in (gapMs - 50L)..(gapMs + 200L),
    )
  }

  @Test
  fun explicitAdvanceIsPreservedAndDoesNotPokeWallClockBookkeeping() {
    val explicit = renderAndCaptureCommand(advanceTimeMs = 333L, requestId = 1L)
    assertEquals(
      "explicit advanceTimeMs must round-trip unchanged for recording-style callers",
      333L,
      explicit.advanceTimeMs,
    )
    // The first explicit-advance call should still seed `lastRenderAtMs`, otherwise the next
    // null-advance render would get the floor instead of a wall-clock delta. Sleep a known gap
    // and observe the substituted advance to confirm.
    Thread.sleep(150L)
    val followup = renderAndCaptureCommand(advanceTimeMs = null, requestId = 2L)
    val advance = followup.advanceTimeMs ?: error("expected null-advance to substitute a delta")
    assertTrue(
      "expected substituted advance ≈ 150ms after an explicit-advance render, got ${advance}ms",
      advance in 100L..400L,
    )
  }

  @Test
  fun longIdleClampsToTheCap() {
    renderAndCaptureCommand(advanceTimeMs = null, requestId = 1L)
    // Simulate a paused session by manually backdating the bookkeeping rather than sleeping for
    // the cap interval — keeps the test fast. We assert the cap by reading
    // `lastUsedAtMs` indirectly through the next render's advance.
    //
    // The session's render() reads `lastRenderAtMs` and substitutes `now - lastRenderAtMs`,
    // clamped to MAX. We can't poke the field directly without reflection, so instead we verify
    // the cap by sleeping past it. 1000ms is the production cap; 1100ms gives us 100ms of slack.
    Thread.sleep(1_100L)
    val cmd = renderAndCaptureCommand(advanceTimeMs = null, requestId = 2L)
    val advance = cmd.advanceTimeMs ?: error("expected null-advance to substitute a delta")
    assertTrue(
      "expected advance to be clamped to the 1000ms cap after a 1100ms idle, got ${advance}ms",
      advance <= 1_000L,
    )
    assertTrue(
      "expected the clamped advance to still reflect the cap (≥ 900ms), got ${advance}ms",
      advance >= 900L,
    )
  }

  /**
   * Drive `session.render()` from a worker thread, drain the resulting [InteractiveCommand.Render]
   * off `slot.interactiveCommands`, post a synthetic [RenderResult] reply onto
   * `DaemonHostBridge.results`, then join the worker and return the captured command. The session
   * blocks on the reply queue with a 60s timeout in production; the harness completes the round
   * trip in milliseconds without changing production code paths.
   */
  private fun renderAndCaptureCommand(
    advanceTimeMs: Long?,
    requestId: Long,
  ): InteractiveCommand.Render {
    val captured = AtomicReference<InteractiveCommand.Render?>(null)
    val workerError = AtomicReference<Throwable?>(null)
    val worker =
      Thread({
          try {
            session.render(requestId = requestId, advanceTimeMs = advanceTimeMs)
          } catch (t: Throwable) {
            workerError.set(t)
          }
        })
        .apply {
          isDaemon = true
          name = "AndroidInteractiveSessionWallClockTest-render-$requestId"
        }
    worker.start()
    val cmd =
      slot.interactiveCommands.poll(5, TimeUnit.SECONDS) as? InteractiveCommand.Render
        ?: error(
          "expected an InteractiveCommand.Render on the slot's queue within 5s; got nothing — " +
            "session.render() may not have enqueued"
        )
    captured.set(cmd)
    DaemonHostBridge.results.computeIfAbsent(requestId) { LinkedBlockingQueue() }.put(FAKE_RESULT)
    worker.join(5_000)
    workerError.get()?.let { throw AssertionError("render() worker failed", it) }
    return cmd
  }

  private companion object {
    const val STREAM_ID: String = "stream-wall-clock"

    val FAKE_RESULT: RenderResult =
      RenderResult(
        id = 0L,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        pngPath = "/tmp/fake-render.png",
        metrics = mapOf("test" to 1L),
      )
  }
}
