package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ServeCatalogRefresherTest {

  private fun entry(system: String = "compose-m3") =
    ServeCatalogRefresher.Entry(
      system = system,
      repo = "yschimke/compose-ai-tools",
      branch = "design-artifacts/$system",
    )

  /** What the store hands back for a catalog that registered. */
  private fun ok(incomplete: Boolean = false) =
    ServeCatalogStore.Result.Ok("compose-m3", 1, "trusted", incomplete = incomplete)

  @Test
  fun `reloads only when the branch head changes`() {
    val head = ConcurrentHashMap(mapOf("compose-m3" to "aaaaaaa"))
    val reloads = mutableListOf<String>()
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { sys, _ ->
          reloads += sys
          ok()
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> head["compose-m3"] },
        onLog = {},
      )
    r.seedInitialHeads()
    r.tick()
    assertEquals(emptyList(), reloads, "unchanged head → no reload")
    head["compose-m3"] = "bbbbbbb"
    r.tick()
    assertEquals(listOf("compose-m3"), reloads, "a moved head triggers exactly one reload")
    r.tick()
    assertEquals(listOf("compose-m3"), reloads, "the same new head does not reload again")
    r.close()
  }

  @Test
  fun `manual refresh reports whether a later version was found`() {
    var head = "aaaaaaa"
    val reloads = mutableListOf<String>()
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { system, _ ->
          reloads += system
          ok()
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> head },
        onLog = {},
      )
    r.seedInitialHeads()

    assertEquals(CatalogRefreshResult.CURRENT, r.refresh("compose-m3"))
    head = "bbbbbbb"
    assertEquals(CatalogRefreshResult.UPDATED, r.refresh("compose-m3"))
    assertEquals(listOf("compose-m3"), reloads)
    assertEquals(CatalogRefreshResult.NOT_FOUND, r.refresh("unknown"))
    r.close()
  }

  @Test
  fun `a failed reload keeps the old head and retries next tick`() {
    var head = "aaaaaaa"
    var succeed = false
    val reloads = AtomicInteger(0)
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { _, _ ->
          reloads.incrementAndGet()
          if (succeed) ok() else null
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> head },
        onLog = {},
      )
    r.seedInitialHeads()
    head = "bbbbbbb"
    r.tick()
    assertEquals(1, reloads.get(), "the moved head is reloaded")
    r.tick()
    assertEquals(2, reloads.get(), "a failed reload didn't advance the head, so it retries")
    succeed = true
    r.tick()
    assertEquals(3, reloads.get(), "still retrying until success")
    r.tick()
    assertEquals(3, reloads.get(), "a successful reload records the head, so it stops reloading")
    r.close()
  }

  @Test
  fun `an unresolvable head is skipped, never reloading`() {
    val reloads = AtomicInteger(0)
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { _, _ ->
          reloads.incrementAndGet()
          ok()
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> null },
        onLog = {},
      )
    r.seedInitialHeads()
    r.tick()
    r.tick()
    assertEquals(0, reloads.get(), "a branch whose head can't be resolved is left exactly as-is")
    r.close()
  }

  @Test
  fun `seedInitialHeads prevents reloading an unchanged branch on the first tick`() {
    val reloads = AtomicInteger(0)
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { _, _ ->
          reloads.incrementAndGet()
          ok()
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> "stable-sha" },
        onLog = {},
      )
    r.seedInitialHeads()
    r.tick()
    assertEquals(
      0,
      reloads.get(),
      "the boot head is recorded, so an unchanged branch isn't reloaded",
    )
    r.close()
  }

  @Test
  fun `a catalog that failed at startup retries without a branch change`() {
    var succeed = false
    val reloads = AtomicInteger(0)
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry("jetnews"), entry("reply")) },
        reload = { system, _ ->
          if (system == "reply") reloads.incrementAndGet()
          if (system == "jetnews" || succeed) ok() else null
        },
        intervalMillis = 1_000,
        headResolver = { _, branch -> "stable-${branch.substringAfterLast('/')}" },
        onLog = {},
      )
    // jetnews loaded at boot; reply did not. Seed only the usable catalog.
    r.seedInitialHeads(setOf("jetnews"))
    r.tick()
    assertEquals(1, reloads.get(), "the unchanged failed catalog retries on the first tick")
    r.tick()
    assertEquals(2, reloads.get(), "it keeps retrying while unavailable")
    succeed = true
    r.tick()
    assertEquals(3, reloads.get(), "the successful retry records the head")
    r.tick()
    assertEquals(3, reloads.get(), "the stable successful head is no longer retried")
    r.close()
  }

  @Test
  fun `each catalog is tracked independently`() {
    val heads = ConcurrentHashMap(mapOf("compose-m3" to "a1", "cadence" to "c1"))
    val reloads = mutableListOf<String>()
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry("compose-m3"), entry("cadence")) },
        reload = { sys, _ ->
          reloads += sys
          ok()
        },
        intervalMillis = 1_000,
        headResolver = { _, branch -> heads[branch.substringAfterLast('/')] },
        onLog = {},
      )
    r.seedInitialHeads()
    r.tick()
    assertEquals(emptyList(), reloads)
    // Only cadence moves — compose-m3 must not be re-fetched.
    heads["cadence"] = "c2"
    r.tick()
    assertEquals(listOf("cadence"), reloads, "only the changed catalog reloads")
    r.close()
  }

  @Test
  fun `an incomplete reload keeps being retried until it comes back complete`() {
    // The catalog IS the new revision — it registered and it is serving. What it is not is settled:
    // something optional could not be fetched *right now*. Recording the head here is what used to
    // make that permanent, so one throttled request cost a catalog its issue index, or its whole
    // acceptance surface, until somebody published again.
    val reloads = AtomicInteger(0)
    var outcome: ServeCatalogStore.Result? = ok(incomplete = true)
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { _, _ ->
          reloads.incrementAndGet()
          outcome
        },
        intervalMillis = 1_000,
        headResolver = { _, _ -> "stable-sha" },
        onLog = {},
      )
    // Not seeded: this stands in for a branch that moved and was then read incompletely.
    r.tick()
    assertEquals(1, reloads.get())
    // The head has not moved, and an unchanged head normally short-circuits — but the revision was
    // never recorded, so it is re-read.
    r.tick()
    assertEquals(2, reloads.get(), "an incomplete revision is re-read on the next tick")

    outcome = ok()
    r.tick()
    assertEquals(3, reloads.get())
    r.tick()
    assertEquals(3, reloads.get(), "once complete, the unchanged head short-circuits again")
    r.close()
  }

  @Test
  fun `an incomplete reload still reports that the catalog was updated`() {
    // It withholds the head, not the truth: the catalog really is the newer revision, and a manual
    // refresh that answered FAILED would tell an operator their catalog had not moved when it had.
    val r =
      ServeCatalogRefresher(
        entries = { listOf(entry()) },
        reload = { _, _ -> ok(incomplete = true) },
        intervalMillis = 1_000,
        headResolver = { _, _ -> "stable-sha" },
        onLog = {},
      )
    assertEquals(CatalogRefreshResult.UPDATED, r.refresh("compose-m3"))
    r.close()
  }
}
