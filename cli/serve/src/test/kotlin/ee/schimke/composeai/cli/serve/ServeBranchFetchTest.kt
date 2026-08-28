package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The branch-read outcome and its retry policy ([BranchFetch]).
 *
 * The property every one of these guards is the same: **a throttle must never be mistaken for a
 * missing file.** That confusion is what made a rate-limited read indistinguishable from an
 * unpublished asset, and it is what let a permanent negative cache remember a transient blip.
 */
class ServeBranchFetchTest {

  @Test
  fun `only a real absence is permanent`() {
    assertEquals(BranchFetch.NotFound, BranchFetch.ofStatus(404))
    assertEquals(BranchFetch.NotFound, BranchFetch.ofStatus(410))
    assertFalse(BranchFetch.NotFound.isTransient, "404 is a fact, not a moment")

    // Everything else is a statement about now, and must stay retryable + uncacheable.
    for (status in listOf(429, 403, 500, 502, 503, 504)) {
      assertTrue(
        BranchFetch.ofStatus(status).isTransient,
        "$status must not be treated as a missing asset",
      )
    }
  }

  @Test
  fun `403 is read as a throttle, not an absence`() {
    // GitHub answers 403 for some rate-limit conditions. Guessing wrong in this direction costs one
    // wasted retry; guessing wrong the other way caches a throttle as a permanently missing file.
    assertTrue(BranchFetch.ofStatus(403) is BranchFetch.Throttled)
  }

  @Test
  fun `a served response carries its bytes and nothing to retry`() {
    val ok = BranchFetch.Ok(byteArrayOf(1, 2, 3))
    assertFalse(ok.isTransient)
    assertNull(BranchFetch.retryDelayMillis(ok, attempt = 1))
    assertEquals(3, ok.bytesOrNull?.size)
    assertNull(BranchFetch.NotFound.bytesOrNull)
  }

  @Test
  fun `a missing file is never retried`() {
    assertNull(
      BranchFetch.retryDelayMillis(BranchFetch.NotFound, attempt = 1),
      "retrying a 404 is how a catalog of absent assets becomes a thundering herd",
    )
  }

  @Test
  fun `backoff grows and then gives up`() {
    val outcome = BranchFetch.Unavailable(503)
    assertEquals(BranchFetch.BASE_BACKOFF_MILLIS, BranchFetch.retryDelayMillis(outcome, 1))
    assertEquals(BranchFetch.BASE_BACKOFF_MILLIS * 2, BranchFetch.retryDelayMillis(outcome, 2))
    assertNull(
      BranchFetch.retryDelayMillis(outcome, BranchFetch.MAX_RETRIES + 1),
      "the attempts are bounded — a request is waiting behind this",
    )
  }

  @Test
  fun `the server's own Retry-After wins when it is longer`() {
    // It is the only party that knows when it will serve again.
    val throttled = BranchFetch.Throttled(retryAfterSeconds = 5)
    assertEquals(5_000L, BranchFetch.retryDelayMillis(throttled, 1))

    // …but not when the exponential step is already longer, and never past the cap: a hostile or
    // confused header must not park a request thread for minutes.
    assertEquals(
      BranchFetch.BASE_BACKOFF_MILLIS,
      BranchFetch.retryDelayMillis(BranchFetch.Throttled(0), 1),
    )
    assertEquals(
      BranchFetch.MAX_RETRY_AFTER_SECONDS * 1000L,
      BranchFetch.retryDelayMillis(BranchFetch.Throttled(9_999), 1),
    )
  }

  @Test
  fun `a 503 that says when to come back is believed too`() {
    // `Retry-After` is defined on 503 as much as on 429 (RFC 9110 10.2.3). Dropping it there meant
    // a host asking for ten seconds got 250ms and 500ms instead — both retries spent inside the
    // outage, and the asset then reported as missing, which is the confusion this type exists
    // to end.
    val outage = BranchFetch.ofStatus(503, retryAfterSeconds = 10)
    assertEquals(BranchFetch.Unavailable(503, 10), outage)
    assertEquals(10_000L, BranchFetch.retryDelayMillis(outage, 1))

    // Same cap as a throttle: a header is advice, not a licence to hold a request thread.
    assertEquals(
      BranchFetch.MAX_RETRY_AFTER_SECONDS * 1000L,
      BranchFetch.retryDelayMillis(BranchFetch.ofStatus(503, 9_999), 1),
    )
    // And with no header it is still the plain exponential schedule.
    assertEquals(
      BranchFetch.BASE_BACKOFF_MILLIS,
      BranchFetch.retryDelayMillis(BranchFetch.ofStatus(503), 1),
    )
  }

  @Test
  fun `Retry-After parses only what it can trust`() {
    assertEquals(7L, BranchFetch.parseRetryAfter("7"))
    assertEquals(7L, BranchFetch.parseRetryAfter("  7 "))
    assertNull(BranchFetch.parseRetryAfter(null))
    assertNull(BranchFetch.parseRetryAfter(""))
    assertNull(BranchFetch.parseRetryAfter("-1"), "a negative delay is not a delay")
    // The HTTP-date form is legal but no branch host we read sends it; unparseable falls back to
    // the exponential schedule rather than guessing.
    assertNull(BranchFetch.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
  }

  @Test
  fun `every outcome says why in one line`() {
    assertEquals("not found", BranchFetch.NotFound.summary)
    assertEquals("throttled (retry after 3s)", BranchFetch.Throttled(3).summary)
    assertEquals("throttled", BranchFetch.Throttled(null).summary)
    assertEquals("unavailable (503)", BranchFetch.Unavailable(503).summary)
    assertEquals(
      "transport: SocketTimeoutException",
      BranchFetch.Transport("SocketTimeoutException").summary,
    )
  }
}
