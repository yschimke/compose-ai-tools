package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for [BundleSource.resolveToFile] — the local-path-or-URL resolver every bundle-open
 * command shares. URL cases use a throwaway loopback [HttpServer] so no real network is touched.
 */
class BundleSourceTest {

  private val tmp = Files.createTempDirectory("bundle-source-test-").toFile()
  private var server: HttpServer? = null

  @AfterTest
  fun cleanup() {
    server?.stop(0)
    tmp.deleteRecursively()
  }

  private fun startServer(handler: (path: String) -> Pair<Int, ByteArray>): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange ->
      val (code, body) = handler(exchange.requestURI.path)
      exchange.sendResponseHeaders(code, if (body.isEmpty()) -1L else body.size.toLong())
      exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  @Test
  fun `local path resolves to the file`() {
    val f = File(tmp, "bundle.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertEquals(f.canonicalFile, BundleSource.resolveToFile(f.path).canonicalFile)
  }

  @Test
  fun `missing local path throws`() {
    val ex =
      assertFailsWith<IllegalArgumentException> { BundleSource.resolveToFile("/no/such.png") }
    assertTrue(ex.message!!.contains("not a file"))
  }

  @Test
  fun `file URL resolves to the referenced file`() {
    val f = File(tmp, "via-url.png").apply { writeBytes(byteArrayOf(4, 5)) }
    val resolved = BundleSource.resolveToFile(f.toURI().toString())
    assertEquals(f.canonicalFile, resolved.canonicalFile)
  }

  @Test
  fun `http URL downloads to a temp file`() {
    val payload = byteArrayOf(9, 8, 7, 6)
    val base = startServer { _ -> 200 to payload }

    val resolved = BundleSource.resolveToFile("$base/some/bundle.png")

    assertTrue(resolved.isFile)
    assertEquals(payload.toList(), resolved.readBytes().toList())
    assertTrue(resolved.name.endsWith(".png"))
  }

  @Test
  fun `http error status throws`() {
    val base = startServer { _ -> 404 to ByteArray(0) }
    val ex =
      assertFailsWith<IllegalArgumentException> { BundleSource.resolveToFile("$base/missing.png") }
    assertTrue(ex.message!!.contains("HTTP 404"), "expected HTTP status in message: ${ex.message}")
  }

  @Test
  fun `looksLikeUrl recognises schemes`() {
    assertTrue(BundleSource.looksLikeUrl("https://example.com/x.png"))
    assertTrue(BundleSource.looksLikeUrl("HTTP://example.com/x.png"))
    assertTrue(BundleSource.looksLikeUrl("file:///tmp/x.png"))
    assertTrue(!BundleSource.looksLikeUrl("/tmp/x.png"))
    assertTrue(!BundleSource.looksLikeUrl("bundle.png"))
  }
}
