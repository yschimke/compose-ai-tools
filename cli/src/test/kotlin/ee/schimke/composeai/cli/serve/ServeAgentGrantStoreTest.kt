package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grant state machine: what a request becomes, who may collect it, and what a grant may do.
 *
 * The properties worth pinning are the ones the design leans on — the token is delivered to the
 * device secret and not to the link, an approval can narrow but never widen, and everything here
 * expires. Each of those is a security claim in
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md),
 * so each gets a test rather than a comment.
 */
class ServeAgentGrantStoreTest {

  private var now = 1_000_000L

  private fun store(
    maxScope: ServeAgentGrantScope = ServeAgentGrantScope.PLAYGROUND,
    maxGrantTtlSeconds: Long = 3600,
    maxActiveGrants: Int = 16,
    maxPendingRequests: Int = 32,
  ) =
    ServeAgentGrantStore(
      maxGrantTtlSeconds = maxGrantTtlSeconds,
      maxScope = maxScope,
      maxActiveGrants = maxActiveGrants,
      maxPendingRequests = maxPendingRequests,
      clock = { now },
    )

  private fun ServeAgentGrantStore.ask(
    scope: ServeAgentGrantScope = ServeAgentGrantScope.LIVE,
    ttl: Long = 1800,
  ) = openRequest("fix #1", "10.0.0.1", scope, ttl)!!

  @Test
  fun `the token goes to the device secret, not to the link`() {
    val store = store()
    val request = store.ask()
    store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)

    // Someone holding the link but not the secret gets the same answer as someone holding neither.
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, "not-the-secret"))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, null))

    val collected = store.poll(request.id, request.deviceSecret)
    assertTrue(collected is ServeAgentGrantStore.Poll.Approved)
    assertTrue(collected.grant.token.startsWith(ServeAgentGrantStore.TOKEN_PREFIX))
  }

  @Test
  fun `an unknown id is indistinguishable from a wrong secret`() {
    val store = store()
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll("Zm9vYmFyYmF6cXV1eA", "whatever"))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll("!!not-well-formed!!", "whatever"))
  }

  @Test
  fun `approval may narrow a request but never widen it`() {
    val store = store(maxScope = ServeAgentGrantScope.PLAYGROUND)
    val request = store.ask(scope = ServeAgentGrantScope.LIVE, ttl = 900)

    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.PLAYGROUND, 99_999)!!
    assertEquals(ServeAgentGrantScope.LIVE, grant.scope)
    assertEquals(900, (grant.expiresAtMillis - grant.issuedAtMillis) / 1000)
  }

  @Test
  fun `the operator ceiling clamps a request at the door`() {
    val store = store(maxScope = ServeAgentGrantScope.PREVIEW)
    val request = store.ask(scope = ServeAgentGrantScope.PLAYGROUND)
    assertEquals(ServeAgentGrantScope.PREVIEW, request.requestedScope)

    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.PLAYGROUND, 600)!!
    assertEquals(ServeAgentGrantScope.PREVIEW, grant.scope)
    assertFalse(grant.allows(ServeAgentGrantScope.LIVE))
  }

  @Test
  fun `scopes are cumulative`() {
    val store = store()
    val request = store.ask(scope = ServeAgentGrantScope.PLAYGROUND)
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.PLAYGROUND, 600)!!
    assertTrue(grant.allows(ServeAgentGrantScope.PREVIEW))
    assertTrue(grant.allows(ServeAgentGrantScope.LIVE))
    assertTrue(grant.allows(ServeAgentGrantScope.PLAYGROUND))
    assertEquals(listOf("preview", "live", "playground"), grant.scopes.map { it.wire })
  }

  @Test
  fun `approving twice mints one grant, not two`() {
    val store = store()
    val request = store.ask()
    val first = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)!!
    val second = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)!!
    assertEquals(first.id, second.id)
    assertEquals(1, store.activeGrants().size)
  }

  @Test
  fun `a denied request cannot then be approved`() {
    val store = store()
    val request = store.ask()
    assertTrue(store.deny(request.id, "@yuri"))
    assertNull(store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600))
    val polled = store.poll(request.id, request.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Denied)
  }

  @Test
  fun `an approved request cannot then be denied`() {
    val store = store()
    val request = store.ask()
    store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)
    assertFalse(store.deny(request.id, "@someone-else"))
  }

  @Test
  fun `a request expires, and its poll says so`() {
    val store = store()
    val request = store.ask()
    now += (store.requestTtlSeconds + 1) * 1000
    assertNull(store.request(request.id))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a grant expires and stops authorising`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 60)!!
    assertNotNull(store.grantForToken(grant.token))
    now += 61_000
    assertNull(store.grantForToken(grant.token))
    assertTrue(store.activeGrants().isEmpty())
  }

  @Test
  fun `a revoked grant stops authorising immediately`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 3600)!!
    assertTrue(store.revoke(grant.id, "@yuri"))
    assertNull(store.grantForToken(grant.token))
    assertFalse(store.revoke(grant.id, "@yuri"))
  }

  @Test
  fun `revoking by token is the agent handing its own access back`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 3600)!!
    assertTrue(store.revokeToken(grant.token, "the agent itself"))
    assertNull(store.grantForToken(grant.token))
  }

  @Test
  fun `polling after a revoke reports expired rather than handing back a dead token`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 3600)!!
    store.revoke(grant.id, "@yuri")
    assertEquals(ServeAgentGrantStore.Poll.Expired, store.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `the active-grant cap evicts the nearest to expiry, never the new one`() {
    val store = store(maxActiveGrants = 2)
    fun mint(ttl: Long): ServeAgentGrantStore.Grant {
      val request = store.ask(ttl = ttl)
      return store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, ttl)!!
    }
    val shortest = mint(60)
    val middle = mint(600)
    val newest = mint(3600)
    assertNull(store.grantForToken(shortest.token))
    assertNotNull(store.grantForToken(middle.token))
    assertNotNull(store.grantForToken(newest.token))
  }

  @Test
  fun `a short-lived grant does not evict itself on a full map`() {
    // The approver's choice of a *shorter* lifetime than everything already live used to make the
    // new grant the nearest to expiry, so the cap evicted the grant it had just minted: the page
    // said approved and the agent's next poll found nothing.
    val store = store(maxActiveGrants = 2)
    fun mint(ttl: Long): ServeAgentGrantStore.Grant {
      val request = store.ask(ttl = ttl)
      return store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, ttl)!!
    }
    mint(3600)
    mint(1800)
    val shortest = mint(900)
    assertNotNull(store.grantForToken(shortest.token), "the new grant evicted itself")
    assertEquals(2, store.activeGrants().size)
  }

  @Test
  fun `the pending-request cap refuses rather than growing without bound`() {
    val store = store(maxPendingRequests = 2)
    assertNotNull(store.openRequest("a", "ip", ServeAgentGrantScope.PREVIEW, 600))
    assertNotNull(store.openRequest("b", "ip", ServeAgentGrantScope.PREVIEW, 600))
    assertNull(store.openRequest("c", "ip", ServeAgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `the pending cap holds under concurrent anonymous callers`() {
    // The check and the insert used to be a check-then-act on a map an anonymous caller drives, so
    // a burst could push it well past the cap — the bound this ungated route relies on for memory.
    val store = store(maxPendingRequests = 8)
    val threads =
      (1..32).map { i ->
        Thread { store.openRequest("burst-$i", "ip-$i", ServeAgentGrantScope.PREVIEW, 600) }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertTrue(
      store.pendingRequests().size <= 8,
      "the cap was exceeded: ${store.pendingRequests().size}",
    )
  }

  @Test
  fun `a resolved request makes room for a new one`() {
    val store = store(maxPendingRequests = 2)
    val first = store.openRequest("a", "ip", ServeAgentGrantScope.PREVIEW, 600)!!
    store.openRequest("b", "ip", ServeAgentGrantScope.PREVIEW, 600)
    store.deny(first.id, "@yuri")
    assertNotNull(store.openRequest("c", "ip", ServeAgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `a token is never mistaken for the operator token, and vice versa`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)!!
    assertTrue(ServeAgentGrantStore.isWellFormedToken(grant.token))
    // An ordinary `--token` (no prefix) never reaches the map at all.
    assertFalse(ServeAgentGrantStore.isWellFormedToken("plain-operator-token-value"))
    assertNull(store.grantForToken("plain-operator-token-value"))
  }

  @Test
  fun `the fingerprint identifies a grant without disclosing it`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)!!
    assertEquals(12, grant.fingerprint.length)
    assertFalse(grant.token.contains(grant.fingerprint))
    assertEquals(grant.fingerprint, ServeAgentGrantStore.fingerprintOf(grant.token))
  }

  @Test
  fun `the audit trail names the approver and the fingerprint, never the token`() {
    val lines = mutableListOf<String>()
    val store =
      ServeAgentGrantStore(
        clock = { now },
        maxScope = ServeAgentGrantScope.PLAYGROUND,
        audit = { lines += it },
      )
    val request = store.openRequest("fix #1", "10.0.0.1", ServeAgentGrantScope.LIVE, 600)!!
    val grant = store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)!!
    store.revoke(grant.id, "@yuri")
    assertEquals(2, lines.size)
    assertTrue(lines.all { it.contains(grant.fingerprint) })
    assertTrue(lines.all { !it.contains(grant.token) })
    assertTrue(lines[0].contains("@yuri"))
  }

  @Test
  fun `a user code avoids the characters a human confuses`() {
    repeat(200) {
      val code = ServeAgentGrantStore.randomUserCode()
      assertEquals(9, code.length)
      assertEquals('-', code[4])
      assertTrue(code.none { it in "01ILOSZB25" }, "confusable character in $code")
    }
  }

  @Test
  fun `overflow never strands a token the agent has not collected yet`() {
    val store = store(maxPendingRequests = 2)
    val approved = store.ask()
    store.approve(approved.id, "@yuri", ServeAgentGrantScope.LIVE, 600)
    store.openRequest("b", "ip", ServeAgentGrantScope.PREVIEW, 600)
    // The map is full and the only resolved entry is an approval nobody has polled for yet, so the
    // new ask is refused rather than deleting a live credential its owner can never fetch again.
    assertNull(store.openRequest("c", "ip", ServeAgentGrantScope.PREVIEW, 600))
    val polled = store.poll(approved.id, approved.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Approved)
  }

  @Test
  fun `an approval near the deadline is still collectable after it`() {
    // The request TTL bounds how long a human has to DECIDE. Once they have decided, deleting the
    // record strands the grant it created — which is what happened to anyone approving in the last
    // seconds of the window, because the agent's next poll landed after it.
    val store = store()
    val request = store.ask(ttl = 3600)
    store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 3600)
    now += (store.requestTtlSeconds + 5) * 1000
    val polled = store.poll(request.id, request.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Approved, "got $polled")
  }

  @Test
  fun `approving concurrently with a purge never mints an orphaned grant`() {
    // The lookup used to sit outside the lock that purging takes, so a purge landing between the
    // two could delete the entry while approve minted a grant and marked a detached object
    // approved: the page said success, the agent's poll found nothing.
    repeat(40) {
      val store = store()
      val request = store.ask(ttl = 600)
      val results = java.util.concurrent.ConcurrentLinkedQueue<ServeAgentGrantStore.Grant?>()
      val approver = Thread {
        results += store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)
      }
      val purger = Thread { repeat(20) { store.purge() } }
      approver.start()
      purger.start()
      approver.join()
      purger.join()
      val grant = results.poll()
      if (grant != null) {
        // If it says it minted one, the agent must be able to collect it.
        val polled = store.poll(request.id, request.deviceSecret)
        assertTrue(polled is ServeAgentGrantStore.Poll.Approved, "orphaned grant: got $polled")
      }
    }
  }

  @Test
  fun `concurrent polling never sees a half-published approval`() {
    // HONEST SCOPE: this is a smoke test, not the guarantee. The guarantee is that `poll` reads
    // under the same lock `approve` holds, so the intermediate state (APPROVED with a null grant
    // id) cannot be observed at all. A racing test like this one passed against the *broken*
    // ordering too — the window is two adjacent statements, and hitting it by sampling is luck.
    // Kept because it would catch a gross regression (a lock removed, an exception under
    // contention) cheaply, and because the reader should be told which of the two it is.
    repeat(50) {
      val store = store()
      val request = store.ask(ttl = 600)
      val seen = java.util.concurrent.ConcurrentLinkedQueue<ServeAgentGrantStore.Poll>()
      val poller = Thread { repeat(200) { seen += store.poll(request.id, request.deviceSecret) } }
      poller.start()
      store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600)
      poller.join()
      assertTrue(
        seen.none { it is ServeAgentGrantStore.Poll.Expired },
        "a poll saw `expired` for a grant that was being minted",
      )
    }
  }

  @Test
  fun `a lost token response can be collected again`() {
    // `collected` marks that a poll BUILT a response, not that the agent received one. Treating it
    // as a deletion trigger meant a response lost in flight left the retry told `unknown` while the
    // grant was still live.
    val store = store()
    val request = store.ask(ttl = 3600)
    store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 3600)
    assertTrue(store.poll(request.id, request.deviceSecret) is ServeAgentGrantStore.Poll.Approved)
    now += (store.requestTtlSeconds + 5) * 1000 // past the request window, grant still alive
    assertTrue(
      store.poll(request.id, request.deviceSecret) is ServeAgentGrantStore.Poll.Approved,
      "the retry after a lost response must still collect the token",
    )
  }

  @Test
  fun `a pending request still dies on its own deadline`() {
    // The other half: nobody may approve a request whose window has closed.
    val store = store()
    val request = store.ask()
    now += (store.requestTtlSeconds + 5) * 1000
    assertNull(store.request(request.id))
    assertNull(store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 600))
  }

  @Test
  fun `a retained approval is reclaimed once its grant is gone`() {
    val store = store()
    val request = store.ask(ttl = 60)
    store.approve(request.id, "@yuri", ServeAgentGrantScope.LIVE, 60)
    now += (store.requestTtlSeconds + 5) * 1000
    // The grant expired too, so the record owes nobody anything and goes.
    assertNull(store.request(request.id))
  }

  @Test
  fun `a collected request is shed to make room`() {
    val store = store(maxPendingRequests = 2)
    val collected = store.ask()
    store.approve(collected.id, "@yuri", ServeAgentGrantScope.LIVE, 600)
    assertTrue(
      store.poll(collected.id, collected.deviceSecret) is ServeAgentGrantStore.Poll.Approved
    )
    store.openRequest("b", "ip", ServeAgentGrantScope.PREVIEW, 600)
    assertNotNull(store.openRequest("c", "ip", ServeAgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `a label cannot forge a log line or drive the operator's terminal`() {
    val lines = mutableListOf<String>()
    val store = ServeAgentGrantStore(clock = { now }, audit = { lines += it })
    val hostile = "ok\nagent-grant: minted deadbeef scope=playground\u001b[2J"
    val request = store.openRequest(hostile, "10.0.0.1", ServeAgentGrantScope.PREVIEW, 600)!!
    assertFalse(request.label.contains('\n'))
    assertFalse(request.label.any { it.isISOControl() })
    store.approve(request.id, "@yuri", ServeAgentGrantScope.PREVIEW, 600)
    assertEquals(1, lines.size, "one label must not become two log lines")
    assertFalse(lines.single().contains('\n'))
  }

  @Test
  fun `a label from an agent is capped rather than trusted to be short`() {
    val store = store()
    val request = store.openRequest("x".repeat(5000), "ip", ServeAgentGrantScope.PREVIEW, 600)!!
    assertEquals(ServeAgentGrantStore.MAX_LABEL_CHARS, request.label.length)
  }
}
