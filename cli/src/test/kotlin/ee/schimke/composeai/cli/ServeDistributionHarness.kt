package ee.schimke.composeai.cli

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files

/**
 * A real `compose-preview-server` process, for the tests that check this CLI's wire against it.
 *
 * ## Why a process rather than a library
 *
 * These tests used to construct `ServeHttpServer` in this JVM, from the published
 * `compose-preview-serve` jar. compose-preview-server does not publish to Maven Central any more —
 * its artifacts are the `.tar.gz` distributions on each GitHub release — so there is no jar to
 * link.
 *
 * That is a better fit for what the tests are for rather than a workaround for what was lost.
 * `serve` and `browse` have been launchers since #5177: what an installed CLI talks to is a
 * *process*, over HTTP, and the thing that can drift is the wire between them. Driving the
 * distribution is driving the artifact a user actually runs; driving the jar was driving a linkage
 * nothing ships.
 *
 * ## Why these tests skip rather than fetch
 *
 * A 120 MB download inside a unit test would make `:cli:test` depend on the network and on
 * whichever release happens to be newest that hour. So the harness **never fetches**. It uses a
 * server this machine already has:
 *
 * * `COMPOSE_PREVIEW_SERVER` — the same variable [ServerBinaryDiscovery] reads, pointing at a
 *   launcher. This is what CI sets, and what to set locally after `./gradlew :server:installDist`
 *   in a compose-preview-server checkout.
 * * otherwise the newest complete copy [ServerDistributionProvision] has cached, which a machine
 *   that has run `compose-preview serve` once will have.
 *
 * With neither, [binary] is null and the tests that need it skip. A silent skip is a real cost —
 * this repository refuses them elsewhere — which is why the opt-in CI job that sets the variable is
 * the one that makes these tests binding, and why it fails rather than skips when the variable is
 * set and the server will not start.
 */
internal object ServeDistributionHarness {

  /**
   * The operator credential the harness starts every server with.
   *
   * Exposed because a caller needs it: the token-gated surfaces answer **404** without it rather
   * than 401, so a test that omits it cannot tell "no such route" from "no credential".
   */
  const val OPERATOR_TOKEN: String = "operator-secret"

  /** Set by CI to make a skip a failure: the job that provisions a server means these to run. */
  const val REQUIRE_ENV: String = "COMPOSE_PREVIEW_SERVE_TESTS_REQUIRE"

  /**
   * The repository `--accept-images` checks uploader access against.
   *
   * Any `owner/repo` would do — no test holds a credential for it, and that is the point: the
   * upload lane exists so the route is served, and every bearer these tests send is refused.
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

  /**
   * The message a skipping test carries, so a reader of a green run can tell which of the two
   * reasons applies without going looking.
   */
  fun skipReason(): String =
    "no compose-preview-server distribution: set ${ServerBinaryDiscovery.ENV} to a launcher " +
      "(`./gradlew :server:installDist` in a compose-preview-server checkout), or run " +
      "`compose-preview serve` once to cache one"

  /** A port nothing holds, taken by binding and releasing — the server is told which to use. */
  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  /**
   * A started server, or null when there is no binary to start. Close it with [Session.close].
   *
   * ## Why there is no bundle
   *
   * `serve` refuses to start with nothing to serve — "no `--bundle` / `--bundles` / `--catalogs`
   * registered a session, and none of `--accept-bundles` / `--accept-docs` / `--accept-images` /
   * `--ui-builder-dir` / `--admin-token` is set" — so something has to stand a lane up. A bundle is
   * the wrong thing to hand it: nothing here renders a preview, and a directory is only accepted as
   * one if its name derives a usable session id, which a throwaway temp directory does not.
   *
   * `--accept-images` is the right one, and not only because it starts: it is the lane
   * `SharePreviewServeUploadTest` posts into. It needs a repository to check uploader access
   * against, which is why `--image-upload-repo` rides with it — uploads still authenticate against
   * GitHub, so a test bearer is refused, and *being refused rather than 404ed* is exactly what that
   * test asserts.
   *
   * `--agent-grants` is off by default and the `/agent-access/…` routes do not exist without it.
   * The scope ceiling and TTL match what the in-process fixture used to construct directly, so the
   * assertions about `maxScope` still mean the same thing.
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

    /** The operator credential as the approval pages take it — in the query, as `serve` does. */
    private fun tokenQuery() = "?token=" + URLEncoder.encode(token, Charsets.UTF_8)

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
     * The approval page for [requestId], as the operator sees it.
     *
     * Fetched rather than constructed because the CSRF seal is issued by that page and bound to the
     * request, the approver and the action — there is no way to mint one from outside, which is the
     * point of it. So the human half of the flow is genuinely exercised here rather than bypassed.
     */
    fun approvalPage(requestId: String): String {
      val (code, body) = get("/agent-access/$requestId" + tokenQuery())
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
