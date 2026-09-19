package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The serve mechanism's client. Most of these are about the credential rather than the upload: this
 * is the one place in the CLI that sends a GitHub token to a host somebody named on a command line,
 * so where it may go, where it comes from, and where it must never appear are all pinned.
 *
 * The protocol-level cases drive a bare JDK [HttpServer] — the client has to be tested against
 * answers a real serve host would never give (a redirect, a refusal), which is exactly the point.
 * [`a real serve host and this client agree`] closes the loop against the actual endpoint.
 */
class SharePreviewServeUploadTest {

  // ---- where a credential may be sent -------------------------------------------------------

  @Test
  fun `a github token is only ever sent over https, or to loopback`() {
    assertNull(ServeImageUploader.rejectUnsafeUrl("https://preview.coo.ee"))
    assertNull(ServeImageUploader.rejectUnsafeUrl("http://127.0.0.1:8080"))
    assertNull(ServeImageUploader.rejectUnsafeUrl("http://localhost:8080"))

    val plaintext = ServeImageUploader.rejectUnsafeUrl("http://preview.coo.ee")
    assertNotNull(plaintext)
    assertTrue(plaintext.contains("https://"), plaintext)
  }

  @Test
  fun `a url carrying credentials is refused rather than used`() {
    val refusal = ServeImageUploader.rejectUnsafeUrl("https://user:hunter2@preview.coo.ee")
    assertNotNull(refusal)
    assertTrue(refusal.contains("credentials"), refusal)
    // The refusal must not repeat what it refused.
    assertFalse(refusal.contains("hunter2"), refusal)
  }

  @Test
  fun `nonsense is refused with the shape it wanted`() {
    assertNotNull(ServeImageUploader.rejectUnsafeUrl("preview.coo.ee"))
    assertNotNull(ServeImageUploader.rejectUnsafeUrl("ftp://preview.coo.ee"))
    assertNotNull(ServeImageUploader.rejectUnsafeUrl(""))
  }

  // ---- where a credential comes from --------------------------------------------------------

  @Test
  fun `an explicit token file outranks an inherited environment variable`() {
    val file = File.createTempFile("token", ".txt").apply { writeText("  from-the-file\n") }
    try {
      val resolved =
        AgentGithubToken.resolve(
          tokenFile = file.path,
          env = { if (it == "GITHUB_TOKEN") "from-the-env" else null },
        ) as AgentGithubToken.Result.Ok
      assertEquals("from-the-file", resolved.token, "and trimmed")
      assertEquals("--github-token-file", resolved.source)
    } finally {
      file.delete()
    }
  }

  @Test
  fun `the environment is read in order, then gh`() {
    val fromGithubToken =
      AgentGithubToken.resolve(null, env = { if (it == "GITHUB_TOKEN") "a" else "b" })
        as AgentGithubToken.Result.Ok
    assertEquals("a", fromGithubToken.token)
    assertEquals("\$GITHUB_TOKEN", fromGithubToken.source)

    val fromGhToken =
      AgentGithubToken.resolve(null, env = { if (it == "GH_TOKEN") "b" else null })
        as AgentGithubToken.Result.Ok
    assertEquals("b", fromGhToken.token)

    val fromCli =
      AgentGithubToken.resolve(null, env = { null }, ghToken = { "c" })
        as AgentGithubToken.Result.Ok
    assertEquals("c", fromCli.token)
    assertEquals("gh auth token", fromCli.source)
  }

  @Test
  fun `a blank credential is no credential, and the error names every safe source`() {
    val err =
      AgentGithubToken.resolve(null, env = { "   " }, ghToken = { "" })
        as AgentGithubToken.Result.Err
    assertTrue(err.message.contains("GITHUB_TOKEN"), err.message)
    assertTrue(err.message.contains("--github-token-file"), err.message)
    assertTrue(err.message.contains("gh auth login"), err.message)
    // The reason there is no flag is part of the message, so the next reader doesn't add one.
    assertTrue(err.message.contains("visible in `ps`"), err.message)
  }

  @Test
  fun `a missing or empty token file is an error, not a silent fallthrough`() {
    val missing = AgentGithubToken.resolve("/no/such/token", env = { "from-the-env" })
    assertTrue(missing is AgentGithubToken.Result.Err)
    val empty = File.createTempFile("token", ".txt").apply { writeText("\n") }
    try {
      assertTrue(
        AgentGithubToken.resolve(empty.path, env = { "from-the-env" })
          is AgentGithubToken.Result.Err,
        "an empty file must not fall back to the environment",
      )
    } finally {
      empty.delete()
    }
  }

  // ---- the upload itself --------------------------------------------------------------------

  @Test
  fun `an upload sends the credential as a bearer header and returns the absolute url`() {
    val seen = mutableListOf<Recorded>()
    withServer(
      seen,
      status = 201,
      body = """{"url":"https://preview.coo.ee/i/abc.png","expiresIn":"7d"}""",
    ) { base ->
      val result = ServeImageUploader(base, "gho_secret").upload(png(), label = "before.png")
      assertEquals("https://preview.coo.ee/i/abc.png", (result as ServeImageUploader.Result.Ok).url)
      assertEquals("7d", result.expiresIn)
    }
    val request = seen.single()
    assertEquals("Bearer gho_secret", request.authorization)
    assertTrue(request.query.startsWith("name=before.png"), request.query)
    // The credential rides in the header and nowhere else — a query parameter would land in the
    // host's access log, and in any proxy's between here and there.
    assertFalse(request.query.contains("gho_secret"), request.query)
    assertEquals(3, request.bodyBytes, "the file's bytes, not a form wrapper")
  }

  @Test
  fun `a redirect is refused rather than followed`() {
    val seen = mutableListOf<Recorded>()
    withServer(seen, status = 302, body = "", location = "https://elsewhere.example/images") { base
      ->
      val reason =
        (ServeImageUploader(base, "gho_secret").upload(png()) as ServeImageUploader.Result.Failed)
          .reason
      assertTrue(reason.contains("redirect"), reason)
      assertTrue(reason.contains("elsewhere.example"), reason)
      assertFalse(reason.contains("gho_secret"), reason)
    }
    // Nothing was sent to the redirect target: the client only ever made the one call.
    assertEquals(1, seen.size)
  }

  @Test
  fun `a refusal from the host is reported without the credential in it`() {
    val seen = mutableListOf<Recorded>()
    withServer(
      seen,
      status = 403,
      body = "GitHub user stranger does not have access to yschimke/compose-ai-tools.",
    ) { base ->
      val reason =
        (ServeImageUploader(base, "gho_secret").upload(png()) as ServeImageUploader.Result.Failed)
          .reason
      assertTrue(reason.contains("403"), reason)
      assertTrue(reason.contains("does not have access"), reason)
      assertFalse(reason.contains("gho_secret"), reason)
    }
  }

  @Test
  fun `a bodyless 404 says the lane is off rather than leaving a bare status`() {
    // What a serve host started WITHOUT --accept-images actually answers: the route is not
    // registered, so the 404 carries no body to explain itself. preview.coo.ee answered exactly
    // this, and "answered 404" alone reads as a wrong URL.
    val seen = mutableListOf<Recorded>()
    withServer(seen, status = 404, body = "") { base ->
      val reason =
        (ServeImageUploader(base, "gho_secret").upload(png()) as ServeImageUploader.Result.Failed)
          .reason
      assertTrue(reason.contains("404"), reason)
      assertTrue(reason.contains("--accept-images"), reason)
      // A stray path on --serve-url produces the same empty 404 from a host whose lane IS on, so
      // the message offers the lane as the likely cause and names the other thing to check.
      assertTrue(reason.contains("most likely"), reason)
      assertTrue(reason.contains("--serve-url"), reason)
      assertFalse(reason.contains("gho_secret"), reason)
    }
  }

  @Test
  fun `a bodyless 405 gets the same explanation as a bodyless 404`() {
    // Disabling the lane can leave a catch-all that matches the path but not POST, so the same
    // configuration answers 405 instead — ServeImageRoutingTest accepts either.
    val seen = mutableListOf<Recorded>()
    withServer(seen, status = 405, body = "") { base ->
      val reason =
        (ServeImageUploader(base, "gho_secret").upload(png()) as ServeImageUploader.Result.Failed)
          .reason
      assertTrue(reason.contains("405"), reason)
      assertTrue(reason.contains("--accept-images"), reason)
    }
  }

  @Test
  fun `a 404 that explains itself is passed through as the host wrote it`() {
    val seen = mutableListOf<Recorded>()
    withServer(seen, status = 404, body = "no such image") { base ->
      val reason =
        (ServeImageUploader(base, "gho_secret").upload(png()) as ServeImageUploader.Result.Failed)
          .reason
      assertTrue(reason.contains("no such image"), reason)
      assertFalse(reason.contains("--accept-images"), reason)
    }
  }

  @Test
  fun `a host token rides in the query, where that host's other routes read it`() {
    val seen = mutableListOf<Recorded>()
    withServer(seen, status = 201, body = """{"url":"https://h/i/a.png"}""") { base ->
      ServeImageUploader(base, "gho_secret", hostToken = "browse").upload(png(), label = "a.png")
    }
    assertTrue(seen.single().query.contains("token=browse"), seen.single().query)
  }

  @Test
  fun `the real serve host has the route this client posts to`() {
    // What survives of the old round trip, and why it is less.
    //
    // This used to build a `ServeHttpServer` in this JVM with an `imageUploadAuth` stub that
    // admitted one token, so the whole upload completed and the client parsed a real `201`. The
    // server is a launched distribution now (compose-preview-server publishes no jar), and that
    // seam is in-process only: a real host authenticates an uploader against GitHub, which a test
    // cannot stand in for from outside the process.
    //
    // So the assertion narrows to the half that can still be checked against the real thing, and
    // it is the half that actually drifts: an unknown bearer is REFUSED, with the server's own
    // words about repository access, rather than 404ed. That is only true if four things line up —
    // the path (`/images`), the method, the host-token query this client puts the operator
    // credential in, and the `Authorization: Bearer` the route reads. The response *parsing* —
    // `201`, the `url` field, `expiresIn` — is covered by the stubbed cases above, which can answer
    // anything including answers a real host would not.
    //
    // The host token is load-bearing and is the trap this test exists to keep sprung: a caller that
    // omits it gets **404**, not 401, because the server hides a token-gated surface rather than
    // advertising it. So "the route is missing" and "I forgot the credential" look identical from
    // outside, and only sending it correctly tells them apart.
    val session = ServeDistributionHarness.start()
    if (session == null) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, ServeDistributionHarness.skipReason())
      return
    }
    session.use {
      val file = File.createTempFile("shot", ".png").apply { writeBytes(realPng()) }
      try {
        val result =
          ServeImageUploader(
              it.origin,
              "gho_not_a_real_token",
              hostToken = ServeDistributionHarness.OPERATOR_TOKEN,
            )
            .upload(file, label = "after.png")
        // Not `Ok`, and not the shape a missing route produces either. `refused` carries the
        // server's own words, so a 404 would read as one here and fail.
        val refused = result as? ServeImageUploader.Result.Failed
        assertNotNull(refused, "an unknown bearer should be refused, not accepted: $result")
        assertFalse(
          refused.reason.contains("404"),
          "the upload route is missing, or the host token did not reach it — either way this " +
            "client is posting somewhere the server does not serve: " +
            refused.reason,
        )
        // Deliberately NOT asserted: the refusal's wording. It depends on how far the bearer got,
        // which is not a property of this repository — a token GitHub rejects outright and one it
        // resolves to a user without access produce different sentences, and which of those a bogus
        // string lands on differs between a CI runner and a developer's box behind a proxy that
        // supplies an identity. The first version of this test asserted "collaborator" or "access"
        // and passed locally for exactly that reason before failing on a clean runner.
        //
        // The status is the part that belongs to this wire, and it is checked above.
      } finally {
        file.delete()
      }
    }
  }

  // ---- harness ------------------------------------------------------------------------------

  private class Recorded(val query: String, val authorization: String?, val bodyBytes: Int)

  /**
   * A one-shot HTTP endpoint answering [status]/[body], recording what it was sent. The JDK's own
   * server rather than a test dependency, and deliberately dumb: what is under test is the client's
   * behaviour in the face of an answer, including answers a real host would never give.
   */
  private fun withServer(
    into: MutableList<Recorded>,
    status: Int,
    body: String,
    location: String? = null,
    block: (String) -> Unit,
  ) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/images") { exchange: HttpExchange ->
      val sent = exchange.requestBody.readBytes()
      into +=
        Recorded(
          query = exchange.requestURI.query ?: "",
          authorization = exchange.requestHeaders.getFirst("Authorization"),
          bodyBytes = sent.size,
        )
      location?.let { exchange.responseHeaders.add("Location", it) }
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    server.start()
    try {
      block("http://127.0.0.1:${server.address.port}")
    } finally {
      server.stop(0)
    }
  }

  private fun png(): File =
    File.createTempFile("shot", ".png").apply {
      writeBytes(byteArrayOf(1, 2, 3))
      deleteOnExit()
    }

  /** A real PNG, for the round trip that goes through the host's content sniff. */
  private fun realPng(): ByteArray {
    val header =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 13) +
        "IHDR".toByteArray() +
        byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0)
    // Enough of a document for the sniff (signature + IHDR); the host stores bytes, it never
    // decodes them.
    return header + ByteArray(16)
  }
}

/** Rewriting a report's relative image references onto the URLs they were uploaded to. */
class SharePreviewMarkdownTest {

  private val uploaded =
    mapOf(
      "before.png" to "https://preview.coo.ee/i/one.png",
      "after.png" to "https://preview.coo.ee/i/two.png",
    )

  @Test
  fun `references are rewritten by basename, whatever path they used`() {
    val markdown =
      """
      | before | after |
      | --- | --- |
      | ![before](before.png) | ![after](./renders/after.png) |
      """
        .trimIndent()
    val rewritten = SharePreviewMarkdown.rewrite(markdown, uploaded)
    assertTrue(rewritten.contains("![before](https://preview.coo.ee/i/one.png)"), rewritten)
    assertTrue(rewritten.contains("![after](https://preview.coo.ee/i/two.png)"), rewritten)
  }

  @Test
  fun `a reference to something that was not uploaded is left alone`() {
    val markdown = "![untouched](diagram.svg) and ![before](before.png)"
    val rewritten = SharePreviewMarkdown.rewrite(markdown, uploaded)
    assertTrue(rewritten.contains("![untouched](diagram.svg)"), rewritten)
    assertTrue(rewritten.contains("https://preview.coo.ee/i/one.png"), rewritten)
  }

  @Test
  fun `a backticked destination is not treated as a reference`() {
    // The malformed shape the PR-body rule warns about. Rewriting it would produce a link that
    // still renders as literal text, quietly turning a broken embed into a broken embed with a
    // real URL in it — better to leave it visibly wrong.
    val markdown = "![before](`before.png`)"
    assertEquals(markdown, SharePreviewMarkdown.rewrite(markdown, uploaded))
  }

  @Test
  fun `plain links and surrounding prose are untouched`() {
    val markdown = "See [the report](before.png) — text stays, `code` stays."
    assertEquals(markdown, SharePreviewMarkdown.rewrite(markdown, uploaded))
  }

  @Test
  fun `an empty map is a no-op`() {
    val markdown = "![before](before.png)"
    assertEquals(markdown, SharePreviewMarkdown.rewrite(markdown, emptyMap()))
  }
}
