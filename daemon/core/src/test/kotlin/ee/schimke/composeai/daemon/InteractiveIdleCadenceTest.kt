package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live frame loop's idle backoff.
 *
 * A held session that nobody is touching still has to be polled — nothing tells the daemon an
 * animation has started, so the only way to find out is to render and compare. The loop therefore
 * cannot stop. But polling a resting preview four times a second for as long as its socket stays
 * open is most of what an unattended live viewer costs on a public server, and because every render
 * refreshes the held session's `lastUsedAtMs`, it also kept the idle lease from ever reclaiming the
 * sandbox from a visitor who had wandered off.
 *
 * These drive [JsonRpcServer.interactiveIdleCadenceMs] directly. It is a pure function of the
 * byte-identical-frame run length — which is the point of deriving quiescence from the hash the
 * daemon already computes for `renderFinished.unchanged` — so the policy is testable without a
 * daemon, a sandbox, or a wall clock.
 *
 * The burst cadence an input opens is a separate mechanism and is unaffected; see
 * `InteractiveInputBurstTest`.
 */
class InteractiveIdleCadenceTest {

  /** Never asked to render anything — these tests only exercise the cadence policy. */
  private object InertHost : RenderHost {
    override fun start() = Unit

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      error("InertHost renders nothing")

    override fun shutdown(timeoutMs: Long) = Unit
  }

  private fun server(
    idleMax: Long = 2_000L,
    quiescentAfter: Int = 3,
    intervalMs: Long = JsonRpcServer.INTERACTIVE_FRAME_INTERVAL_MS,
  ): JsonRpcServer =
    JsonRpcServer(
      input = java.io.ByteArrayInputStream(ByteArray(0)),
      output = java.io.ByteArrayOutputStream(),
      host = InertHost,
      daemonVersion = "test",
      onExit = {},
      interactiveFrameIntervalMs = intervalMs,
      interactiveIdleMaxIntervalMs = idleMax,
      interactiveQuiescentAfter = quiescentAfter,
    )

  /** Drive the policy at a given run length without a daemon: the run is keyed by previewId. */
  private fun JsonRpcServer.cadenceAtRun(run: Int): Long {
    val field = JsonRpcServer::class.java.getDeclaredField("interactiveIdleRun")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = field.get(this) as java.util.concurrent.ConcurrentHashMap<String, Int>
    if (run <= 0) map.remove(PREVIEW) else map[PREVIEW] = run
    return interactiveIdleCadenceMs(PREVIEW)
  }

  @Test
  fun `a preview with pixels still moving keeps the base cadence`() {
    val s = server()
    assertEquals(250L, s.cadenceAtRun(0))
    assertEquals(250L, s.cadenceAtRun(1))
    assertEquals(250L, s.cadenceAtRun(2))
  }

  @Test
  fun `a single unchanged frame mid-animation does not slow the loop down`() {
    // The grace window is the whole point of `quiescentAfter`. One byte-identical frame is ordinary
    // inside an animation, and backing off on it would stutter the cadence for the rest of it.
    val s = server(quiescentAfter = 3)
    assertEquals(250L, s.cadenceAtRun(2))
    assertTrue(s.cadenceAtRun(3) > 250L)
  }

  @Test
  fun `backs off geometrically once the preview is quiescent`() {
    val s = server()
    assertEquals(500L, s.cadenceAtRun(3))
    assertEquals(1_000L, s.cadenceAtRun(4))
    assertEquals(2_000L, s.cadenceAtRun(5))
  }

  @Test
  fun `the backoff is capped, and stays capped however long the session idles`() {
    // The cap is what stops "poll less often" decaying into "never" — the loop is the only thing
    // that can notice an animation starting on a preview nobody is touching.
    val s = server(idleMax = 4_000L)
    assertEquals(4_000L, s.cadenceAtRun(6))
    assertEquals(4_000L, s.cadenceAtRun(50))
    assertEquals(4_000L, s.cadenceAtRun(10_000))
    assertEquals(4_000L, s.cadenceAtRun(Int.MAX_VALUE))
  }

  @Test
  fun `an unknown preview gets the base cadence rather than an accidental backoff`() {
    val s = server()
    assertEquals(250L, s.interactiveIdleCadenceMs(null))
    assertEquals(250L, s.interactiveIdleCadenceMs("never-rendered"))
  }

  @Test
  fun `a disabled loop stays disabled rather than picking up an idle cadence`() {
    // `interactiveFrameIntervalMs <= 0` is how callers switch the loop off entirely
    // (InteractiveCoalescingTest does this to measure input coalescing in isolation).
    val s = server(intervalMs = 0L)
    assertEquals(0L, s.cadenceAtRun(0))
    assertEquals(0L, s.cadenceAtRun(10))
  }

  @Test
  fun `an idle viewer costs a fraction of what the flat cadence did`() {
    // The headline number, computed rather than timed: renders issued over a minute of a live
    // preview nobody is interacting with.
    val s = server()
    var elapsed = 0L
    var renders = 0
    var run = 0
    while (elapsed < 60_000L) {
      renders++
      elapsed += s.cadenceAtRun(run)
      run++ // every frame of a resting preview is byte-identical
    }
    val flat = (60_000L / JsonRpcServer.INTERACTIVE_FRAME_INTERVAL_MS).toInt()
    assertTrue(
      "an idle minute should cost far fewer than the flat cadence's $flat renders, got $renders",
      renders * 5 < flat,
    )
  }

  private companion object {
    private const val PREVIEW = "com.example.Resting"
  }
}
