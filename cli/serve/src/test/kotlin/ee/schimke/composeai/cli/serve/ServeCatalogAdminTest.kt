package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Runtime catalog administration ([ServeCatalogAdmin]): publishing and retiring catalogs on a
 * running server, and persisting the result to the operator's `catalogs.json`. The fetch and the
 * session registry are stubbed — what's under test is the decision-making and the bookkeeping.
 */
class ServeCatalogAdminTest {

  private val fs = FakeFileSystem()
  private val path = "/config/catalogs.json".toPath()
  private val file = ServeCatalogsConfigFile(path, fs)

  private val loaded = mutableListOf<Pair<String, String>>()
  private val unloaded = mutableListOf<String>()

  private fun tracker(vararg configured: CatalogLoadTracker.Config) =
    CatalogLoadTracker(configured.toList())

  private fun admin(
    tracker: CatalogLoadTracker,
    groups: List<ServeCatalogsConfig.Group> =
      listOf(ServeCatalogsConfig.Group("ds", "Design Systems", "design system(s)")),
    failWith: String? = null,
    configFile: ServeCatalogsConfigFile? = file,
  ) =
    ServeCatalogAdmin(
      tracker = tracker,
      defaultRepo = "yschimke/compose-ai-tools",
      branchPrefix = "design-artifacts/",
      configFile = configFile,
      groups = groups,
      load = { system, repo ->
        loaded += system to repo
        failWith
      },
      unload = { unloaded += it },
      onLog = {},
    )

  private fun seedConfig(config: ServeCatalogsConfig) = file.save(config)

  @Test
  fun `publishing a catalog fetches it, tracks it, and writes it back to the config`() {
    seedConfig(ServeCatalogsConfig(groups = listOf(ServeCatalogsConfig.Group("ds", "Design"))))
    val tracker = tracker()
    val result =
      admin(tracker)
        .register(ServeCatalogsConfig.Entry(system = "cadence", repo = "yschimke/cadence"))

    assertEquals(ServeCatalogAdmin.Result.Ok("cadence", warning = null), result)
    assertEquals(listOf("cadence" to "yschimke/cadence"), loaded)
    assertEquals(listOf("cadence"), tracker.snapshot().map { it.config.system })
    assertEquals("design-artifacts/cadence", tracker.configFor("cadence")?.branch)
    // Persisted, so the catalog is still served after a restart — and the operator's groups stay.
    val saved = file.load()
    assertEquals(listOf("cadence"), saved.catalogs.map { it.system })
    assertEquals(listOf("ds"), saved.groups.map { it.id })
  }

  @Test
  fun `an entry with no repo falls back to the server's catalog repo`() {
    val tracker = tracker()
    admin(tracker).register(ServeCatalogsConfig.Entry(system = "compose-m3"))

    assertEquals(listOf("compose-m3" to "yschimke/compose-ai-tools"), loaded)
    assertEquals("yschimke/compose-ai-tools", file.load().catalogs.single().repo)
  }

  @Test
  fun `a declared group is carried through, scoped to the entry's own repos`() {
    val tracker = tracker()
    admin(tracker)
      .register(
        ServeCatalogsConfig.Entry(
          system = "jetnews",
          repo = "yschimke/compose-samples",
          group = "ds",
          attributionRepos = listOf("android/compose-samples"),
        )
      )

    val group = assertNotNull(tracker.configFor("jetnews")?.group)
    assertEquals("Design Systems", group.heading)
    assertEquals("design system(s)", group.noun)
    assertEquals(setOf("android/compose-samples", "yschimke/compose-samples"), group.repos)
  }

  @Test
  fun `an unknown group, a bad id, and a bad repo are all rejected before any fetch`() {
    val tracker = tracker()
    val a = admin(tracker)
    val rejected =
      listOf(
        ServeCatalogsConfig.Entry("ok", group = "nope"),
        ServeCatalogsConfig.Entry("../escape"),
        ServeCatalogsConfig.Entry("ok", repo = "no-slash"),
      )

    rejected.forEach {
      assertTrue(a.register(it) is ServeCatalogAdmin.Result.Invalid, "rejected: $it")
    }
    assertEquals(emptyList(), loaded)
    assertEquals(emptyList(), tracker.snapshot())
  }

  @Test
  fun `re-publishing a served catalog is a conflict, not a silent overwrite`() {
    // Seeded, so this is a catalog that really is already published: the file and the runtime agree
    // on the repo. When they DISAGREE the same request is a repair rather than a conflict — see
    // `a swap whose persistence failed can be repaired by re-posting the same entry`.
    seedConfig(ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry("compose-m3"))))
    val tracker =
      tracker(CatalogLoadTracker.Config("compose-m3", true, "yschimke/compose-ai-tools", "b"))

    val result = admin(tracker).register(ServeCatalogsConfig.Entry("compose-m3"))

    assertTrue(result is ServeCatalogAdmin.Result.Conflict, "$result")
    assertEquals(emptyList(), loaded, "the running catalog is never re-fetched behind its back")
  }

  @Test
  fun `re-publishing with a new load priority converges it instead of conflicting`() {
    seedConfig(ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry("m3-catalog"))))
    val tracker =
      tracker(CatalogLoadTracker.Config("m3-catalog", true, "yschimke/compose-ai-tools", "b"))
    tracker.recordSuccess("m3-catalog")

    val result = admin(tracker).register(ServeCatalogsConfig.Entry("m3-catalog", loadPriority = 20))

    assertTrue(result is ServeCatalogAdmin.Result.Ok, "$result")
    assertEquals(emptyList(), loaded, "the running catalog is never re-fetched behind its back")
    assertTrue(tracker.snapshot().single().available, "and keeps its load state")
    assertEquals(20, tracker.configFor("m3-catalog")?.loadPriority)
    // The point of the write-back: it is the NEXT boot's fetch order that changes.
    assertEquals(20, file.load().catalogs.single().loadPriority)
  }

  @Test
  fun `a catalog that will not fetch is not left half-published`() {
    val tracker = tracker()
    val result =
      admin(tracker, failWith = "branch not found")
        .register(ServeCatalogsConfig.Entry("ghost", repo = "someorg/ghost"))

    assertEquals(ServeCatalogAdmin.Result.Failed("ghost", "branch not found"), result)
    assertEquals(emptyList(), tracker.snapshot(), "the tracker entry is rolled back")
    assertEquals(listOf("ghost"), unloaded)
    assertEquals(emptyList(), file.load().catalogs, "and nothing reaches the config file")
  }

  @Test
  fun `retiring a catalog drops its session and its config entry`() {
    seedConfig(
      ServeCatalogsConfig(
        catalogs =
          listOf(
            ServeCatalogsConfig.Entry("compose-m3"),
            ServeCatalogsConfig.Entry("cadence", listed = false),
          )
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config("compose-m3", true, "yschimke/compose-ai-tools", "b1"),
        CatalogLoadTracker.Config("cadence", false, "yschimke/cadence", "b2"),
      )

    val result = admin(tracker).unregister("cadence")

    assertEquals(ServeCatalogAdmin.Result.Ok("cadence", warning = null), result)
    assertEquals(listOf("compose-m3"), tracker.snapshot().map { it.config.system })
    assertEquals(listOf("cadence"), unloaded)
    assertEquals(listOf("compose-m3"), file.load().catalogs.map { it.system })
  }

  @Test
  fun `retiring an unknown catalog is a conflict`() {
    val result = admin(tracker()).unregister("never-served")

    assertTrue(result is ServeCatalogAdmin.Result.Conflict)
    assertEquals(emptyList(), unloaded)
  }

  @Test
  fun `with no config file the catalog serves but the caller is told it will not persist`() {
    val tracker = tracker()
    val result = admin(tracker, configFile = null).register(ServeCatalogsConfig.Entry("compose-m3"))

    val ok = result as ServeCatalogAdmin.Result.Ok
    assertNotNull(ok.warning, "a runtime-only registration says so rather than pretending")
    assertEquals(listOf("compose-m3"), tracker.snapshot().map { it.config.system })
  }

  @Test
  fun `concurrent registrations all survive in the config file`() {
    // Each mutation is a read-modify-write of one file. Without serialising the WHOLE sequence,
    // two requests load the same document, apply their own edit, and the second atomic move
    // silently drops the first — both callers having been told "ok". Registrations are slowed
    // (the load callback sleeps) so the interleaving is real rather than hoped for.
    val tracker = tracker()
    val admin =
      ServeCatalogAdmin(
        tracker = tracker,
        defaultRepo = "yschimke/compose-ai-tools",
        branchPrefix = "design-artifacts/",
        configFile = file,
        load = { _, _ ->
          Thread.sleep(20)
          null
        },
        unload = {},
        onLog = {},
      )
    val systems = (1..8).map { "cat-$it" }

    val threads = systems.map { system ->
      Thread { admin.register(ServeCatalogsConfig.Entry(system, "someorg/$system")) }
        .apply { start() }
    }
    threads.forEach { it.join() }

    assertEquals(systems.toSet(), file.load().catalogs.map { it.system }.toSet())
    assertEquals(systems.toSet(), tracker.snapshot().map { it.config.system }.toSet())
  }

  @Test
  fun `a repo change is a swap - it loads first, keeps its place, and retires nothing`() {
    seedConfig(
      ServeCatalogsConfig(
        groups = listOf(ServeCatalogsConfig.Group("ds", "Design Systems", "design system(s)")),
        catalogs =
          listOf(
            ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/compose-ai-tools"),
            ServeCatalogsConfig.Entry("cadence", repo = "yschimke/cadence"),
          ),
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config(
          "remote-m3",
          true,
          "yschimke/compose-ai-tools",
          "design-artifacts/remote-m3",
        ),
        CatalogLoadTracker.Config("cadence", false, "yschimke/cadence", "design-artifacts/cadence"),
      )
    tracker.recordSuccess("remote-m3")

    val result =
      admin(tracker)
        .register(
          ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/wear-m3-catalog", listed = true)
        )

    assertEquals(ServeCatalogAdmin.Result.Ok("remote-m3", warning = null), result)
    // Loaded from the NEW repo, and nothing was retired on the way — the old registration was
    // replaced in place rather than dropped and rebuilt.
    assertEquals(listOf("remote-m3" to "yschimke/wear-m3-catalog"), loaded)
    assertEquals(emptyList(), unloaded)
    // Provenance moved…
    val config = assertNotNull(tracker.configFor("remote-m3"))
    assertEquals("yschimke/wear-m3-catalog", config.repo)
    assertEquals("design-artifacts/remote-m3", config.branch)
    // …and its place on the front page did not.
    assertEquals(listOf("remote-m3", "cadence"), tracker.snapshot().map { it.config.system })
    assertEquals(
      "yschimke/wear-m3-catalog",
      file.load().catalogs.single { it.system == "remote-m3" }.repo,
    )
  }

  @Test
  fun `a repo change that cannot be fetched leaves the old catalog serving`() {
    seedConfig(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/old-home"))
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config(
          "remote-m3",
          true,
          "yschimke/old-home",
          "design-artifacts/remote-m3",
        )
      )
    // Actually serving, which is what makes the message below true. See the sibling test for the
    // case where it is not.
    tracker.recordSuccess("remote-m3")

    val result =
      admin(tracker, failWith = "could not fetch catalog.json")
        .register(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/nowhere", listed = true))

    // The whole reason the load runs first: a source that cannot be fetched costs nothing. This is
    // the case that, under retire-then-publish, left the system published NOWHERE.
    assertTrue(result is ServeCatalogAdmin.Result.Failed, "$result")
    assertTrue(
      (result as ServeCatalogAdmin.Result.Failed)
        .reason
        .contains("still serving yschimke/old-home"),
      result.reason,
    )
    assertEquals(emptyList(), unloaded)
    assertEquals("yschimke/old-home", assertNotNull(tracker.configFor("remote-m3")).repo)
    assertEquals("yschimke/old-home", file.load().catalogs.single().repo)
  }

  @Test
  fun `a failed re-point does not claim an unavailable old catalog is still serving`() {
    // `configFor` answers for a pending entry and for one whose initial load failed exactly as it
    // does for a live one, so the failure message was the reassuring half of a two-part answer that
    // could be wrong: reconciliation during startup, or a persistent startup fetch failure, reaches
    // here with nothing at all behind the old configuration.
    seedConfig(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/old-home"))
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config(
          "remote-m3",
          true,
          "yschimke/old-home",
          "design-artifacts/remote-m3",
        )
      )
    // Never loaded — `available` is false and no session is behind it.
    assertEquals("pending", assertNotNull(tracker.stateFor("remote-m3")).loadState)

    val result =
      admin(tracker, failWith = "could not fetch catalog.json")
        .register(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/nowhere", listed = true))

    val reason = (assertIs<ServeCatalogAdmin.Result.Failed>(result)).reason
    assertFalse(reason.contains("still serving"), reason)
    assertTrue(reason.contains("kept the yschimke/old-home configuration"), reason)
    assertTrue(reason.contains("not currently serving"), reason)
    // The old configuration IS preserved, which is the part that was true all along.
    assertEquals("yschimke/old-home", assertNotNull(tracker.configFor("remote-m3")).repo)
  }

  @Test
  fun `a swap whose persistence failed can be repaired by re-posting the same entry`() {
    // The dead end this closes. A runtime swap whose `persist` failed transiently answers
    // Ok-with-warning, leaving the tracker on the new repo and `catalogs.json` on the old one.
    // Every later POST of the same desired entry then matched on every TRACKED field and returned
    // 409 before attempting persistence — so reconciliation could never repair the file, and the
    // next restart reverted the catalog to the repo it had left.
    seedConfig(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/old-home"))
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config(
          "remote-m3",
          true,
          "yschimke/new-home",
          "design-artifacts/remote-m3",
        )
      )
    tracker.recordSuccess("remote-m3")

    val entry = ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/new-home", listed = true)
    val result = admin(tracker).register(entry)

    assertEquals(ServeCatalogAdmin.Result.Ok("remote-m3", warning = null), result)
    assertEquals("yschimke/new-home", file.load().catalogs.single().repo)
    // Nothing was re-fetched: the runtime half was already correct and only the file was behind.
    assertEquals(emptyList(), loaded)

    // And now that the two agree, the same POST is the ordinary conflict again.
    assertEquals(
      ServeCatalogAdmin.Result.Conflict("catalog 'remote-m3' is already published"),
      admin(tracker).register(entry),
    )
  }

  @Test
  fun `a refresh captured before a re-point no longer points at the tracked catalog`() {
    // The refresher's half of the same race. It captures each entry's repo when it snapshots the
    // tracker; "does the system still exist" was the only test it made after finally getting the
    // registration monitor, and that is true either side of a re-point.
    val tracker =
      tracker(
        CatalogLoadTracker.Config("remote-m3", true, "yschimke/old-home", "design-artifacts/x")
      )
    assertTrue(tracker.stillPointsAt("remote-m3", "yschimke/old-home"))

    tracker.repoint("remote-m3", repo = "yschimke/new-home", branch = "design-artifacts/x")

    assertFalse(
      tracker.stillPointsAt("remote-m3", "yschimke/old-home"),
      "a pass captured before the swap must not reload the repo the catalog has left",
    )
    assertTrue(tracker.stillPointsAt("remote-m3", "yschimke/new-home"))
    assertFalse(tracker.stillPointsAt("gone", "yschimke/new-home"), "and a retired system is gone")
  }

  @Test
  fun `a re-point holds the registration lock across its provenance record`() {
    // The branch refresher captures each entry's repo when it snapshots the tracker, then queues on
    // the registration monitor. With the load inside the lock and `repoint` outside it, the
    // refresher could get in between, still read the OLD repo, and reload it over the new
    // registration — the old repo's host serving while the provenance and `catalogs.json` both
    // named the new one.
    seedConfig(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry("remote-m3", repo = "yschimke/old-home"))
      )
    )
    val tracker =
      tracker(
        CatalogLoadTracker.Config(
          "remote-m3",
          true,
          "yschimke/old-home",
          "design-artifacts/remote-m3",
        )
      )
    tracker.recordSuccess("remote-m3")

    val lock = Any()
    // What the refresher reads once it finally gets the monitor.
    val seenByRefresher = java.util.concurrent.LinkedBlockingQueue<String>()
    val loadEntered = java.util.concurrent.CountDownLatch(1)
    val admin =
      ServeCatalogAdmin(
        tracker = tracker,
        defaultRepo = "yschimke/compose-ai-tools",
        branchPrefix = "design-artifacts/",
        configFile = file,
        registrationLock = lock,
        load = { _, _ ->
          // The real `load` takes this same monitor; reentrancy is what makes the wider section
          // legal at all.
          synchronized(lock) {
            loadEntered.countDown()
            Thread.sleep(50)
            null
          }
        },
        unload = {},
        onLog = {},
      )

    val refresher = Thread {
      loadEntered.await()
      synchronized(lock) { seenByRefresher.add(assertNotNull(tracker.configFor("remote-m3")).repo) }
    }
      .apply { start() }

    val result = admin.register(ServeCatalogsConfig.Entry("remote-m3", "yschimke/new-home"))
    refresher.join(5_000)

    assertEquals(ServeCatalogAdmin.Result.Ok("remote-m3", warning = null), result)
    assertEquals(
      "yschimke/new-home",
      seenByRefresher.poll(),
      "the first read that gets the monitor after the load must already see the new provenance",
    )
  }

  @Test
  fun `the tracker keeps configured order and reports what is served`() {
    val tracker = tracker(CatalogLoadTracker.Config("a", true, "o/r", "b"))
    tracker.recordSuccess("a")
    assertTrue(tracker.add(CatalogLoadTracker.Config("b", false, "o/r", "b")))
    assertFalse(tracker.add(CatalogLoadTracker.Config("b", false, "o/r", "b")))

    assertEquals(listOf("a", "b"), tracker.snapshot().map { it.config.system })
    assertEquals("loaded", tracker.snapshot().first().loadState)
    assertEquals("pending", tracker.snapshot().last().loadState)

    assertTrue(tracker.remove("a"))
    assertFalse(tracker.remove("a"))
    assertEquals(listOf("b"), tracker.snapshot().map { it.config.system })
    assertNull(tracker.configFor("a"))
  }
}
