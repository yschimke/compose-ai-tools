package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the per-caller budget from issue #3214 — the bound the playground was missing, where every
 * other bound it has is a whole-host one.
 */
class ServeRateLimiterTest {

  private var now = 0L

  private fun limiter(
    permits: Int = 3,
    windowSeconds: Long = 60,
    maxConcurrent: Int = 1,
    maxKeys: Int = ServeRateLimiter.DEFAULT_MAX_KEYS,
  ) =
    ServeRateLimiter(
      permitsPerWindow = permits,
      windowSeconds = windowSeconds,
      maxConcurrent = maxConcurrent,
      maxKeys = maxKeys,
      clock = { now },
    )

  private fun ServeRateLimiter.take(key: String): ServeRateLimiter.Decision.Admitted? =
    (tryAcquire(key) as? ServeRateLimiter.Decision.Admitted)

  /** Take a permit and give it straight back — one completed unit of work. */
  private fun ServeRateLimiter.cycle(key: String): Boolean {
    val permit = take(key) ?: return false
    permit.release()
    return true
  }

  @Test
  fun `a caller may burst the whole window, then is paced`() {
    val l = limiter(permits = 3)
    repeat(3) { assertTrue(l.cycle("gh:alice"), "burst request ${it + 1} should be admitted") }
    val refused = l.tryAcquire("gh:alice")
    assertIs<ServeRateLimiter.Decision.Throttled>(refused)
    assertTrue(refused.reason.contains("rate limit"), refused.reason)
    // A Retry-After of 0 would have the client bounce straight back; the wait is always ≥ 1s.
    assertTrue(refused.retryAfterSeconds >= 1, "retryAfter=${refused.retryAfterSeconds}")
    assertTrue(refused.retryAfterSeconds <= 60, "retryAfter=${refused.retryAfterSeconds}")
  }

  @Test
  fun `the bucket refills continuously, not on a window boundary`() {
    val l = limiter(permits = 3, windowSeconds = 60)
    repeat(3) { l.cycle("gh:alice") }
    // A third of the window buys back exactly one of three permits.
    now += 20_000
    assertTrue(l.cycle("gh:alice"), "one token should have refilled after a third of the window")
    assertTrue(l.tryAcquire("gh:alice") is ServeRateLimiter.Decision.Throttled, "…but only one")
  }

  @Test
  fun `refill never exceeds the bucket's capacity`() {
    val l = limiter(permits = 3)
    l.cycle("gh:alice")
    // Away for an hour — a caller must come back with a full bucket, not an hour's worth of credit.
    now += 3_600_000
    repeat(3) { assertTrue(l.cycle("gh:alice"), "request ${it + 1} after idling") }
    assertTrue(l.tryAcquire("gh:alice") is ServeRateLimiter.Decision.Throttled)
  }

  @Test
  fun `one caller cannot hold every compile slot`() {
    // The issue's actual complaint: two clients issuing back-to-back long compiles hold both host
    // slots indefinitely. A per-caller concurrency of 1 is what stops one of them doing it alone.
    val l = limiter(permits = 100, maxConcurrent = 1)
    val held = assertNotNull(l.take("gh:alice"))
    val second = l.tryAcquire("gh:alice")
    assertIs<ServeRateLimiter.Decision.Throttled>(second)
    assertTrue(second.reason.contains("in flight"), second.reason)
    // …while a different caller is unaffected — this bounds one caller, not the host.
    assertTrue(l.cycle("gh:bob"))
    held.release()
    assertTrue(l.cycle("gh:alice"), "the slot frees when their own work finishes")
  }

  @Test
  fun `releasing twice does not hand back a permit the caller no longer holds`() {
    val l = limiter(permits = 100, maxConcurrent = 1)
    val permit = l.take("gh:alice")!!
    permit.release()
    permit.release()
    // If the double release had decremented twice, inFlight would have gone negative and this
    // caller would silently be allowed two concurrent compiles from then on.
    val first = l.take("gh:alice")
    assertTrue(first != null)
    assertTrue(
      l.tryAcquire("gh:alice") is ServeRateLimiter.Decision.Throttled,
      "concurrency must still be 1 after a double release",
    )
  }

  @Test
  fun `callers are budgeted independently and logins never collide with addresses`() {
    val l = limiter(permits = 1)
    assertTrue(l.cycle("gh:alice"))
    assertTrue(l.tryAcquire("gh:alice") is ServeRateLimiter.Decision.Throttled)
    // A different login, and an address, each get their own budget. The prefixes are what stop a
    // signed-in user inheriting what an anonymous neighbour behind the same NAT just spent.
    assertTrue(l.cycle("gh:bob"))
    assertTrue(l.cycle("ip:10.0.0.1"))
    assertTrue(l.cycle("ip:alice"))
  }

  @Test
  fun `idle callers are swept so a key spray costs bounded memory`() {
    val l = limiter(permits = 2, maxKeys = 4)
    // Four one-shot callers: each spends a token, so none is immediately sweepable.
    (1..4).forEach { assertTrue(l.cycle("ip:10.0.0.$it")) }
    assertEquals(4, l.trackedCallers())
    // Once their buckets have refilled they are indistinguishable from fresh keys, so a new caller
    // reclaims the space instead of being refused.
    now += 60_000
    assertTrue(l.cycle("ip:10.0.0.5"))
    assertTrue(l.trackedCallers() <= 4, "tracked=${l.trackedCallers()}")
  }

  @Test
  fun `a new caller is refused rather than growing the map past its cap`() {
    val l = limiter(permits = 2, maxKeys = 2)
    // Both slots held by callers with work in flight — nothing is sweepable, by construction.
    val a = assertNotNull(l.take("ip:10.0.0.1"))
    val b = assertNotNull(l.take("ip:10.0.0.2"))
    val refused = l.tryAcquire("ip:10.0.0.3")
    assertIs<ServeRateLimiter.Decision.Throttled>(refused)
    assertTrue(refused.reason.contains("active callers"), refused.reason)
    assertEquals(2, l.trackedCallers())
    // …and the space is reclaimable once those callers go quiet.
    a.release()
    b.release()
    now += 60_000
    assertTrue(l.cycle("ip:10.0.0.3"))
  }

  @Test
  fun `activeCallers counts only callers holding a permit right now`() {
    val l = limiter(permits = 10, maxConcurrent = 2)
    assertEquals(0, l.activeCallers())
    val a = l.take("gh:alice")!!
    l.take("gh:alice")
    val b = l.take("gh:bob")!!
    assertEquals(2, l.activeCallers())
    a.release()
    b.release()
    // alice's second permit is still out — she is still active, bob is not.
    assertEquals(1, l.activeCallers())
  }
}
