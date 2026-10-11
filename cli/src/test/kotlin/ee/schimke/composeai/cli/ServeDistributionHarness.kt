package ee.schimke.composeai.cli

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files

/**
 * A real `compose-preview-server` process for tests that check this CLI's wire against it. A
 * process rather than a library: the server is no longer published as a jar, and what users run is
 * the distribution over HTTP, so that is what should be tested.
 *
 * Never fetches (a 120 MB download in a unit test would tie `:cli:test` to the network and to
 * whichever release is newest). Uses `COMPOSE_PREVIEW_SERVER` (what CI sets; locally after
 * `./gradlew :server:installDist` in a compose-preview-server checkout), else the newest copy
 * [ServerDistributionProvision] has cached. With neither, [binary] is null and the tests skip; the
 * opt-in CI job that sets the variable fails rather than skips if the server won't start.
 */
internal object ServeDistributionHarness {

  /**
   * The operator credential every harness server starts with. Token-gated surfaces answer 404
   * without it, so tests need it to tell "no route" from "no credential".
   */
  const val OPERATOR_TOKEN: String = "operator-secret"

  /** Set by CI to make a skip a failure: the job that provisions a server means these to run. */
  const val REQUIRE_ENV: String = "COMPOSE_PREVIEW_SERVE_TESTS_REQUIRE"

  /**
   * The repository `--accept-images` checks uploader access against. Any repo works: no test holds
   * a credential for it, so every bearer is refused, which is what the tests assert.
   */
  const val IMAGE_REPO: String = "yschimke/compose-ai-tools"

  /** The launcher to drive, or null when this machine has none. */
  val binary: File? by lazy {
    val explicit =
      System.getenv(ServerBinaryDiscovery.ENV)?.trim()?.takeIf { it.isNotBlank() }?.let(::File)
    when {
      explicit != null && ServerDistributionProvision.isComplete(explicit) -> explicit
      explicit != null -> null
      else -> ServerDistributionProvision.cached()
    }
  }

  /** Whether a missing server is a failure rather than a skip. */
  val required: Boolean
    get() = System.getenv(REQUIRE_ENV)?.trim().equals("1", ignoreCase = true)

  /** Message for a skipping test, naming which of the two reasons applies. */
  fun skipReason(): String =
    "no compose-preview-server distribution: set ${ServerBinaryDiscovery.ENV} to a launcher " +
      "(`./gradlew :server:installDist` in a compose-preview-server checkout), or run " +
      "`compose-preview serve` once to cache one"

  /** A port nothing holds, taken by binding and releasing — the server is told which to use. */
  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  /**
   * A started server, or null when there is no binary. Close with [Session.close].
   *
   * `serve` refuses to start with no lane, and a throwaway temp dir isn't a valid bundle, so
   * `--accept-images` (with `--image-upload-repo`) provides one — the lane
   * `SharePreviewServeUploadTest` posts into, where a refusal rather than a 404 is the expected
   * result. `--agent-grants` enables the `/agent-access/…` routes, with the scope ceiling and TTL
   * the tests expect.
   */
  fun start(token: String = OPERATOR_TOKEN): Session? {
    val launcher = binary ?: return null
    val dir = Files.createTempDirectory("serve-harness").toFile().also { it.deleteOnExit() }
    val port = freePort()
    val process =
      ProcessBuilder(
          launcher.absolutePath,
          "serve",
          "--port",
          port.toString(),
          "--token",
          token,
          "--agent-grants",
          "--agent-grant-scopes",
          "playground",
          "--agent-grant-max-ttl",
          "3600",
          "--accept-images",
          "--image-upload-repo",
          IMAGE_REPO,
        )
        .redirectErrorStream(true)
        .redirectOutput(File(dir, "server.log"))
        .start()
    val session = Session(process, "http://127.0.0.1:$port", token, File(dir, "server.log"))
    return if (session.awaitReady()) session
    else {
      session.close()
      error("the server did not answer on ${session.origin}:\n${session.log()}")
    }
  }

  /** A running server and the operator credential that drives its approval pages. */
  internal class Session(
    private val process: Process,
    val origin: String,
    private val token: String,
    private val logFile: File,
  ) : AutoCloseable {

    fun log(): String = logFile.takeIf { it.isFile }?.readText().orEmpty()

    /** Polls until the server answers anything at all, or gives up. */
    fun awaitReady(timeoutMillis: Long = 60_000): Boolean {
      val deadline = System.currentTimeMillis() + timeoutMillis
      while (System.currentTimeMillis() < deadline) {
        if (!process.isAlive) return false
        val code = runCatching { get("/", follow = false).first }.getOrNull()
        if (code != null) return true
        Thread.sleep(200)
      }
      return false
    }

    /** The operator credential as form submissions take it. */
    private fun tokenQuery() = "?token=" + URLEncoder.encode(token, Charsets.UTF_8)

    /**
     * Fetch an operator page as a machine client, via the token header: a `?token=` query is
     * exchanged for a cookie and redirected, which [HttpURLConnection] (no cookie jar) would lose.
     */
    private fun operatorGet(path: String): Pair<Int, String> {
      val connection = URI(origin + path).toURL().openConnection() as HttpURLConnection
      connection.setRequestProperty("X-Compose-Preview-Token", token)
      return connection.use { it.responseCode to it.bodyText() }
    }

    fun get(path: String, follow: Boolean = true): Pair<Int, String> {
      val connection = URI(origin + path).toURL().openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = follow
      return connection.use { it.responseCode to it.bodyText() }
    }

    fun postForm(path: String, form: Map<String, String>): Pair<Int, String> {
      val connection = URI(origin + path).toURL().openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.instanceFollowRedirects = false
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      val body =
        form.entries.joinToString("&") { (k, v) ->
          URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
        }
      connection.outputStream.use { it.write(body.toByteArray()) }
      return connection.use { it.responseCode to it.bodyText() }
    }

    /**
     * The approval page for [requestId] as the operator sees it. Fetched, because its CSRF seal is
     * bound to the request, approver and action, so the human half of the flow is really exercised.
     */
    fun approvalPage(requestId: String): String {
      val (code, body) = operatorGet("/agent-access/$requestId")
      check(code == 200) { "approval page for $requestId answered $code:\n$body" }
      return body
    }

    private fun hiddenField(page: String, name: String): String =
      Regex("""name="$name"[^>]*value="([^"]*)"""").find(page)?.groupValues?.get(1)
        ?: Regex("""value="([^"]*)"[^>]*name="$name"""").find(page)?.groupValues?.get(1)
        ?: error("no $name field on the approval page; the form shape changed:\n$page")

    /** Approve [requestId] at [scope], through the real form. */
    fun approve(requestId: String, scope: String, ttlSeconds: Int) {
      val page = approvalPage(requestId)
      val (code, body) =
        postForm(
          "/agent-access/$requestId" + tokenQuery(),
          mapOf(
            "action" to "approve",
            "csrf" to hiddenField(page, "csrf"),
            "scope" to scope,
            "ttlSeconds" to ttlSeconds.toString(),
          ),
        )
      check(code in 200..399) { "approving $requestId answered $code:\n$body" }
    }

    /** Deny [requestId], through the real form. */
    fun deny(requestId: String) {
      val page = approvalPage(requestId)
      val (code, body) =
        postForm(
          "/agent-access/$requestId" + tokenQuery(),
          mapOf("action" to "deny", "denyCsrf" to hiddenField(page, "denyCsrf")),
        )
      check(code in 200..399) { "denying $requestId answered $code:\n$body" }
    }

    override fun close() {
      process.destroy()
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }
  }
}

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
  try {
    block(this)
  } finally {
    disconnect()
  }

private fun HttpURLConnection.bodyText(): String =
  (runCatching { inputStream }.getOrNull() ?: errorStream)?.bufferedReader()?.use { it.readText() }
    ?: ""
