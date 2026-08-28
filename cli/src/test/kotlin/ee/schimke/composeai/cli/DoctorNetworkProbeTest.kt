package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [DoctorCommand.headPlain] after its move to the Ktor/OkHttp client. A throwaway
 * loopback [HttpServer] stands in for a remote host — no real network. Asserts the behaviour
 * [checkNetworkReach] depends on: a real response (any status) reports its code, an unreachable
 * host folds to `-1 to {error}` without throwing.
 */
class DoctorNetworkProbeTest {
  private var server: HttpServer? = null

  @AfterTest
  fun cleanup() {
    server?.stop(0)
  }

  private fun startServer(status: Int): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange ->
      exchange.responseHeaders.add("X-Probe", "yes")
      exchange.sendResponseHeaders(status, -1)
      exchange.close()
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}/"
  }

  @Test
  fun `reachable host returns its status code and headers`() {
    val url = startServer(200)

    val (code, headers) = DoctorCommand(emptyList()).headPlain(url)

    assertEquals(200, code)
    assertTrue(
      headers.keys.any { it.equals("X-Probe", ignoreCase = true) },
      "expected the probe header to round-trip: $headers",
    )
  }

  @Test
  fun `a non-2xx is still a reachable response, not an error`() {
    val url = startServer(404)

    val (code, _) = DoctorCommand(emptyList()).headPlain(url)

    assertEquals(404, code)
  }

  /**
   * A captive portal's shape: `/` redirects to a login page that answers 200.
   *
   * Following it reports the LOGIN PAGE's status as the probed host's, so an endpoint that never
   * answered reads as `reachable (HTTP 200)` — the exact opposite of the truth, from the check
   * whose whole job is to notice that egress is intercepted.
   */
  private fun startPortal(): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/login") { exchange ->
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    s.createContext("/") { exchange ->
      exchange.responseHeaders.add("Location", "/login")
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}/"
  }

  @Test
  fun `a redirect is reported, not followed to whatever answers at the other end`() {
    val url = startPortal()

    val (code, headers) = DoctorCommand(emptyList()).headPlain(url)

    assertEquals(302, code, "the probe stops at the redirect rather than reporting the login page")
    assertTrue(
      headers.entries.any { (k, v) -> k.equals("Location", ignoreCase = true) && v == "/login" },
      "the redirect target is carried through for the check to name: $headers",
    )
  }

  @Test
  fun `an unreachable host returns -1 and an error, never throws`() {
    // Nothing is listening on port 1 — the connection is refused fast, well inside the 3s timeout.
    val (code, headers) = DoctorCommand(emptyList()).headPlain("http://127.0.0.1:1/")

    assertEquals(-1, code)
    assertTrue(headers.containsKey("error"), "expected an error entry: $headers")
  }
}

/**
 * Coverage for [DoctorCommand.networkCheck] — the classification that decides whether a probe
 * result is a tick or a warning. Pure; no server needed.
 */
class DoctorNetworkCheckTest {
  private val gstatic = DoctorCommand.NETWORK_HOSTS.first { it.id == "fonts-gstatic" }
  private val googleapis = DoctorCommand.NETWORK_HOSTS.first { it.id == "fonts-googleapis" }

  @Test
  fun `a 2xx is ok`() {
    val check = DoctorCommand.networkCheck(googleapis, 200, null, inClaudeCloud = false)

    assertEquals("ok", check.status)
    assertEquals("env.network.fonts-googleapis", check.id)
    assertTrue("HTTP 200" in check.message, check.message)
  }

  @Test
  fun `a documented 404 is ok but says why`() {
    val check = DoctorCommand.networkCheck(gstatic, 404, null, inClaudeCloud = false)

    assertEquals("ok", check.status)
    assertTrue("versioned font paths" in check.message, check.message)
  }

  @Test
  fun `an undocumented non-2xx is a warning, not a tick`() {
    // The regression from yschimke/skills#52: `✓ fonts.googleapis.com reachable (HTTP 404)`.
    val check = DoctorCommand.networkCheck(googleapis, 404, null, inClaudeCloud = false)

    assertEquals("warning", check.status)
    assertTrue("HTTP 404" in check.message, check.message)
    assertTrue("reachable" !in check.message, check.message)
  }

  @Test
  fun `an intercepting proxy status is a warning with the allowlist remediation`() {
    val check = DoctorCommand.networkCheck(gstatic, 403, null, inClaudeCloud = true)

    assertEquals("warning", check.status)
    assertTrue("proxy or sandbox" in (check.detail ?: ""), check.detail.orEmpty())
    assertTrue("Custom" in (check.remediation?.summary ?: ""), check.remediation?.summary.orEmpty())
  }

  @Test
  fun `a redirect is named as an interception, with where it pointed`() {
    val check =
      DoctorCommand.networkCheck(
        gstatic,
        302,
        null,
        inClaudeCloud = false,
        redirectTarget = "http://portal.example/login",
      )

    assertEquals("warning", check.status)
    assertTrue("redirected" in check.message, check.message)
    assertTrue("reachable" !in check.message, check.message)
    assertTrue("portal.example/login" in (check.detail ?: ""), check.detail.orEmpty())
    assertTrue("captive portal" in (check.detail ?: ""), check.detail.orEmpty())
  }

  @Test
  fun `the healthy-response wording follows the probe, not a blanket 2xx`() {
    // `fonts.gstatic.com` documents a 404 on `/` — it serves versioned asset paths only — so
    // telling its operator the url "returns 2xx when egress is healthy" contradicted the probe's
    // own configuration, in the one message they have to reason from.
    val intercepted = DoctorCommand.networkCheck(gstatic, 403, null, inClaudeCloud = false)
    assertTrue("2xx or HTTP 404" in (intercepted.detail ?: ""), intercepted.detail.orEmpty())

    // A probe with no documented exception still says plain 2xx.
    val plain = DoctorCommand.networkCheck(googleapis, 403, null, inClaudeCloud = false)
    assertTrue("answers 2xx when egress is healthy" in (plain.detail ?: ""), plain.detail.orEmpty())
    assertTrue("404" !in (plain.detail ?: ""), plain.detail.orEmpty())
  }

  @Test
  fun `no response at all still reports unreachable with the transport error`() {
    val check = DoctorCommand.networkCheck(gstatic, -1, "Connection refused", inClaudeCloud = false)

    assertEquals("warning", check.status)
    assertTrue("unreachable" in check.message, check.message)
    assertTrue("Connection refused" in (check.detail ?: ""), check.detail.orEmpty())
  }

  @Test
  fun `every probe url points at the host it reports on`() {
    // The check message names the host, so a URL that drifted onto a different one would report a
    // reachability verdict about somewhere else entirely.
    DoctorCommand.NETWORK_HOSTS.forEach { probe ->
      assertTrue(
        probe.url.startsWith("https://${probe.host}/"),
        "${probe.id} probes ${probe.url}, which isn't on ${probe.host}",
      )
    }
  }
}
