package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The branch-read counters ([BranchFetchStats]).
 *
 * These exist because renders had failure telemetry and branch reads — the lane that actually talks
 * to GitHub — had none, so "is GitHub rate-limiting us, or was that asset never published?" could
 * only be answered by reproducing it by hand. The assertions below are all about keeping those two
 * answers separable on a status page.
 */
class BranchFetchStatsTest {

  @Test
  fun `a quiet server advertises nothing`() {
    // Not a block of zeros: the same choice the render roll-up makes. "Nothing has been read" and
    // "everything read succeeded" are different, and a status page should not blur them.
    assertNull(BranchFetchStats().snapshot())
  }

  @Test
  fun `a throttle is counted apart from a missing file`() {
    // The whole point. A rate limit and a genuinely absent asset were the same `null` before
    // BranchFetch; if they were the same counter here, the status page would be back to useless.
    val stats = BranchFetchStats(clock = { 1_000L })
    stats.record(BranchFetch.Ok(byteArrayOf(1)))
    stats.record(BranchFetch.NotFound)
    stats.record(BranchFetch.NotFound)
    stats.record(BranchFetch.Throttled(5))

    val snap = stats.snapshot()!!
    assertEquals(4, snap.attempted)
    assertEquals(1, snap.ok)
    assertEquals(2, snap.notFound)
    assertEquals(1, snap.throttled)
    assertEquals(0, snap.unavailable)
    assertEquals(1_000L, snap.lastThrottleAtEpochMillis)
  }

  @Test
  fun `a missing file is not recorded as a failure`() {
    // A catalog legitimately declares assets a given revision never published. Folding those into
    // the failure fields would keep the alert-worthy numbers permanently non-zero, which is the
    // same as having no alert.
    val stats = BranchFetchStats(clock = { 7L })
    stats.record(BranchFetch.NotFound)

    val snap = stats.snapshot()!!
    assertEquals(1, snap.notFound)
    assertNull(snap.lastFailureAtEpochMillis, "an absent asset is the expected case, not a fault")
    assertNull(snap.lastFailureReason)
  }

  @Test
  fun `a size refusal is counted but not alarmed on`() {
    // `tooLarge`'s own documentation says it is "not a fault of the branch host the way `throttled`
    // and `unavailable` are", and names those three as the ones to alert on — but the
    // `lastFailure*`
    // fields, which exist to describe the branch host, recorded it anyway. A catalog publishing one
    // large asset then showed up as this server's last failure, with a reason and a timestamp that
    // read as an incident.
    //
    // It belongs with `notFound`: a fact about what the producer published, worth its own counter
    // and not worth waking anyone.
    val stats = BranchFetchStats(clock = { 9L })
    stats.record(BranchFetch.TooLarge(25L * 1024 * 1024))

    val snap = stats.snapshot()!!
    assertEquals(1, snap.tooLarge, "the refusal must still be visible as its own number")
    assertNull(snap.lastFailureAtEpochMillis, "a large published asset is not a branch-host fault")
    assertNull(snap.lastFailureReason)
  }

  @Test
  fun `the branch host's own failures carry a reason and a time`() {
    val stats = BranchFetchStats(clock = { 42L })
    stats.record(BranchFetch.Unavailable(503))

    val snap = stats.snapshot()!!
    assertEquals(1, snap.unavailable)
    assertEquals(42L, snap.lastFailureAtEpochMillis)
    val reason = assertNotNull(snap.lastFailureReason)
    assertTrue(reason.contains("503"), reason)
    assertNull(snap.lastThrottleAtEpochMillis, "a 503 is not a rate limit")
  }

  @Test
  fun `a read counts once, by how it ended`() {
    // The counters sit above the transport's retry loop — which is what lets them count an injected
    // transport at all — so a throttle the retry rescued arrives here as `ok`. Asserted rather than
    // left implicit, because `throttled: 0` means "none we could not ride out", not "none met".
    val stats = BranchFetchStats()
    stats.record(BranchFetch.Ok(byteArrayOf(1)))
    val snap = stats.snapshot()!!
    assertEquals(1, snap.attempted)
    assertEquals(1, snap.ok)
    assertEquals(0, snap.throttled)
  }

  @Test
  fun `a failure reason is bounded before it reaches a status page`() {
    val stats = BranchFetchStats()
    stats.record(BranchFetch.Transport("x".repeat(BranchFetchStats.MAX_REASON_CHARS * 3)))
    val reason = stats.snapshot()!!.lastFailureReason!!
    assertTrue(reason.length <= BranchFetchStats.MAX_REASON_CHARS, "length ${reason.length}")
  }
}
