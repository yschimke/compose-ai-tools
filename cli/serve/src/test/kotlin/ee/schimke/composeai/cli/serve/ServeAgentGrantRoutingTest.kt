package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The whole grant flow over real HTTP on a **token-gated** server — an agent asks with no
 * credential, the operator approves in the browser, and the agent's bearer then opens the gate that
 * was 404ing a moment earlier.
 *
 * Token-gated rather than GitHub-gated on purpose: it is the configuration where the *approver* is
 * the `--token` holder, so the "who may approve" rule is exercised against something a test can
 * actually present, and the escalation case (an agent trying to approve with its own grant) is
 * reachable end to end.
 */
class ServeAgentGrantRoutingTest {

  private val operatorToken = "operator-secret-token"

  private val registry = ServeSessionRegistry(open = { null })

  private val grants =
    ServeAgentGrantStore(maxScope = AgentGrantScope.PLAYGROUND, maxGrantTtlSeconds = 3600)

  private val server: ServeHttpServer by lazy {
    val dir = Files.createTempDirectory("grants").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    registry.register("demo", host = ServeBundleHost(dir, label = "demo"), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = false,
        agentGrants = grants,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun post(
    path: String,
    body: String,
    contentType: String = "application/json",
    token: String? = null,
    tokenHeader: String = ServeHttpServer.TOKEN_HEADER,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url(url(path))
        .post(body.toRequestBody(contentType.toMediaType()))
        .apply { token?.let { header(tokenHeader, it) } }
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun get(path: String, token: String? = null): Pair<Int, String> {
    val request =
      Request.Builder()
        .url(url(path))
        .apply { token?.let { header(ServeHttpServer.TOKEN_HEADER, it) } }
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun field(html: String, name: String): String =
    Regex("name=\"$name\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
      ?: error("no $name field in the page")

  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun str(text: String, key: String) = json(text)[key]!!.jsonPrimitive.content

  /** Ask, approve as the operator with the page's own seal, and return the minted bearer. */
  private fun grantedToken(scope: String = "live"): String {
    val (_, opened) = post("/agent-access/request", """{"scope":"$scope","label":"fix #1"}""")
    val requestId = str(opened, "requestId")
    val secret = str(opened, "deviceSecret")
    val (_, page) = get("/agent-access/$requestId?token=$operatorToken")
    val approve =
      post(
        "/agent-access/$requestId?token=$operatorToken",
        "action=approve&csrf=${field(page, "csrf")}&scope=$scope&ttl=1800",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, approve.first)
    val (_, polled) =
      post("/agent-access/poll", """{"requestId":"$requestId","deviceSecret":"$secret"}""")
    assertEquals("approved", str(polled, "status"))
    return str(polled, "token")
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `an agent with no credential can open a request`() {
    val (code, body) = post("/agent-access/request", """{"scope":"live","label":"fix #1"}""")
    assertEquals(200, code)
    val payload = json(body)
    assertTrue(payload["requestId"]!!.jsonPrimitive.content.isNotEmpty())
    assertTrue(payload["deviceSecret"]!!.jsonPrimitive.content.isNotEmpty())
    assertTrue(payload["approveUrl"]!!.jsonPrimitive.content.contains("/agent-access/"))
    assertEquals("live", payload["requestedScope"]!!.jsonPrimitive.content)
    // The approve link is a handle, never the credential.
    assertFalse(
      payload["approveUrl"]!!
        .jsonPrimitive
        .content
        .contains(payload["deviceSecret"]!!.jsonPrimitive.content)
    )
  }

  @Test
  fun `an empty body means the defaults, not a bad request`() {
    val (code, body) = post("/agent-access/request", "")
    assertEquals(200, code)
    assertEquals("preview", str(body, "requestedScope"))
  }

  @Test
  fun `the approval page is refused without an operator identity`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val (code, body) = get("/agent-access/${str(opened, "requestId")}")
    assertEquals(401, code)
    // …and it must not leak the operator token while explaining how to present one.
    assertFalse(body.contains(operatorToken))
  }

  @Test
  fun `the approval page shows the code the agent printed`() {
    val (_, opened) = post("/agent-access/request", """{"scope":"live","label":"fix &lt;#1&gt;"}""")
    val (code, page) = get("/agent-access/${str(opened, "requestId")}?token=$operatorToken")
    assertEquals(200, code)
    assertTrue(page.contains(str(opened, "userCode")))
    assertTrue(page.contains("Verification code"))
    // The device secret is never rendered — the page is a thing a human is invited to screenshot.
    assertFalse(page.contains(str(opened, "deviceSecret")))
  }

  @Test
  fun `poll says pending until a human acts`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val (code, body) =
      post(
        "/agent-access/poll",
        """{"requestId":"${str(opened, "requestId")}","deviceSecret":"${str(opened, "deviceSecret")}"}""",
      )
    assertEquals(200, code)
    assertEquals("pending", str(body, "status"))
    assertFalse(body.contains("cpat_"))
  }

  @Test
  fun `a granted token opens a gate that was closed`() {
    // Before: the gate is shut for an anonymous caller.
    assertEquals(404, get("/status.json").first)

    val token = grantedToken()
    val (code, body) = get("/status.json", token = token)
    assertEquals(200, code)
    assertTrue(body.contains("\"version\""))
  }

  @Test
  fun `a granted token also works as a bearer and as a query parameter`() {
    val token = grantedToken()
    val bearer =
      Request.Builder().url(url("/status.json")).header("Authorization", "Bearer $token").build()
    client.newCall(bearer).execute().use { assertEquals(200, it.code) }
    assertEquals(200, get("/status.json?token=$token").first)
  }

  @Test
  fun `whoami describes the grant without echoing it`() {
    val token = grantedToken()
    val (code, body) = get("/agent-access/whoami", token = token)
    assertEquals(200, code)
    assertEquals(true, json(body)["active"]!!.jsonPrimitive.content.toBoolean())
    assertTrue(body.contains("\"live\""))
    assertFalse(body.contains(token))
    assertTrue(str(body, "fingerprint").length == 12)
  }

  @Test
  fun `whoami answers false rather than erroring for a caller with no grant`() {
    val (code, body) = get("/agent-access/whoami", token = "cpat_notarealtokenatall00")
    assertEquals(200, code)
    assertEquals("false", json(body)["active"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an agent can hand its own access back`() {
    val token = grantedToken()
    assertEquals(200, get("/status.json", token = token).first)
    val (code, body) = post("/agent-access/revoke", "{}", token = token)
    assertEquals(200, code)
    assertEquals("true", json(body)["revoked"]!!.jsonPrimitive.content)
    assertEquals(404, get("/status.json", token = token).first)
  }

  @Test
  fun `a stale or forged csrf seal is refused`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val requestId = str(opened, "requestId")
    val (code, _) =
      post(
        "/agent-access/$requestId?token=$operatorToken",
        "action=approve&csrf=forged&scope=live&ttl=1800",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(403, code)
    // Nothing was minted.
    val (_, polled) =
      post(
        "/agent-access/poll",
        """{"requestId":"$requestId","deviceSecret":"${str(opened, "deviceSecret")}"}""",
      )
    assertEquals("pending", str(polled, "status"))
  }

  @Test
  fun `a grant cannot approve another grant`() {
    val token = grantedToken()
    val (_, opened) = post("/agent-access/request", """{"scope":"playground"}""")
    val requestId = str(opened, "requestId")
    // The page itself is refused, so the agent never sees a seal to replay…
    assertEquals(401, get("/agent-access/$requestId", token = token).first)
    // …and posting a decision with the bearer is refused too.
    val (code, _) =
      post(
        "/agent-access/$requestId",
        "action=approve&csrf=anything&scope=playground&ttl=1800",
        contentType = "application/x-www-form-urlencoded",
        token = token,
      )
    assertEquals(401, code)
  }

  @Test
  fun `denying grants nothing and tells the agent so`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val requestId = str(opened, "requestId")
    val (_, page) = get("/agent-access/$requestId?token=$operatorToken")
    val (code, _) =
      post(
        "/agent-access/$requestId?token=$operatorToken",
        "action=deny&denyCsrf=${field(page, "denyCsrf")}",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, code)
    val (_, polled) =
      post(
        "/agent-access/poll",
        """{"requestId":"$requestId","deviceSecret":"${str(opened, "deviceSecret")}"}""",
      )
    assertEquals("denied", str(polled, "status"))
  }

  @Test
  fun `a poll with the wrong secret never collects the token`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val requestId = str(opened, "requestId")
    val (_, page) = get("/agent-access/$requestId?token=$operatorToken")
    post(
      "/agent-access/$requestId?token=$operatorToken",
      "action=approve&csrf=${field(page, "csrf")}&scope=preview&ttl=600",
      contentType = "application/x-www-form-urlencoded",
    )
    val (code, body) =
      post("/agent-access/poll", """{"requestId":"$requestId","deviceSecret":"wrong"}""")
    assertEquals(200, code)
    assertEquals("unknown", str(body, "status"))
    assertFalse(body.contains("cpat_"))
  }

  @Test
  fun `the operator sees pending requests and live grants on status`() {
    val token = grantedToken()
    post("/agent-access/request", """{"label":"another ask"}""")
    val (code, page) = get("/status?token=$operatorToken")
    assertEquals(200, code)
    assertTrue(page.contains("Agent access"))
    assertTrue(page.contains("another ask"))
    assertTrue(page.contains("Revoke"))
    // The page lists grants by fingerprint; no token ever reaches the markup.
    assertFalse(page.contains(token))
    assertNotNull(grants.activeGrants().firstOrNull())
    assertTrue(page.contains(grants.activeGrants().first().fingerprint))
  }

  @Test
  fun `a grant holder reading status gets no revoke buttons`() {
    val token = grantedToken()
    val (code, page) = get("/status", token = token)
    assertEquals(200, code)
    assertTrue(page.contains("Agent access"))
    assertFalse(page.contains("Revoke"))
  }

  @Test
  fun `a page served to a grant holder never carries the operator token`() {
    val token = grantedToken()
    // Every generated link on a token-gated server carries a `?token=`, which is exactly how a
    // twenty-minute `preview` grant could have walked off with the permanent operator credential.
    // Each page a grant can reach must therefore be wired with the grant's own token instead.
    for (path in listOf("/", "/status", "/no-such-page")) {
      val (_, body) = get(path, token = token)
      assertFalse(
        body.contains(operatorToken),
        "$path leaked the operator token to a grant holder",
      )
    }
    // …and the substitution has to be a working credential, not a blank: the links it wired must
    // still resolve for the reader they were wired for.
    val (code, page) = get("/status", token = token)
    assertEquals(200, code)
    assertTrue(page.contains("token=$token"), "the page's links carry the reader's own grant")
    assertEquals(200, get("/status.json?token=$token").first)
  }

  @Test
  fun `the operator still sees their own token in their own pages`() {
    val (code, page) = get("/status?token=$operatorToken")
    assertEquals(200, code)
    assertTrue(page.contains("token=$operatorToken"), "an operator's own page is unchanged")
  }

  @Test
  fun `a preview grant cannot open the ingest lanes it was never shown`() {
    // This server wires no ingest lanes, so the unit under test is the gate itself: a `preview`
    // grant that satisfies every ordinary route must NOT satisfy the one an operator opted into
    // for clients contributing content. The consent page says "browse", and it has to mean it.
    val token = grantedToken(scope = "preview")
    assertEquals(200, get("/status.json", token = token).first)
    assertEquals(404, post("/docs", "{}", token = token).first)
    assertEquals(404, post("/bundles/anything", "{}", token = token).first)
  }

  @Test
  fun `a preview grant is refused the live socket even with no github auth configured`() {
    // The bug this pins: the live gate used to read `githubAuth ?: return false` BEFORE looking at
    // the grant, so on a box with no OAuth — like this one — every grant sailed through it
    // regardless of what a human had actually approved. The socket is the live lane, so it is
    // where the rule is checked.
    val refusal = socketCloseReason(grantedToken(scope = "preview"))
    assertTrue(
      refusal.contains("live not approved"),
      "a preview grant should be refused the live lane, got: $refusal",
    )
    // A `live` grant gets past the scope check; whatever happens next is not about the scope.
    val allowed = socketCloseReason(grantedToken(scope = "live"))
    assertFalse(allowed.contains("live not approved"), "a live grant was refused: $allowed")
  }

  /** Open the viewer socket with [token] and return the reason it was closed with (or "open"). */
  private fun socketCloseReason(token: String): String {
    val latch = java.util.concurrent.CountDownLatch(1)
    val closeReason = java.util.concurrent.atomic.AtomicReference("open")
    val request =
      Request.Builder()
        .url("ws://127.0.0.1:${server.port}/ws/AnyPreview?session=demo")
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .build()
    val socket =
      client.newWebSocket(
        request,
        object : okhttp3.WebSocketListener() {
          override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            closeReason.set(reason)
            latch.countDown()
          }

          override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            closeReason.set(reason)
            latch.countDown()
          }

          override fun onFailure(
            webSocket: okhttp3.WebSocket,
            t: Throwable,
            response: okhttp3.Response?,
          ) {
            closeReason.set("failure: ${t.message}")
            latch.countDown()
          }
        },
      )
    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
    socket.cancel()
    return closeReason.get()
  }

  @Test
  fun `a preview grant may replay a render but not commission one`() {
    val preview = grantedToken(scope = "preview")
    // A bare replay is what `preview` means, and stays available.
    assertTrue(get("/render/Anything.png", token = preview).first != 403)
    // An override turns the same route into a live render — that is `live`, and was not approved.
    assertEquals(403, get("/render/Anything.png?uiMode=dark", token = preview).first)
    // …as does asking for a product only a daemon can produce.
    assertEquals(403, get("/render/Anything.svg", token = preview).first)
    // The whole-catalog zip renders every preview, so it wants `live` too.
    assertEquals(403, get("/bundle.zip", token = preview).first)
  }

  @Test
  fun `a preview grant is refused every route that puts the daemon to work`() {
    val preview = grantedToken(scope = "preview")
    // Presence pings keep a daemon warm; the storybook frame has no baked lane at all; the theme
    // lease is permission to run a burst of live renders.
    assertEquals(403, post("/api/presence", "{}", token = preview).first)
    assertEquals(403, get("/iframe.html?id=Anything", token = preview).first)
    assertEquals(403, post("/api/theme-render-lease", "{}", token = preview).first)
  }

  @Test
  fun `a preview grant keeps every route that only replays what exists`() {
    // The other half of the rule, and the one that keeps `preview` worth granting: reading the
    // catalog, its pages and its already-rendered bytes must not require `live`.
    val preview = grantedToken(scope = "preview")
    for (path in listOf("/", "/api/previews", "/status", "/p/Anything", "/index.json")) {
      assertTrue(
        get(path, token = preview).first != 403,
        "$path should be readable with a preview grant",
      )
    }
  }

  @Test
  fun `a grant in the query is not shadowed by an unrelated bearer`() {
    // `share-preview --mechanism serve` sends the host credential in `?token=` and a GitHub token
    // in `Authorization: Bearer`. Reading the sources by precedence made the GitHub token hide the
    // grant, so the browse gate 404'd a caller that was properly authorised.
    val token = grantedToken()
    val request =
      Request.Builder()
        .url(url("/status.json?token=$token"))
        .header("Authorization", "Bearer gho_an_unrelated_github_token")
        .build()
    client.newCall(request).execute().use { assertEquals(200, it.code) }
  }

  @Test
  fun `denying a request someone else already approved says so`() {
    val (_, opened) = post("/agent-access/request", "{}")
    val requestId = str(opened, "requestId")
    val (_, page) = get("/agent-access/$requestId?token=$operatorToken")
    // A second operator holds the same page; the first approves.
    post(
      "/agent-access/$requestId?token=$operatorToken",
      "action=approve&csrf=${field(page, "csrf")}&scope=preview&ttl=600",
      contentType = "application/x-www-form-urlencoded",
    )
    // The second clicks Deny. Telling them "nothing was granted" would be a false assurance.
    val (code, body) =
      post(
        "/agent-access/$requestId?token=$operatorToken",
        "action=deny&denyCsrf=${field(page, "denyCsrf")}",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(409, code)
    assertTrue(body.contains("Already decided"))
    assertFalse(body.contains("Nothing was granted"))
  }

  @Test
  fun `a live grant may commission a render`() {
    val live = grantedToken(scope = "live")
    assertTrue(get("/render/Anything.png?uiMode=dark", token = live).first != 403)
    assertTrue(get("/bundle.zip", token = live).first != 403)
  }

  @Test
  fun `an approver on a private server must hold the browse token, not merely a session`() {
    // No GitHub auth here, so the operator branch is what runs — and it compares against `--token`
    // specifically. A caller with a grant, or with nothing, is not an approver.
    val (_, opened) = post("/agent-access/request", "{}")
    val requestId = str(opened, "requestId")
    assertEquals(401, get("/agent-access/$requestId").first)
    assertEquals(401, get("/agent-access/$requestId?token=wrong-token").first)
    assertEquals(200, get("/agent-access/$requestId?token=$operatorToken").first)
  }

  @Test
  fun `status json reports the lane by fingerprint`() {
    val token = grantedToken()
    val (code, body) = get("/status.json?token=$operatorToken")
    assertEquals(200, code)
    val access = json(body)["agentAccess"]!!.jsonObject
    assertEquals("1", access["activeGrants"]!!.jsonPrimitive.content)
    assertEquals("playground", access["maxScope"]!!.jsonPrimitive.content)
    assertFalse(body.contains(token))
  }
}
