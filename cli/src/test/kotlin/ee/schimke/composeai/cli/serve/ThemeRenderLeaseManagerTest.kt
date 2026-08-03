package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeRenderLeaseManagerTest {
  private var now = 1_000L
  private var tokenNumber = 0

  private fun manager(serverSlots: Int = 8) =
    ThemeRenderLeaseManager(
      serverRenderSlots = serverSlots,
      clock = { now },
      tokenSource = { "token-${++tokenNumber}" },
    )

  @Test
  fun `two leases are active server-wide and each is bound to session and host identity`() {
    val manager = manager()
    val host = Any()
    val grant = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))

    // A second page gets the narrower tier rather than falling back to the serial baseline…
    val second = assertNotNull(manager.acquire("material", Any(), requestedCapacity = 5))
    assertEquals(3, second.concurrency)
    // …and a third finds no free tier.
    assertNull(manager.acquire("third", Any(), requestedCapacity = 5))
    assertNull(manager.admit(grant.token, "material", host))
    assertNull(manager.admit(grant.token, "wear", Any()))
    assertNotNull(manager.admit(grant.token, "wear", host)).close()
  }

  @Test
  fun `grant is capped by requested capacity server slots and the burst ceiling`() {
    assertEquals(3, manager().acquire("app", Any(), requestedCapacity = 3)?.concurrency)
    assertEquals(2, manager(serverSlots = 2).acquire("app", Any(), 5)?.concurrency)
    assertEquals(5, manager(serverSlots = 20).acquire("app", Any(), 20)?.concurrency)
  }

  @Test
  fun `capacity at the serial baseline is denied`() {
    assertNull(manager().acquire("app", Any(), requestedCapacity = 1))
    assertNull(manager(serverSlots = 1).acquire("app", Any(), requestedCapacity = 5))
  }

  @Test
  fun `admission cannot exceed the granted concurrency and permits close idempotently`() {
    val manager = manager()
    val host = Any()
    val grant = assertNotNull(manager.acquire("app", host, requestedCapacity = 3))
    val permits = List(3) { assertNotNull(manager.admit(grant.token, "app", host)) }

    assertNull(manager.admit(grant.token, "app", host))
    permits.first().close()
    permits.first().close()
    assertNotNull(manager.admit(grant.token, "app", host)).close()
    permits.drop(1).forEach { it.close() }
  }

  @Test
  fun `release drains in-flight work before permitting a replacement`() {
    val manager = manager()
    // Occupy the narrow tier so the assertions below are about the released tier draining, not
    // about a spare tier being handed out.
    manager.acquire("filler", Any(), requestedCapacity = 5)
    val host = Any()
    val grant = assertNotNull(manager.acquire("app", host, requestedCapacity = 5))
    val permit = assertNotNull(manager.admit(grant.token, "app", host))

    assertTrue(manager.release(grant.token))
    assertTrue(manager.release(grant.token), "release is idempotent while draining")
    assertNull(manager.admit(grant.token, "app", host))
    assertNull(manager.acquire("other", Any(), requestedCapacity = 5))

    permit.close()
    assertNotNull(manager.acquire("other", Any(), requestedCapacity = 5))
    assertFalse(manager.release("unknown"))
  }

  @Test
  fun `expiry rejects new admits and allows replacement once no work is in flight`() {
    val manager = manager()
    val host = Any()
    val grant = assertNotNull(manager.acquire("app", host, requestedCapacity = 5))
    assertEquals(now + ThemeRenderLeaseManager.TTL_MILLIS, grant.expiresAtMillis)

    now = grant.expiresAtMillis
    assertNull(manager.admit(grant.token, "app", host))
    assertNotNull(manager.acquire("other", Any(), requestedCapacity = 5))
  }

  @Test
  fun `expired lease with in-flight work drains before replacement`() {
    val manager = manager()
    // Both tiers are occupied with work in flight, so no tier can be handed out until they drain.
    val fillerHost = Any()
    val filler = assertNotNull(manager.acquire("filler", fillerHost, requestedCapacity = 5))
    val fillerPermit = assertNotNull(manager.admit(filler.token, "filler", fillerHost))
    val host = Any()
    val grant = assertNotNull(manager.acquire("app", host, requestedCapacity = 5))
    val permit = assertNotNull(manager.admit(grant.token, "app", host))

    now = grant.expiresAtMillis
    assertNull(manager.admit(grant.token, "app", host))
    assertNull(manager.acquire("other", Any(), requestedCapacity = 5))

    permit.close()
    fillerPermit.close()
    assertNotNull(manager.acquire("other", Any(), requestedCapacity = 5))
  }
}
