package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeAgentGrantScope
import ee.schimke.composeai.cli.serve.ServeAgentGrantStore
import ee.schimke.composeai.cli.serve.ServeBundleHost
import ee.schimke.composeai.cli.serve.ServeHttpServer
import ee.schimke.composeai.cli.serve.ServeSessionRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLI's grant client against a **real** `ServeHttpServer`, so the two halves of the wire
 * protocol are checked against each other rather than each against its own idea of the other.
 *
 * They are deliberately separate declarations — the CLI ships and versions independently of any
 * host it talks to, so its request/response types are its own with every field defaulted. That
 * independence is exactly what lets them drift silently, which is what this test exists to stop.
 */
class AgentAccessClientIntegrationTest {

  private val registry = ServeSessionRegistry(open = { null })

  private val grants =
    ServeAgentGrantStore(maxScope = ServeAgentGrantScope.PLAYGROUND, maxGrantTtlSeconds = 3600)

  private val server: ServeHttpServer by lazy {
    val dir = Files.createTempDirectory("client-grants").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    registry.register("demo", host = ServeBundleHost(dir, label = "demo"), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-secret",
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = false,
        agentGrants = grants,
      )
      .also { it.start() }
  }

  private fun client() = AgentAccessClient("http://127.0.0.1:${server.port}")

  /**
   * A store that reads and remembers normally but cannot save a *grant*. Stands in for a full disk
   * or a read-only config dir at the moment the token comes back — the case where dropping the
   * pending record would strand a live credential.
   */
  private class UnsaveableStore(file: File) : AgentAccessStore(file = file, warn = {}) {
    override fun save(entry: Entry): Boolean = false
  }

  private fun unsaveableStore(): AgentAccessStore =
    UnsaveableStore(
      File(
        Files.createTempDirectory("auth-store-ro").toFile().also { it.deleteOnExit() },
        "agent-access.json",
      )
    )

  /** A credential store on a throwaway path, so a test never touches the caller's real one. */
  private fun tempStore(): AgentAccessStore =
    AgentAccessStore(
      file =
        File(
          Files.createTempDirectory("auth-store").toFile().also { it.deleteOnExit() },
          "agent-access.json",
        ),
      warn = {},
    )

  private fun <T> ok(result: AgentAccessClient.Result<T>): T =
    when (result) {
      is AgentAccessClient.Result.Ok -> result.value
      is AgentAccessClient.Result.Err -> error("expected success, got: ${result.reason}")
    }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `the client's request and the server's response agree`() {
    val opened = ok(client().open(label = "fix #1", scope = "live", ttlSeconds = 1800))
    assertTrue(opened.requestId.isNotEmpty())
    assertTrue(opened.deviceSecret.isNotEmpty())
    assertEquals(9, opened.userCode.length)
    assertEquals("live", opened.requestedScope)
    assertEquals(1800, opened.requestedTtlSeconds)
    assertEquals("playground", opened.maxScope)
    assertTrue(opened.approveUrl.startsWith("http://127.0.0.1:${server.port}/agent-access/"))
    assertTrue(opened.pollUrl.endsWith("/agent-access/poll"))
    assertTrue(opened.pollIntervalSeconds > 0)
    // The label the human will read is the one the client sent.
    assertEquals("fix #1", grants.request(opened.requestId)?.label)
  }

  @Test
  fun `poll transitions pending to approved and yields a usable token`() {
    val c = client()
    val opened = ok(c.open(label = "fix #2", scope = "live", ttlSeconds = 900))
    assertEquals("pending", ok(c.poll(opened.requestId, opened.deviceSecret)).status)

    // The human's half, driven directly — the browser flow itself is covered by the routing test.
    grants.approve(opened.requestId, "@yuri", ServeAgentGrantScope.LIVE, 900)

    val approved = ok(c.poll(opened.requestId, opened.deviceSecret))
    assertEquals("approved", approved.status)
    assertEquals("@yuri", approved.approvedBy)
    assertEquals(listOf("preview", "live"), approved.scopes)
    assertEquals(ServeHttpServer.TOKEN_HEADER, approved.tokenHeader)
    val token = approved.token!!

    val who = ok(c.whoami(token))
    assertTrue(who.active)
    assertEquals("fix #2", who.label)
    assertEquals(12, who.fingerprint!!.length)

    assertTrue(ok(c.revoke(token)).revoked)
    assertFalse(ok(c.whoami(token)).active)
  }

  @Test
  fun `a denied request reads as denied on the client`() {
    val c = client()
    val opened = ok(c.open(label = "", scope = "", ttlSeconds = 600))
    grants.deny(opened.requestId, "@yuri")
    assertEquals("denied", ok(c.poll(opened.requestId, opened.deviceSecret)).status)
  }

  @Test
  fun `a wrong device secret reads as unknown and carries no token`() {
    val c = client()
    val opened = ok(c.open(label = "", scope = "preview", ttlSeconds = 600))
    grants.approve(opened.requestId, "@yuri", ServeAgentGrantScope.PREVIEW, 600)
    val polled = ok(c.poll(opened.requestId, "not-the-secret"))
    assertEquals("unknown", polled.status)
    assertEquals(null, polled.token)
  }

  @Test
  fun `auth status collects a request left behind by --no-wait`() {
    // The whole promise of `--no-wait`: print the link, exit, and let a later command finish the
    // job. It only holds if the request was persisted and something actually polls it.
    val c = client()
    val opened = ok(c.open(label = "left behind", scope = "live", ttlSeconds = 900))
    val store = tempStore()
    store.savePending(
      AgentAccessStore.Pending(
        origin = c.origin,
        requestId = opened.requestId,
        deviceSecret = opened.deviceSecret,
        userCode = opened.userCode,
        approveUrl = opened.approveUrl,
        label = "left behind",
        expiresAtMillis = System.currentTimeMillis() + opened.expiresInSeconds * 1000,
      )
    )
    // Nothing to show yet — and, importantly, nothing lost.
    AuthCommand(listOf("status", "--server", c.origin), store).run()
    assertNull(store.tokenFor(c.origin))
    assertNotNull(store.pendingFor(c.origin))

    grants.approve(opened.requestId, "@yuri", ServeAgentGrantScope.LIVE, 900)

    AuthCommand(listOf("status", "--server", c.origin), store).run()
    assertNotNull(store.tokenFor(c.origin), "the approved token should have been collected")
    assertNull(store.pendingFor(c.origin), "the collected request should not still be pending")
    assertEquals("left behind", store.entryFor(c.origin)?.label)
  }

  @Test
  fun `auth status drops a grant the server no longer honours`() {
    val c = client()
    val opened = ok(c.open(label = "revoked soon", scope = "live", ttlSeconds = 900))
    grants.approve(opened.requestId, "@yuri", ServeAgentGrantScope.LIVE, 900)
    val token = ok(c.poll(opened.requestId, opened.deviceSecret)).token!!
    val store = tempStore()
    store.save(
      AgentAccessStore.Entry(
        origin = c.origin,
        token = token,
        scopes = listOf("preview", "live"),
        expiresAtMillis = System.currentTimeMillis() + 900_000,
      )
    )
    // Still live: status leaves it alone.
    AuthCommand(listOf("status", "--server", c.origin), store).run()
    assertNotNull(store.tokenFor(c.origin))

    // The operator revokes. Local expiry has not moved, so only asking the server can tell.
    grants.revokeToken(token, "@yuri")
    AuthCommand(listOf("status", "--server", c.origin), store).run()
    assertNull(store.tokenFor(c.origin), "a revoked grant should not still be reported as held")
  }

  @Test
  fun `a failed save leaves the device secret in place to try again`() {
    // Dropping the pending record before the grant is safely stored loses the only thing that can
    // re-poll for the token — and the human-readable path never prints the token itself, so the
    // grant would be live on the server with nothing left able to redeem it.
    val c = client()
    val opened = ok(c.open(label = "unwritable", scope = "live", ttlSeconds = 900))
    val wedged = unsaveableStore()
    wedged.savePending(
      AgentAccessStore.Pending(
        origin = c.origin,
        requestId = opened.requestId,
        deviceSecret = opened.deviceSecret,
        expiresAtMillis = System.currentTimeMillis() + 600_000,
      )
    )
    assertNotNull(wedged.pendingFor(c.origin))
    grants.approve(opened.requestId, "@yuri", ServeAgentGrantScope.LIVE, 900)

    // The store cannot write, so collection fails — and must not take the secret down with it.
    AuthCommand(listOf("status", "--server", c.origin), wedged).run()
    assertNull(wedged.tokenFor(c.origin))
    assertNotNull(
      wedged.pendingFor(c.origin),
      "a failed save must leave the device secret in place",
    )
    // Proof the secret is still good: the server will hand the token over again.
    assertEquals("approved", ok(c.poll(opened.requestId, opened.deviceSecret)).status)
  }

  @Test
  fun `plaintext to a non-loopback host is refused before anything is sent`() {
    val e = assertFailsWith<IllegalArgumentException> { AgentAccessClient("http://preview.coo.ee") }
    assertTrue(e.message!!.contains("plaintext"))
  }

  @Test
  fun `a URL carrying credentials is refused`() {
    assertFailsWith<IllegalArgumentException> { AgentAccessClient("https://u:p@preview.coo.ee") }
  }
}
