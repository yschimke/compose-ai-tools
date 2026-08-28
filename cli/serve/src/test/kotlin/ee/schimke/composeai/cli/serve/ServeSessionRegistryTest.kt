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
import kotlin.test.assertFalse
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
  fun `a reserved route name is never bound to a session`() {
    // No entry in `sessions` may be named after one of the server's own top-level routes — a
    // session called `api` is unreachable at `/api/` on the main host (Ktor scores the constant
    // segment above `/{system}`) but WOULD be served by `/{system}/` on a top-level site host,
    // which is the isolation leak [ServeSites] exists to prevent. There are two places an id is
    // bound: `register` (checked) and the on-demand fork in `entryFor` — with `--revisions`, a
    // permitted ref named `api` reaches only the latter, so both have to refuse it.
    val opener = Opener()
    val factory = CountingFactory()
    ServeSessionRegistry(open = opener, factory = factory, reaperIntervalMillis = 0).use { reg ->
      for (reserved in listOf("api", "status", "render", "p", "rc-fonts")) {
        assertNull(reg.acquire(reserved), "'$reserved' must not be forked into a session")
        assertNull(reg.peekHost(reserved))
        assertTrue(!reg.isKnownSession(reserved), "'$reserved' must not enter the registry")
      }
      assertEquals(0, factory.built.get(), "a reserved name never reaches the factory at all")
      assertNotNull(reg.acquire("main"), "an ordinary ref still forks")
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

  /**
   * The counterpart to [ServeSessionRegistry.addSuspendListener].
   *
   * `peekHost` tells a caller that needs a session's facts to keep a last-known snapshot across
   * suspension. Without a retirement signal that advice has no ending: the holder is told to keep
   * facts and never told the session they belong to is gone, so a host that publishes, serves and
   * retires catalogs through the admin API retains every one of them for the life of the process.
   */
  @Test
  fun `unregister notifies snapshot holders so a retired catalog can be dropped`() {
    val retired = mutableListOf<String>()
    ServeSessionRegistry(open = Opener()).use { reg ->
      reg.addUnregisterListener { retired += it }
      reg.register("compose-m3", stateFor("compose-m3"))
      reg.register("wear-m3", stateFor("wear-m3"))

      assertTrue(reg.unregister("compose-m3"), "the registered catalog was retired")
      assertEquals(listOf("compose-m3"), retired, "only the retired catalog is announced")

      // Nothing was removed, so nothing is announced — a holder must not drop a live session's
      // snapshot because somebody asked to retire a name that was never registered.
      assertFalse(reg.unregister("never-published"), "an unknown id retires nothing")
      assertEquals(listOf("compose-m3"), retired, "a no-op retirement announces nothing")
    }
  }

  /**
   * The guarantee a suspend listener cannot give: no reader can observe a session detached from its
   * host with its snapshot not yet published.
   *
   * `capture` runs under the registry lock immediately before the detach, so the two are one
   * transition. This asserts it from inside the callback — at the moment `capture` runs the host is
   * still attached, which is exactly what makes "resident" and "snapshotted" the only two states
   * visible from outside.
   */
  @Test
  fun `a snapshot is captured as part of the detach, not after it`() {
    val clock = AtomicLong(0)
    val captured = mutableListOf<String>()
    var attachedAtCapture: Boolean? = null
    ServeSessionRegistry(
        open = Opener(),
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        reg.setSessionSnapshots(
          object : ServeSessionRegistry.SessionSnapshots {
            override fun capture(sessionId: String, host: ServeHost) {
              captured += sessionId
              // Read through the registry's own accessor: still resident here, because the detach
              // has not happened yet. If capture ever moves after it, this flips and the window
              // this test exists to deny is back.
              attachedAtCapture = reg.peekHost(sessionId) != null
            }

            override fun discard(sessionId: String) {
              captured -= sessionId
            }
          }
        )
        reg.register("compose-m3", stateFor("compose-m3"))
        assertNotNull(reg.acquire("compose-m3"), "resident before the idle window")

        clock.set(1_000)
        assertEquals(1, reg.suspendIdle(), "the idle session was suspended")
        assertEquals(listOf("compose-m3"), captured, "its snapshot was captured")
        assertEquals(true, attachedAtCapture, "capture ran BEFORE the host was detached")
        assertNull(reg.peekHost("compose-m3"), "and the session is suspended afterwards")
      }
  }

  /** Retirement discards under the same lock, so nothing survives the catalog it belonged to. */
  @Test
  fun `retiring a suspended catalog discards its snapshot`() {
    val clock = AtomicLong(0)
    val held = mutableSetOf<String>()
    ServeSessionRegistry(
        open = Opener(),
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        reg.setSessionSnapshots(
          object : ServeSessionRegistry.SessionSnapshots {
            override fun capture(sessionId: String, host: ServeHost) {
              held += sessionId
            }

            override fun discard(sessionId: String) {
              held -= sessionId
            }
          }
        )
        reg.register("compose-m3", stateFor("compose-m3"))
        reg.acquire("compose-m3")
        clock.set(1_000)
        reg.suspendIdle()
        assertEquals(setOf("compose-m3"), held, "suspended, so its snapshot is held")

        assertTrue(reg.unregister("compose-m3"), "the catalog was retired")
        assertEquals(emptySet(), held, "and its snapshot went with it")
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
  fun `background optimization keeps an idle catalog resident until work finishes`() {
    val clock = AtomicLong(0)
    val optimizing = AtomicBoolean(true)
    val delegate = RecordingHost()
    val host =
      object : ServeHost by delegate {
        override val backgroundWorkActive: Boolean
          get() = optimizing.get()
      }
    ServeSessionRegistry(
        open = { null },
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { registry ->
        registry.register("catalog", stateFor("catalog"), host = host)
        clock.set(200)
        assertEquals(0, registry.suspendIdle(), "background cache fill keeps its daemon resident")
        assertEquals(false, delegate.closed)

        optimizing.set(false)
        assertEquals(1, registry.suspendIdle(), "the daemon may suspend after optimization")
        assertTrue(delegate.closed)
      }
  }

  @Test
  fun `an unfinished optimizer no longer pins its daemon and is resumed when a lane is free`() {
    val clock = AtomicLong(0)
    val work = ServeBackgroundWork(clock = clock::get)
    val cache = CatalogThemeCache().apply { configureTargets(listOf("preview|dark")) }
    val opener = Opener()
    ServeSessionRegistry(
        open = opener,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { registry ->
        registry.register(
          "catalog",
          stateFor("catalog").copy(catalogThemeCache = cache, backgroundWork = work),
          host = opener(stateFor("catalog")),
        )
        clock.set(200)
        // The pass is parked, not holding a lane: `backgroundWorkActive` is false, so the daemon
        // is reclaimable even though the catalog has targets left. This is the whole change — the
        // flag used to stay set for the worker's life and the worker never ends.
        assertEquals(1, registry.suspendIdle(), "a parked optimizer does not pin its daemon")
        assertEquals(
          1,
          work.optimizerAdmissionSnapshot().hostSuspensions,
          "the suspension is counted against the optimizer, not lost as an ordinary idle reap",
        )

        val openedBefore = opener.opened.get()
        assertEquals(1, registry.resumeIdleOptimizers(), "an unfinished catalog is brought back")
        assertEquals(openedBefore + 1, opener.opened.get(), "the host was actually reopened")
        assertEquals(1, work.optimizerAdmissionSnapshot().hostResumes)

        // Resuming stamps the session's own idle clock, not the whole-server one the quiet gate
        // reads: a resume that reported the box as busy would refuse the turn it exists to take.
        assertNotNull(registry.idleMillis(), "the resume did not make the server look busy")
        assertEquals(0, registry.suspendIdle(), "the resumed host gets its idle window to work in")
      }
  }

  /**
   * A catalog an operator marked for regeneration is work, and has to be treated as work.
   *
   * Warm everywhere and finished nowhere is a state that did not exist before renders could be
   * inherited across a build. `fullyOptimized` still reads true for it, so the resume filter passed
   * straight over the one catalog that had just been given something to do — and the action that
   * marked it answered `queued` while nothing would ever come and work the queue.
   */
  @Test
  fun `a catalog marked for regeneration is resumed even though every target is warm`() {
    val root = java.nio.file.Files.createTempDirectory("registry-dirty").toFile()
    root.deleteOnExit()
    val fp = "f".repeat(64)
    val generation =
      assertNotNull(
        ThemeCacheStore(root, graceMillis = 0)
          .open(
            "marked",
            fp,
            GenerationInputs(
              system = "marked",
              fingerprint = fp,
              toolVersion = "1.14.0",
              variant = "desktop",
              renderConfig = "density=2",
            ),
          )
      )
    val clock = AtomicLong(0)
    val work = ServeBackgroundWork(clock = clock::get)
    val cache =
      CatalogThemeCache(persistence = generation).apply {
        configureTargets(listOf("preview|dark"))
        put("preview|dark", ByteArray(4))
      }
    assertTrue(cache.snapshot().fullyOptimized, "every target is warm")

    val opener = Opener()
    ServeSessionRegistry(
        open = opener,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { registry ->
        registry.register(
          "marked",
          stateFor("marked").copy(catalogThemeCache = cache, backgroundWork = work),
          host = opener(stateFor("marked")),
        )
        clock.set(200)
        assertEquals(1, registry.suspendIdle())
        assertEquals(0, registry.resumeIdleOptimizers(), "warm and converged: nothing to do")

        assertEquals(1, cache.markPersistedDirty(), "the operator's regenerate")
        val openedBefore = opener.opened.get()
        assertEquals(
          1,
          registry.resumeIdleOptimizers(),
          "and now there is work, so the suspended catalog comes back to do it",
        )
        assertEquals(openedBefore + 1, opener.opened.get())
      }
  }

  @Test
  fun `a fully optimized or lane-starved catalog is not resumed`() {
    val clock = AtomicLong(0)
    val work = ServeBackgroundWork(clock = clock::get)
    val unfinished = CatalogThemeCache().apply { configureTargets(listOf("preview|dark")) }
    val finished =
      CatalogThemeCache().apply {
        configureTargets(listOf("preview|dark"))
        put("preview|dark", ByteArray(4))
      }
    val opener = Opener()
    ServeSessionRegistry(
        open = opener,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { registry ->
        registry.register(
          "done",
          stateFor("done").copy(catalogThemeCache = finished, backgroundWork = work),
          host = opener(stateFor("done")),
        )
        registry.register(
          "todo",
          stateFor("todo").copy(catalogThemeCache = unfinished, backgroundWork = work),
          host = opener(stateFor("todo")),
        )
        clock.set(200)
        assertEquals(2, registry.suspendIdle())

        // No lane to give: resuming here would pay a cold daemon to stand at the door, which is
        // the residency the suspension just reclaimed.
        work.pauseOptimizers(10_000, "test")
        assertEquals(0, work.optimizerResumeSlots())
        assertEquals(0, registry.resumeIdleOptimizers(), "no lane free, so nothing is resumed")

        work.resumeOptimizers()
        val openedBefore = opener.opened.get()
        assertEquals(1, registry.resumeIdleOptimizers(), "only the unfinished catalog comes back")
        assertEquals(openedBefore + 1, opener.opened.get())
      }
  }

  @Test
  fun `parked catalogs rotate rather than waiting for an incumbent to finish`() {
    val clock = AtomicLong(0)
    // One lane, so without the challenger slot the single incumbent would hold it indefinitely: a
    // pass re-queues the instant its slice ends, and `lanes - inUse - queued` then reads zero on
    // every later sweep, leaving the parked catalogs to wait for someone to FINISH. That is the
    // starvation the +1 exists to break.
    val work = ServeBackgroundWork(maxConcurrentOptimizers = 1, clock = clock::get)
    val reopened = java.util.Collections.synchronizedList(mutableListOf<String>())
    val opener = Opener()
    val recording: (ServeSessionState) -> ServeRenderHost? = { state ->
      reopened += state.label
      opener(state)
    }
    val ids = listOf("alpha", "beta", "gamma")
    ServeSessionRegistry(
        open = recording,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { registry ->
        ids.forEach { id ->
          val cache = CatalogThemeCache().apply { configureTargets(listOf("$id|dark")) }
          registry.register(
            id,
            stateFor(id).copy(catalogThemeCache = cache, backgroundWork = work),
            host = opener(stateFor(id)),
          )
        }
        clock.set(1_000)
        assertEquals(3, registry.suspendIdle(), "all three park while none holds a lane")

        // One lane and nothing queued: the budget is 1 + 1 = 2 — the lane, plus a challenger
        // standing at the door so admission's fairness has someone to hand the next lane to.
        assertEquals(2, work.optimizerResumeSlots())
        reopened.clear()
        assertEquals(2, registry.resumeIdleOptimizers(), "a lane holder AND a challenger come back")
        val firstRound = reopened.toList()
        assertEquals(2, firstRound.size)

        // The third is not stranded. Park the two that just ran and sweep again: ordering is by
        // suspension time, so the catalog that has been parked since the first sweep is taken
        // first and the pair that just ran is NOT resurrected ahead of it.
        clock.set(2_000)
        assertEquals(2, registry.suspendIdle())
        reopened.clear()
        assertTrue(registry.resumeIdleOptimizers() > 0)
        assertEquals(
          ids.single { it !in firstRound },
          reopened.first(),
          "the rotation reaches the catalog that has waited longest, not the one just parked",
        )
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

  /**
   * A busy answer has to be attributable, or everything downstream of it looks broken for no
   * visible reason.
   *
   * [ServeSessionRegistry.idleMillis] going null stands the theme optimizer down and holds the
   * `--exit-when-idle` watchdog open, and a lease released in a `finally` can still leak when the
   * request is cancelled mid-flight. Naming the holders turns "the box says it is busy and is
   * serving nothing" from an inference into a one-line read on `/status.json`.
   */
  @Test
  fun `leasedSessions names exactly the holders that make the server read busy`() {
    ServeSessionRegistry(open = Opener(), factory = CountingFactory(), reaperIntervalMillis = 0)
      .use { reg ->
        assertEquals(emptyList(), reg.leasedSessions())

        val b = assertNotNull(reg.lease("b"))
        val a = assertNotNull(reg.lease("a"))
        assertEquals(listOf("a", "b"), reg.leasedSessions(), "sorted, so a diff is stable")
        assertEquals(
          listOf("a", "b"),
          reg.busyLeasedSessions(),
          "and while the holders are fresh they are also the busy set",
        )
        assertNull(reg.idleMillis(), "which is precisely why the clock reads busy")

        a.close()
        assertEquals(listOf("b"), reg.leasedSessions())

        b.close()
        assertEquals(emptyList(), reg.leasedSessions())
        assertNotNull(reg.idleMillis(), "the clock runs again once the last lease is released")
      }
  }

  /**
   * The regression this whole change exists for (#4312).
   *
   * A viewer WebSocket holds a lease for the socket's whole life, and `leases > 0` used to mean
   * *busy* outright — so one browser tab left open on a catalog pinned the server's idle clock at
   * null indefinitely, whether or not anyone was looking at it. Measured on the public box: eight
   * consecutive minutes with a lease held, `activeStreams 1` and zero renders, after which only the
   * ceiling let any work through.
   */
  @Test
  fun `a lease stops suppressing the idle clock once its holder goes quiet`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        reaperIntervalMillis = 0,
        leaseBusyMillis = 30_000,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("a", connection = true))

        clock.set(29_999)
        assertNull(reg.idleMillis(), "still busy inside the window — a visitor may be mid-browse")

        clock.set(30_000)
        assertEquals(
          30_000L,
          reg.idleMillis(),
          "quiet for the window: the clock runs again with the socket still open",
        )
        assertNull(
          reg.connectionIdleMillis(),
          "but the connection is still live, so exit-when-idle must not fire",
        )
        assertEquals(listOf("a"), reg.leasedSessions(), "and the session is still held resident")
        assertEquals(emptyList(), reg.busyLeasedSessions(), "just not by anyone doing anything")

        // A client message on the socket is what real use looks like from here.
        clock.set(45_000)
        lease.touch()
        assertNull(reg.idleMillis(), "activity re-arms it without needing a new lease")

        clock.set(80_000)
        assertEquals(35_000L, reg.idleMillis(), "and it counts from that activity, not the lease")
      }
  }

  /**
   * The ageing rule is for *connection* holds only.
   *
   * `withLeasedSession` wraps ordinary HTTP work in a lease too, and that work is not always short
   * — a cold `/render` is 30-70s and a `/bundle.zip` longer. Ageing one of those out would report
   * the box as quiet while it is still rendering, and let background work start against exactly the
   * foreground request the quiet gate exists to protect.
   */
  @Test
  fun `a request-scoped lease stays busy however long the request runs`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        reaperIntervalMillis = 0,
        leaseBusyMillis = 30_000,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("a")) // the default: a request-scoped hold

        clock.set(10 * 60_000) // ten minutes into a cold render, and it never touched
        assertNull(reg.idleMillis(), "a request in flight is busy until it is done, not for 30s")
        assertEquals(listOf("a"), reg.busyLeasedSessions())

        lease.close()
        clock.set(10 * 60_000 + 5_000)
        assertEquals(5_000L, reg.idleMillis(), "and the clock starts from its release")
      }
  }

  /**
   * A connection lease going quiet must not un-busy a request that is running alongside it — the
   * two counts are tracked separately, not collapsed into one.
   */
  @Test
  fun `a quiet connection lease does not mask a concurrent request lease`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        reaperIntervalMillis = 0,
        leaseBusyMillis = 30_000,
        clock = clock::get,
      )
      .use { reg ->
        val socket = assertNotNull(reg.lease("a", connection = true))
        val request = assertNotNull(reg.lease("a"))

        clock.set(120_000)
        assertNull(reg.idleMillis(), "the request is still in flight")

        request.close()
        clock.set(180_000)
        assertEquals(60_000L, reg.idleMillis(), "once it lands, the quiet socket does not hold on")
        socket.close()
      }
  }

  /**
   * `touch` is called from the socket's message loop, which outlives the lease's `finally` on a
   * cancelled request. Touching a released lease must not resurrect it as busy.
   */
  @Test
  fun `touching a closed lease does nothing`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(
        open = Opener(),
        factory = CountingFactory(),
        reaperIntervalMillis = 0,
        leaseBusyMillis = 30_000,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("a", connection = true))
        lease.close()
        clock.set(60_000)
        lease.touch()
        assertEquals(60_000L, reg.idleMillis(), "still counted from the release, not the touch")
        assertEquals(emptyList(), reg.leasedSessions())
      }
  }

  /**
   * The busy window has to sit **below** the optimizer's cold-entry window, or a held lease that
   * only goes quiet after the gate's own window is still the binding constraint and the gate never
   * opens under one — which is the bug, restated with a different number.
   *
   * It also has to sit below the page's presence heartbeat, or an open tab's own keepalive keeps
   * the lease permanently "active" and nothing changes.
   */
  @Test
  fun `the lease busy window fits inside the optimizer gate and the presence heartbeat`() {
    assertTrue(
      ServeSessionRegistry.DEFAULT_LEASE_BUSY_MILLIS <
        ServeCatalogLiveHost.themeOptimizationIdleMillisDefault(),
      "a lease must go quiet before the gate's own window elapses",
    )
    assertTrue(
      ServeSessionRegistry.DEFAULT_LEASE_BUSY_MILLIS < ServeWeb.PRESENCE_INTERVAL_SECONDS * 1_000L,
      "or the heartbeat alone would keep every open tab busy forever",
    )
  }

  /**
   * The GC is the *second* removal path, and it has to discard too.
   *
   * Every suspended session is captured, a forked revision host included, so a reclaim that removed
   * the entry without its snapshot would leak one per reclaimed revision — defeating the bound this
   * GC exists to enforce, on the surface with the most churn.
   */
  @Test
  fun `reclaiming a forked session discards its snapshot`() {
    val clock = AtomicLong(0)
    val held = mutableSetOf<String>()
    val factory = ServeSessionFactory { id -> stateFor(id) }
    ServeSessionRegistry(
        open = Opener(),
        factory = factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        suspendedGcTimeoutMillis = 1_000,
        clock = clock::get,
      )
      .use { reg ->
        reg.setSessionSnapshots(
          object : ServeSessionRegistry.SessionSnapshots {
            override fun capture(sessionId: String, host: ServeHost) {
              held += sessionId
            }

            override fun discard(sessionId: String) {
              held -= sessionId
            }
          }
        )
        assertNotNull(reg.acquire("rev1"))
        clock.set(200)
        assertEquals(1, reg.suspendIdle())
        assertEquals(setOf("rev1"), held, "suspended, so a snapshot is held for it")

        clock.set(1_500)
        assertEquals(1, reg.reclaimIdleForked(), "past the GC window → reclaimed")
        assertEquals(emptySet(), held, "and the GC discarded its snapshot with it")
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
