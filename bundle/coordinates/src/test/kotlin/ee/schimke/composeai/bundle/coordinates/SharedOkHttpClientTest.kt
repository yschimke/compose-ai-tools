package ee.schimke.composeai.bundle.coordinates

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedOkHttpClientTest {
  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        val body = "ok".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      start()
    }

  @AfterTest fun stop() = server.stop(0)

  @Test
  fun `closing a Ktor client leaves the shared client's pool and dispatcher alone`() {
    val url = "http://127.0.0.1:${server.address.port}/"
    repeat(2) {
      val body = ktorOkHttpClient().use { client -> runBlocking { client.get(url).bodyAsText() } }
      assertEquals("ok", body)
    }

    // Ktor tears an engine down once its in-flight work completes, after `close` returns.
    Thread.sleep(500)
    val shared = SharedOkHttpClient.client
    assertFalse(shared.dispatcher.executorService.isShutdown)
    assertTrue(
      shared.connectionPool.idleConnectionCount() > 0,
      "the keep-alive connection survived",
    )
  }

  @Test
  fun `the shared client carries the certificate check`() {
    assertTrue(SharedOkHttpClient.client.hostnameVerifier is ForbiddenCertificatesHostnameVerifier)
  }
}
