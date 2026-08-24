package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The link between the two lanes: an agent grant the operator ticked `images` on may upload through
 * `POST /images`, holding **no GitHub credential at all**.
 *
 * The GitHub gate on this server refuses every token it is shown ([RefusingAuth]), which is what
 * makes each success here evidence: nothing in this file can pass by accident through the path that
 * already worked, so an accepted upload was admitted by the grant and by nothing else.
 */
class ServeAgentGrantImageUploadTest {

  /** A gate that admits nobody, so only the grant lane can ever produce a `201` here. */
  private class RefusingAuth(override val repository: String = "yschimke/compose-ai-tools") :
    ServeImageUploadAuth {
    override fun identify(bearerToken: String?): ServeImageUploadAuth.Identity =
      if (bearerToken.isNullOrBlank()) ServeImageUploadAuth.Identity.Missing
      else ServeImageUploadAuth.Identity.Refused(403, "no.")
  }

  private val operatorToken = "operator-secret-token"

  private val registry = ServeSessionRegistry(open = { null })

  private val grants =
    ServeAgentGrantStore(
      maxScope = ServeAgentGrantScope.LIVE,
      maxCapabilities = setOf(ServeAgentGrantCapability.IMAGES),
      maxGrantTtlSeconds = 3600,
    )

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "none",
        isPublic = false,
        agentGrants = grants,
        imageStore = ServeImageStore(ttlSeconds = 600),
        imageUploadAuth = RefusingAuth(),
      )
      .also { it.start() }
  }

  /** A second box whose operator never opted into the capability at all. */
  private val closedGrants =
    ServeAgentGrantStore(maxScope = ServeAgentGrantScope.LIVE, maxGrantTtlSeconds = 3600)

  private val closedServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = false,
        agentGrants = closedGrants,
        imageStore = ServeImageStore(ttlSeconds = 600),
        imageUploadAuth = RefusingAuth(),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun url(path: String, port: Int = server.port) = "http://127.0.0.1:$port$path"

  private fun post(
    path: String,
    body: String,
    contentType: String = "application/json",
    port: Int = server.port,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url(url(path, port))
        .post(body.toRequestBody(contentType.toMediaType()))
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun get(path: String, port: Int = server.port): Pair<Int, String> {
    client.newCall(Request.Builder().url(url(path, port)).build()).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun field(html: String, name: String): String =
    Regex("name=\"$name\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
      ?: error("no $name field in the page")

  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun str(text: String, key: String) = json(text)[key]!!.jsonPrimitive.content

  /**
   * Drive the whole device flow and return the bearer. [ask] is what the agent requests, [tick] is
   * what the human actually ticks — separately, because "asked for" and "granted" are the two
   * things this feature must never conflate.
   */
  private fun grantedToken(
    scope: String = "preview",
    ask: List<String> = listOf("images"),
    tick: List<String> = ask,
    port: Int = server.port,
  ): String {
    val asked = ask.joinToString(",") { "\"$it\"" }
    val (_, opened) =
      post(
        "/agent-access/request",
        """{"scope":"$scope","label":"embed a render","capabilities":[$asked]}""",
        port = port,
      )
    val requestId = str(opened, "requestId")
    val secret = str(opened, "deviceSecret")
    val (_, page) = get("/agent-access/$requestId?token=$operatorToken", port = port)
    val ticked = tick.joinToString("") { "&capability=$it" }
    val (approveCode, _) =
      post(
        "/agent-access/$requestId?token=$operatorToken",
        "action=approve&csrf=${field(page, "csrf")}&scope=$scope&ttl=1800$ticked",
        contentType = "application/x-www-form-urlencoded",
        port = port,
      )
    assertEquals(200, approveCode)
    val (_, polled) =
      post(
        "/agent-access/poll",
        """{"requestId":"$requestId","deviceSecret":"$secret"}""",
        port = port,
      )
    assertEquals("approved", str(polled, "status"))
    return str(polled, "token")
  }

  private fun upload(token: String?, port: Int = server.port) =
    client
      .newCall(
        Request.Builder()
          .url(url("/images?name=after.png", port))
          .apply { token?.let { header(ServeHttpServer.TOKEN_HEADER, it) } }
          .post(ServeImageFixtures.png().toRequestBody("application/octet-stream".toMediaType()))
          .build()
      )
      .execute()

  @AfterTest
  fun tearDown() {
    server.stop()
    closedServer.stop()
    registry.close()
  }

  @Test
  fun `a ticked images grant uploads with no github credential at all`() {
    val token = grantedToken()
    upload(token).use { response ->
      assertEquals(201, response.code)
      val body = response.body.string()
      // The finished markdown is the thing the agent came for.
      assertTrue(str(body, "markdown").startsWith("![after.png](http"), body)
      // Attribution names the grant and the human behind it — never a borrowed login.
      val uploadedBy = str(body, "uploadedBy")
      assertTrue(uploadedBy.startsWith("agent grant "), uploadedBy)
      assertTrue(uploadedBy.contains("operator (token)"), uploadedBy)
    }
  }

  @Test
  fun `the uploaded image is then fetchable at the url it handed back`() {
    val token = grantedToken()
    val path = upload(token).use { str(it.body.string(), "path") }
    // Ungated, exactly like a collaborator's upload: GitHub's proxy fetches a PR body's images
    // anonymously, so a gated URL would never paint.
    client.newCall(Request.Builder().url(url(path)).build()).execute().use {
      assertEquals(200, it.code)
      assertEquals("image/png", it.header("Content-Type"))
    }
  }

  @Test
  fun `a grant without the capability is refused, however wide its scope`() {
    // `live` outranks `preview` on the scope ladder and still confers nothing here: a capability is
    // not a rung, and no amount of scope may add up to one.
    val token = grantedToken(scope = "live", ask = emptyList())
    upload(token).use { response ->
      assertEquals(401, response.code)
      assertTrue(response.body.string().contains("GitHub token"))
    }
  }

  @Test
  fun `asking is not granting — an unticked capability confers nothing`() {
    val token = grantedToken(ask = listOf("images"), tick = emptyList())
    upload(token).use { assertEquals(401, it.code) }
  }

  @Test
  fun `a box that never offered the capability cannot have it posted into a grant`() {
    // The form is client state: this posts `capability=images` at a server whose ceiling is empty.
    val token = grantedToken(port = closedServer.port)
    upload(token, port = closedServer.port).use { assertEquals(401, it.code) }
  }

  @Test
  fun `the poll and whoami both report what was actually granted`() {
    val token = grantedToken()
    val request =
      Request.Builder()
        .url(url("/agent-access/whoami"))
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .build()
    client.newCall(request).execute().use {
      val body = it.body.string()
      assertTrue(body.contains("\"capabilities\":[\"images\"]"), body)
      // Never the token, on any surface that describes it.
      assertFalse(body.contains(token), body)
    }
  }

  @Test
  fun `the approval page offers the capability as its own checkbox`() {
    val (_, opened) =
      post(
        "/agent-access/request",
        """{"scope":"preview","label":"embed a render","capabilities":["images"]}""",
      )
    val (code, page) = get("/agent-access/${str(opened, "requestId")}?token=$operatorToken")
    assertEquals(200, code)
    assertTrue(page.contains("""<input type="checkbox" name="capability" value="images">"""), page)
    // Unticked by default: an extra permission is an act, not something to click past.
    assertFalse(page.contains("""value="images" checked"""), page)
  }

  @Test
  fun `an images grant still cannot spend the box's cpu`() {
    // The two halves stay independent in both directions: `images` says nothing about `live`.
    val token = grantedToken(scope = "preview", ask = listOf("images"))
    val request =
      Request.Builder().url(url("/bundle.zip")).header(ServeHttpServer.TOKEN_HEADER, token).build()
    client.newCall(request).execute().use { assertEquals(403, it.code) }
  }
}
