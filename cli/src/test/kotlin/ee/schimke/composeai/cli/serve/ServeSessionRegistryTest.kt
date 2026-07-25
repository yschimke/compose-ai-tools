package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServeSessionRegistryTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-registry").toFile().also { it.deleteOnExit() }

  private fun stateFor(label: String): ServeSessionState =
    ServeSessionState(
      descriptor = File("daemon-launch.json"),
      workspaceRoot = newRenderRoot(),
      workspaceName = "w",
      previews = listOf(ServePreview(previewId, "Red")),
      label = label,
    )

  /** Opens a fresh fake host per call; records how many times it ran. */
  private inner class Opener(private val streaming: Boolean = false) :
    (ServeSessionState) -> ServeRenderHost? {
    val opened = AtomicInteger(0)

    override fun invoke(state: ServeSessionState): ServeRenderHost {
      opened.incrementAndGet()
      return ServeRenderHost(
        session = FakeRenderSession(newRenderRoot(), streaming = streaming),
        previews = state.previews,
        label = state.label,
        renderTimeoutSeconds = 30,
      )
    }
  }

  /** A factory that builds a state per id (except "missing") and counts builds. */
  private inner class CountingFactory : ServeSessionFactory {
    val built = AtomicInteger(0)

    override fun create(sessionId: String): ServeSessionState? {
      if (sessionId == "missing") return null
      built.incrementAndGet()
      return stateFor(sessionId)
    }
  }

  /**
   * Suspension closes the outgoing daemon OUTSIDE the registry lock (so a blocking shutdown doesn't
   * stall unrelated sessions), which opens a window where the entry's host is already null while
   * its daemon is still alive. A resume landing in that window must WAIT, not open a replacement
   * alongside it — otherwise one session briefly runs two daemon subprocesses and overshoots the
   * live-seat / memory budget the limiter is there to enforce.
   */
  @Test
  fun `a resume waits for the outgoing daemon to finish closing`() {
    val clock = AtomicLong(0)
    val opener = Opener()
    val releaseClose = CountDownLatch(1)
    val closeStarted = CountDownLatch(1)
    val closeFinished = AtomicBoolean(false)
    // Wrap the opened host so its close() parks until the test lets it go.
    val blockingOpener: (ServeSessionState) -> ServeHost? = { state ->
      val delegate = opener(state)
      object : ServeHost by delegate {
        override fun close() {
          closeStarted.countDown()
          // BOUNDED park: if this test ever fails mid-flight, the registry's own close() must not
          // block forever on a latch nobody will release — a regression should fail, not hang CI.
          releaseClose.await(10, TimeUnit.SECONDS)
          delegate.close()
          closeFinished.set(true)
        }
      }
    }
    ServeSessionRegistry(
        open = blockingOpener,
        idleTimeoutMillis = 10,
        reaperIntervalMillis = 0,
        clock = { clock.get() },
      )
      .use { reg ->
        try {
          reg.register("a", stateFor("a"))
          assertNotNull(reg.acquire("a"))
          assertEquals(1, opener.opened.get())

          clock.set(100)
          val suspender = Thread { reg.suspendIdle() }.apply { start() }
          assertTrue(closeStarted.await(5, TimeUnit.SECONDS), "the suspension started closing")

          // The host is detached but its daemon is still shutting down. A resume now must block.
          val resumed = AtomicReference<ServeHost?>(null)
          val resumeReturned = CountDownLatch(1)
          Thread {
              resumed.set(reg.acquire("a"))
              resumeReturned.countDown()
            }
            .start()
          assertTrue(
            !resumeReturned.await(300, TimeUnit.MILLISECONDS),
            "the resume must not complete while the previous daemon is still closing",
          )
          assertEquals(
            1,
            opener.opened.get(),
            "no second daemon is opened alongside the closing one",
          )

          // Let the close finish; the parked resume then opens exactly one replacement.
          releaseClose.countDown()
          assertTrue(resumeReturned.await(5, TimeUnit.SECONDS), "the resume completes once closed")
          suspender.join(5_000)
          assertTrue(
            closeFinished.get(),
            "the outgoing daemon closed before the replacement opened",
          )
          assertNotNull(resumed.get())
          assertEquals(2, opener.opened.get(), "exactly one replacement daemon")
        } finally {
          // Never leave a blocked close() parked — an assertion failure above would otherwise
          // stall the registry's own close() inside use{}.
          releaseClose.countDown()
        }
      }
  }

  @Test
  fun `acquire builds once, opens once, and caches the live host`() {
    val opener = Opener()
    val factory = CountingFactory()
    ServeSessionRegistry(open = opener, factory = factory, reaperIntervalMillis = 0).use { reg ->
      val first = assertNotNull(reg.acquire("a"))
      val second = assertNotNull(reg.acquire("a"))
      assertSame(first, second, "a resident session returns the same host")
      assertEquals(1, factory.built.get())
      assertEquals(1, opener.opened.get())
      assertEquals(1, reg.activeCount())
      assertEquals(1, reg.residentCount())
    }
  }

  @Test
  fun `a suspended session resumes from saved state without rebuilding`() {
    val clock = AtomicLong(0)
    val opener = Opener()
    val factory = CountingFactory()
    ServeSessionRegistry(
        open = opener,
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val live = assertNotNull(reg.acquire("a"))
        clock.set(200)
        assertEquals(1, reg.suspendIdle(), "the idle daemon is suspended")
        assertEquals(0, reg.residentCount(), "no daemon resident while suspended")
        assertEquals(1, reg.activeCount(), "but the session (its state) is retained")

        val resumed = assertNotNull(reg.acquire("a"))
        assertNotSame(live, resumed, "resume opens a fresh daemon from the saved state")
        assertEquals(1, factory.built.get(), "resume must NOT rebuild")
        assertEquals(2, opener.opened.get(), "resume re-opens from state")
        assertEquals(1, reg.residentCount())
      }
  }

  @Test
  fun `a registered session resumes from its state, never rebuilding`() {
    val clock = AtomicLong(0)
    val opener = Opener()
    val factory = CountingFactory()
    ServeSessionRegistry(
        open = opener,
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val eager =
          ServeRenderHost(
            FakeRenderSession(newRenderRoot()),
            listOf(ServePreview(previewId, "Red")),
          )
        reg.register("primary", stateFor("primary"), host = eager)
        assertSame(eager, reg.acquire("primary"), "the eager host is served while resident")

        clock.set(200)
        assertEquals(1, reg.suspendIdle())
        val resumed = assertNotNull(reg.acquire("primary"))
        assertNotSame(eager, resumed)
        assertEquals(0, factory.built.get(), "a registered session is never built by the factory")
        assertEquals(1, opener.opened.get(), "resumed via the opener")
      }
  }

  @Test
  fun `liveSeatWeight surfaces the session state's weight, defaulting to 1`() {
    ServeSessionRegistry(open = Opener()).use { reg ->
      val heavy = stateFor("wear-m3").copy(liveSeatWeight = 2)
      reg.register("wear-m3", heavy)
      reg.register("compose-m3", stateFor("compose-m3")) // default weight
      assertEquals(2, reg.liveSeatWeight("wear-m3"), "the Android session's heavier weight is read")
      assertEquals(1, reg.liveSeatWeight("compose-m3"), "a default-weight session reads 1")
      assertEquals(1, reg.liveSeatWeight("unknown"), "an unknown/forked session defaults to 1")
    }
  }

  @Test
  fun `runningDaemons snapshots resident hosts and peekHost never resumes`() {
    val clock = AtomicLong(0)
    val opener = Opener()
    ServeSessionRegistry(
        open = opener,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val heavy = stateFor("wear-m3").copy(liveSeatWeight = 2)
        reg.register("wear-m3", heavy)
        reg.register("compose-m3", stateFor("compose-m3"))
        assertNotNull(reg.acquire("wear-m3"))
        assertNotNull(reg.acquire("compose-m3"))

        val running = reg.runningDaemons()
        assertEquals(listOf("compose-m3", "wear-m3"), running.map { it.id }, "id-sorted")
        val wear = running.single { it.id == "wear-m3" }
        assertEquals(2, wear.liveSeatWeight, "the state's live-seat weight is surfaced")
        assertTrue(wear.hasLiveStream, "a daemon-backed host advertises a live stream")
        assertEquals(0L, wear.startedAt, "started-at is stamped when the daemon opens")

        // Suspend, then confirm peek/runningDaemons see it as gone WITHOUT resuming it.
        clock.set(200)
        assertEquals(2, reg.suspendIdle())
        assertNull(reg.peekHost("wear-m3"), "peek returns null for a suspended session")
        assertTrue(reg.runningDaemons().isEmpty(), "a suspended daemon drops out of runningDaemons")
        assertEquals(2, opener.opened.get(), "peek/runningDaemons never re-opened a daemon")
      }
  }

  /** Minimal [ServeHost] that only records whether it was closed. */
  private class RecordingHost : ServeHost {
    var closed = false
      private set

    override val previews: List<ServePreview> = emptyList()
    override val label: String = "recording"

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.NotFound

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: ee.schimke.composeai.daemon.protocol.StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (ee.schimke.composeai.daemon.protocol.StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `re-registering a session id closes the replaced host`() {
    ServeSessionRegistry(open = Opener()).use { reg ->
      val first = RecordingHost()
      val second = RecordingHost()
      reg.register("compose-m3", host = first, pinned = true)
      // A catalog refresh re-registers the same pinned id with a fresh host.
      reg.register("compose-m3", host = second, pinned = true)
      assertTrue(first.closed, "the replaced host (and its daemon) is closed on re-registration")
      assertTrue(!second.closed, "the newly registered host stays open")
      assertSame(second, reg.acquire("compose-m3"), "the new host is served")
      // Re-registering the SAME instance must NOT close it (idempotent seed).
      reg.register("compose-m3", host = second, pinned = true)
      assertTrue(!second.closed, "re-registering the same host instance does not close it")
    }
  }

  @Test
  fun `a leased session is not suspended until the lease closes`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("a"))
        clock.set(10_000)
        assertEquals(0, reg.suspendIdle(), "an open lease keeps the daemon resident")
        lease.close()
        clock.set(20_000)
        assertEquals(1, reg.suspendIdle(), "after the lease closes the idle daemon suspends")
      }
  }

  @Test
  fun `a session with live watchers is not suspended`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(streaming = true),
        factory = CountingFactory(),
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val host = assertNotNull(reg.acquire("a"))
        val handle =
          assertNotNull(host.subscribeStream(previewId, PreviewOverrides(), null, null) {})
        clock.set(10_000)
        assertEquals(0, reg.suspendIdle(), "a host with a live watcher must stay resident")
        handle.close()
        assertEquals(1, reg.suspendIdle(), "once the watcher leaves it can suspend")
      }
  }

  @Test
  fun `acquire returns null when the factory cannot create the session`() {
    ServeSessionRegistry(open = Opener(), factory = CountingFactory(), reaperIntervalMillis = 0)
      .use { reg ->
        assertNull(reg.acquire("missing"))
        assertEquals(0, reg.activeCount())
      }
  }

  @Test
  fun `idleMillis is null while leased and grows from the last activity otherwise`() {
    val clock = AtomicLong(1_000)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("a"))
        clock.set(5_000)
        assertNull(reg.idleMillis(), "an open lease means the server is busy, not idle")

        lease.close() // records activity at t=5_000
        clock.set(8_500)
        assertEquals(3_500L, reg.idleMillis(), "idle counts from the last activity once unleased")
      }
  }

  @Test
  fun `a long-idle suspended forked session is reclaimed and its worktree pruned`() {
    val clock = AtomicLong(0)
    val reclaimed = mutableListOf<String>()
    val factory = ServeSessionFactory { id -> stateFor(id).copy(reclaim = { reclaimed += id }) }
    ServeSessionRegistry(
        open = Opener(),
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        suspendedGcTimeoutMillis = 1_000,
        clock = clock::get,
      )
      .use { reg ->
        assertNotNull(reg.acquire("rev1")) // fork + open at t=0
        clock.set(200)
        assertEquals(1, reg.suspendIdle(), "idle past the suspend window → daemon released")
        assertEquals(0, reg.reclaimIdleForked(), "still inside the GC window → not reclaimed")
        assertEquals(1, reg.activeCount(), "state retained while inside the GC window")

        clock.set(1_500)
        assertEquals(1, reg.reclaimIdleForked(), "past the GC window → reclaimed")
        assertEquals(0, reg.activeCount(), "the forked session is removed entirely")
        assertEquals(listOf("rev1"), reclaimed, "its worktree reclaim hook ran exactly once")
      }
  }

  @Test
  fun `a resident forked session is never reclaimed`() {
    val clock = AtomicLong(0)
    val reclaimed = mutableListOf<String>()
    val factory = ServeSessionFactory { id -> stateFor(id).copy(reclaim = { reclaimed += id }) }
    ServeSessionRegistry(
        open = Opener(),
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        suspendedGcTimeoutMillis = 1_000,
        clock = clock::get,
      )
      .use { reg ->
        assertNotNull(reg.acquire("rev1"))
        clock.set(10_000) // long idle, but never suspended (host still resident)
        assertEquals(0, reg.reclaimIdleForked(), "a live host is suspended before it can be GC'd")
        assertEquals(1, reg.activeCount())
        assertTrue(reclaimed.isEmpty())
      }
  }

  @Test
  fun `a registered session is never reclaimed even when long-idle and suspended`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        suspendedGcTimeoutMillis = 1_000,
        clock = clock::get,
      )
      .use { reg ->
        val eager =
          ServeRenderHost(
            FakeRenderSession(newRenderRoot()),
            listOf(ServePreview(previewId, "Red")),
          )
        reg.register("primary", stateFor("primary"), host = eager)
        clock.set(200)
        assertEquals(1, reg.suspendIdle(), "a registered session still suspends its daemon")
        clock.set(10_000)
        assertEquals(0, reg.reclaimIdleForked(), "but a registered session is never removed")
        assertEquals(1, reg.activeCount(), "so it stays permanently resumable")
      }
  }

  @Test
  fun `the GC is disabled when the timeout is non-positive`() {
    val clock = AtomicLong(0)
    val factory = ServeSessionFactory { id -> stateFor(id).copy(reclaim = {}) }
    ServeSessionRegistry(
        open = Opener(),
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        suspendedGcTimeoutMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        assertNotNull(reg.acquire("rev1"))
        clock.set(200)
        reg.suspendIdle()
        clock.set(1_000_000)
        assertEquals(0, reg.reclaimIdleForked(), "GC off → nothing reclaimed however idle")
        assertEquals(1, reg.activeCount())
      }
  }

  @Test
  fun `close releases every resident host and rejects further acquire`() {
    val reg =
      ServeSessionRegistry(open = Opener(), factory = CountingFactory(), reaperIntervalMillis = 0)
    reg.acquire("a")
    reg.acquire("b")
    assertEquals(2, reg.residentCount())
    reg.close()
    assertEquals(0, reg.activeCount())
    assertTrue(runCatching { reg.acquire("c") }.isFailure, "a closed registry rejects acquire")
  }
}
