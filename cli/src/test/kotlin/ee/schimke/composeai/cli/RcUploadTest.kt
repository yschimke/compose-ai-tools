package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpServer
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * `rc upload` against a bare JDK [HttpServer] standing in for a `serve --accept-docs` host, so the
 * client can face answers a real host never gives.
 */
class RcUploadTest {

  private val fs = FakeFileSystem()
  private val doc = "/work/card.rc".toPath()

  private val document: ByteArray =
    RemoteComposeJson.compile(
      """{"header":{"width":10,"height":10},"root":[{"box":{"modifiers":[{"size":10.0}]}}]}"""
    )

  private class Exited(val code: Int) : RuntimeException()

  private val out = mutableListOf<String>()
  private val err = mutableListOf<String>()

  /** What the fake host saw: query string and body of each `POST /docs`. */
  private val received = mutableListOf<Pair<String, ByteArray>>()

  private var server: HttpServer? = null

  @AfterTest fun stop() = server?.stop(0) ?: Unit

  /** Start a host answering `POST /docs` with [status] and [body]; returns its base URL. */
  private fun host(
    status: Int = 201,
    body: String = ACCEPTED,
    headers: Map<String, String> = emptyMap(),
  ): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/docs") { exchange ->
      received += (exchange.requestURI.rawQuery ?: "") to exchange.requestBody.readBytes()
      val bytes = body.toByteArray()
      headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  private fun run(
    vararg args: String,
    env: Map<String, String> = emptyMap(),
    projectRoot: File? = null,
  ) =
    RcCommand(
        args.toList(),
        fs,
        stdout = { out += it },
        stderr = { err += it },
        exit = { throw Exited(it) },
        env = { env[it] },
        projectRoot = { projectRoot },
      )
      .run()

  private fun runExpectingExit(
    vararg args: String,
    env: Map<String, String> = emptyMap(),
    projectRoot: File? = null,
  ): Int =
    try {
      run(*args, env = env, projectRoot = projectRoot)
      error("expected a non-zero exit")
    } catch (e: Exited) {
      e.code
    }

  private fun query(raw: String): Map<String, String> =
    raw.split('&').associate {
      it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

  @Test
  fun `uploads the document and prints an absolute permalink`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }
    val base = host()

    run("upload", doc.toString(), "--serve-url", base, "--serve-token", "operator-secret")

    assertEquals(listOf("$base/d/abc123"), out)
    val (rawQuery, body) = received.single()
    assertContentEquals(document, body)
    val q = query(rawQuery)
    assertEquals("card.rc", q["name"], "the label defaults to the file's basename")
    assertEquals("operator-secret", q["token"])
    assertContains(err.single(), "expires in 1h")
    // The token goes to the host and nowhere else.
    assertTrue(err.none { "operator-secret" in it }, err.toString())
  }

  @Test
  fun `--name labels the upload and --json prints the envelope with the absolute url`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }
    val base = host()

    run("upload", doc.toString(), "--name", "My card", "--json", env = mapOf(SERVE_URL_ENV to base))

    assertEquals("My card", query(received.single().first)["name"])
    assertNull(query(received.single().first)["token"], "no token was configured")
    val json = out.single()
    assertContains(json, "\"url\": \"$base/d/abc123\"")
    assertContains(json, "\"format\": \"Remote Compose\"")
    // The host's whole envelope survives, not just the fields the client models.
    assertContains(json, "\"schema\": \"compose-preview-serve/doc/v1\"")
    assertContains(json, "\"formatId\": \"rc\"")
  }

  @Test
  fun `a redirect is refused without echoing the token it carried`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }
    val base =
      host(
        status = 307,
        body = "",
        headers = mapOf("Location" to "https://elsewhere.example/docs?name=card.rc&token=s3cret"),
      )

    assertEquals(
      1,
      runExpectingExit("upload", doc.toString(), "--serve-url", base, "--serve-token", "s3cret"),
    )
    assertContains(err.last(), "https://elsewhere.example/docs")
    assertTrue(err.none { "s3cret" in it }, err.toString())
  }

  @Test
  fun `a file that is not a document is refused before anything is sent`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { writeUtf8("not a document") }
    val base = host()

    assertEquals(1, runExpectingExit("upload", doc.toString(), "--serve-url", base))
    assertTrue(received.isEmpty())
  }

  @Test
  fun `a host without the document lane is named as the likely cause`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }
    val base = host(status = 404, body = "")

    assertEquals(1, runExpectingExit("upload", doc.toString(), "--serve-url", base))
    assertContains(err.last(), "--accept-docs")
  }

  @Test
  fun `no host at all says how to name one`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }

    assertEquals(1, runExpectingExit("upload", doc.toString()))
    assertContains(err.last(), "--serve-url")
  }

  @Test
  fun `a host named only by the checkout is not uploaded to until confirmed`() {
    fs.createDirectories(doc.parent!!)
    fs.write(doc) { write(document) }
    val base = host()
    val project = kotlin.io.path.createTempDirectory("rc-upload").toFile()
    try {
      File(project, "gradle.properties").writeText("$SERVE_URL_PROPERTY=$base\n")

      assertEquals(1, runExpectingExit("upload", doc.toString(), projectRoot = project))
      assertTrue(received.isEmpty(), "an unconfirmed host must not receive the document")
    } finally {
      project.deleteRecursively()
    }
  }

  @Test
  fun `a token never travels over plain http to a remote host`() {
    assertNotNull(ServeDocUploader.rejectUnsafeUrl("http://preview.coo.ee", withToken = true))
    assertNull(ServeDocUploader.rejectUnsafeUrl("http://preview.coo.ee", withToken = false))
    assertNull(ServeDocUploader.rejectUnsafeUrl("http://127.0.0.1:8080", withToken = true))
    assertNull(ServeDocUploader.rejectUnsafeUrl("https://preview.coo.ee", withToken = true))
    assertNotNull(ServeDocUploader.rejectUnsafeUrl("https://u:p@preview.coo.ee", withToken = false))
    assertNotNull(ServeDocUploader.rejectUnsafeUrl("ftp://preview.coo.ee", withToken = false))
  }

  private companion object {
    const val ACCEPTED =
      """{"schema":"compose-preview-serve/doc/v1","id":"abc123","name":"card.rc",""" +
        """"format":"Remote Compose","formatId":"rc","bytes":42,"url":"/d/abc123",""" +
        """"expiresIn":"1h","expiresAtEpochSeconds":1700000000}"""
  }
}
