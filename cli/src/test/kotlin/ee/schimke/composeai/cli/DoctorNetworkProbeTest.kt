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

  @Test
  fun `an unreachable host returns -1 and an error, never throws`() {
    // Nothing is listening on port 1 — the connection is refused fast, well inside the 3s timeout.
    val (code, headers) = DoctorCommand(emptyList()).headPlain("http://127.0.0.1:1/")

    assertEquals(-1, code)
    assertTrue(headers.containsKey("error"), "expected an error entry: $headers")
  }
}
