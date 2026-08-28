package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogLoadTrackerTest {

  private fun tracker() =
    CatalogLoadTracker(
      listOf(
        CatalogLoadTracker.Config(
          system = "jetnews",
          listed = true,
          repo = "yschimke/compose-samples",
          branch = "design-artifacts/jetnews",
        ),
        CatalogLoadTracker.Config(
          system = "reply",
          listed = true,
          repo = "yschimke/compose-samples",
          branch = "design-artifacts/reply",
        ),
      ),
      clock = { 123L },
    )

  @Test
  fun `initial failures remain visible and block completeness`() {
    val tracker = tracker()
    tracker.recordSuccess("jetnews")
    tracker.recordFailure("reply", "could not parse catalog.json\nstack trace")

    assertFalse(tracker.allAvailable())
    assertEquals(setOf("jetnews"), tracker.availableSystems())
    assertEquals("catalogs 1/2 loaded; failed: reply", tracker.startupSummary())
    val reply = tracker.snapshot().single { it.config.system == "reply" }
    assertEquals("failed", reply.loadState)
    assertEquals("could not parse catalog.json", reply.error)
    assertEquals(123L, reply.lastAttemptEpochMillis)
  }

  @Test
  fun `configured catalogs start pending before the first async load records an attempt`() {
    val tracker = tracker()

    assertFalse(tracker.allAvailable())
    assertEquals("catalogs 0/2 loaded", tracker.startupSummary())
    assertEquals(listOf("pending", "pending"), tracker.snapshot().map { it.loadState })
    assertEquals(listOf(null, null), tracker.snapshot().map { it.lastAttemptEpochMillis })
  }

  @Test
  fun `a later success clears the error and satisfies completeness`() {
    val tracker = tracker()
    tracker.recordSuccess("jetnews")
    tracker.recordFailure("reply", "network unavailable")
    tracker.recordSuccess("reply")

    assertTrue(tracker.allAvailable())
    assertEquals("jetnews", tracker.firstAvailableSystem())
    val reply = tracker.snapshot().single { it.config.system == "reply" }
    assertEquals("loaded", reply.loadState)
    assertEquals(null, reply.error)
  }

  @Test
  fun `a refresh failure keeps the last good copy available but reports stale`() {
    val tracker = tracker()
    tracker.recordSuccess("jetnews")
    tracker.recordSuccess("reply")
    tracker.recordFailure("reply", "new branch content is malformed")

    assertTrue(tracker.allAvailable(), "the staged refresh retains the prior usable copy")
    val reply = tracker.snapshot().single { it.config.system == "reply" }
    assertTrue(reply.available)
    assertEquals("stale", reply.loadState)
    assertEquals("new branch content is malformed", reply.error)
  }

  @Test
  fun `load order is by priority, leaving configured order to the front page`() {
    val tracker = tracker()
    tracker.add(
      CatalogLoadTracker.Config(
        system = "m3-catalog",
        listed = true,
        repo = "yschimke/m3-catalog",
        branch = "design-artifacts/m3-catalog",
        loadPriority = 20,
      )
    )
    tracker.add(
      CatalogLoadTracker.Config(
        system = "wear-m3-catalog",
        listed = true,
        repo = "yschimke/wear-m3-catalog",
        branch = "design-artifacts/wear-m3-catalog",
        loadPriority = 10,
      )
    )

    // A runtime registration is APPENDED, which is exactly how the two catalogs a box most wants
    // back ended up loading last (#4231): fetch order now leads with them...
    assertEquals(
      listOf("m3-catalog", "wear-m3-catalog", "jetnews", "reply"),
      tracker.loadOrder().map { it.config.system },
    )
    // ...while everything driven by configured order — the front page, and the session
    // firstAvailableSystem hands the readiness probe — is untouched.
    assertEquals(
      listOf("jetnews", "reply", "m3-catalog", "wear-m3-catalog"),
      tracker.snapshot().map { it.config.system },
    )
    tracker.recordSuccess("jetnews")
    tracker.recordSuccess("m3-catalog")
    assertEquals("jetnews", tracker.firstAvailableSystem())
  }

  @Test
  fun `equal priorities keep configured order`() {
    val tracker = tracker()

    assertEquals(listOf("jetnews", "reply"), tracker.loadOrder().map { it.config.system })
  }

  @Test
  fun `re-pointing swaps provenance in place, keeping position and load state`() {
    val tracker = tracker()
    tracker.recordSuccess("jetnews")

    assertTrue(
      tracker.repoint("jetnews", repo = "someorg/new-home", branch = "design-artifacts/jetnews")
    )

    val jetnews = tracker.snapshot().single { it.config.system == "jetnews" }
    assertEquals("someorg/new-home", jetnews.config.repo)
    assertEquals("design-artifacts/jetnews", jetnews.config.branch)
    // The registered copy is untouched — this records where the bytes came from, it does not fetch
    // them, and [ServeCatalogAdmin] only calls it once the new source is already loaded.
    assertTrue(jetnews.available, "a provenance change must not drop the registered copy")
    assertEquals(listOf("jetnews", "reply"), tracker.snapshot().map { it.config.system })
  }

  @Test
  fun `re-pointing a system that is not tracked reports it rather than adding one`() {
    val tracker = tracker()

    assertFalse(tracker.repoint("nope", repo = "someorg/nope", branch = "design-artifacts/nope"))
    assertEquals(listOf("jetnews", "reply"), tracker.snapshot().map { it.config.system })
  }

  @Test
  fun `relisting carries the new load priority without touching load state`() {
    val tracker = tracker()
    tracker.recordSuccess("reply")

    assertTrue(tracker.relist("reply", listed = false, group = null, loadPriority = 5))

    val reply = tracker.snapshot().single { it.config.system == "reply" }
    assertEquals(5, reply.config.loadPriority)
    assertFalse(reply.config.listed)
    assertTrue(reply.available, "a listing change must not drop the registered copy")
    assertEquals(listOf("reply", "jetnews"), tracker.loadOrder().map { it.config.system })
  }
}
