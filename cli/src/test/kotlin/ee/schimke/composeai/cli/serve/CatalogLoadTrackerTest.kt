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
  fun `a later success clears the error and satisfies completeness`() {
    val tracker = tracker()
    tracker.recordSuccess("jetnews")
    tracker.recordFailure("reply", "network unavailable")
    tracker.recordSuccess("reply")

    assertTrue(tracker.allAvailable())
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
}
