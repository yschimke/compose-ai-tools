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
  fun `users and tabs share one catalog allocation while another catalog gets the narrow tier`() {
    val manager = manager()
    val host = Any()
    val grant = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))
    val sameCatalog = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))
    assertEquals(5, sameCatalog.concurrency)

    // A second catalog gets the narrower tier rather than falling back to the serial baseline…
    val second = assertNotNull(manager.acquire("material", Any(), requestedCapacity = 5))
    assertEquals(3, second.concurrency)
    // …and a third finds no free tier.
    assertNull(manager.acquire("third", Any(), requestedCapacity = 5))
    assertNull(manager.admit(grant.token, "material", host))
    assertNull(manager.admit(grant.token, "wear", Any()))
    val sharedPermits =
      List(3) { assertNotNull(manager.admit(grant.token, "wear", host)) } +
        List(2) { assertNotNull(manager.admit(sameCatalog.token, "wear", host)) }
    assertNull(
      manager.admit(sameCatalog.token, "wear", host),
      "all page claims share the catalog's five in-flight slots",
    )
    sharedPermits.forEach { it.close() }
  }

  @Test
  fun `a full claim and a reaped one are different answers`() {
    // The caller has to tell them apart. A saturated claim is alive and its holder should come
    // back, so refusing that render is honest. An unknown one never admits anything again, so
    // refusing THAT one strands the page: it falls back to the serial unleased lane instead.
    val manager = manager()
    val host = Any()
    val grant = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))

    val permits = List(5) { assertNotNull(manager.admit(grant.token, "wear", host)) }
    assertEquals(
      ThemeRenderLeaseManager.Admission.Saturated,
      manager.admission(grant.token, "wear", host),
    )
    permits.forEach { it.close() }

    assertEquals(
      ThemeRenderLeaseManager.Admission.Unknown,
      manager.admission("never-minted", "wear", host),
      "a token this manager never issued",
    )
    val released = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))
    assertTrue(manager.release(released.token))
    assertEquals(
      ThemeRenderLeaseManager.Admission.Unknown,
      manager.admission(released.token, "wear", host),
      "a claim handed back is gone, not merely full",
    )

    val expiring = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))
    now += ThemeRenderLeaseManager.TTL_MILLIS
    assertEquals(
      ThemeRenderLeaseManager.Admission.Unknown,
      manager.admission(expiring.token, "wear", host),
      "so is an expired one",
    )
  }

  @Test
  fun `releasing one page claim does not release its catalog allocation`() {
    val manager = manager()
    val host = Any()
    val first = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))
    val second = assertNotNull(manager.acquire("wear", host, requestedCapacity = 5))

    assertTrue(manager.release(first.token))
    assertNull(manager.admit(first.token, "wear", host))
    assertNotNull(manager.admit(second.token, "wear", host)).close()
    assertEquals(3, assertNotNull(manager.acquire("material", Any(), 5)).concurrency)
    assertNull(manager.acquire("third", Any(), 5))
  }

  @Test
  fun `concurrent grants never promise more width than the server has slots`() {
    // Eight slots is the real box: the tiers fit exactly, 5 + 3.
    val roomy = manager(serverSlots = 8)
    assertEquals(5, assertNotNull(roomy.acquire("a", Any(), requestedCapacity = 5)).concurrency)
    assertEquals(3, assertNotNull(roomy.acquire("b", Any(), requestedCapacity = 5)).concurrency)

    // Four slots: the first grant takes them all, so there is nothing left to promise a second
    // page. Handing it 3 anyway would admit 7 renders against 4 permits and the extra three would
    // queue on the global semaphore until they 503.
    val tight = manager(serverSlots = 4)
    assertEquals(4, assertNotNull(tight.acquire("a", Any(), requestedCapacity = 5)).concurrency)
    assertNull(tight.acquire("b", Any(), requestedCapacity = 5))
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
