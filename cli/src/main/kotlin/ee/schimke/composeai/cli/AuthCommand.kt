package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeAgentGrantScope
import ee.schimke.composeai.cli.serve.ServeAgentGrants
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `compose-preview auth …` — the agent's side of an access grant, per
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * ```
 * compose-preview auth request --server https://preview.coo.ee --scope live --ttl 2h \
 *     --label "fix wear-m3-catalog#68"
 * compose-preview auth status
 * compose-preview auth token          # the bearer, for scripting
 * compose-preview auth revoke
 * ```
 *
 * The shape of `request`'s output is the point of this whole feature, so it is worth being explicit
 * about who reads what. **The agent** reads the first block and relays it verbatim; **the human**
 * reads the link and the code. So the link and the code go to stdout, unadorned and on their own
 * lines, ahead of any progress chatter — an agent that pipes this into a chat message should be
 * able to hand over exactly what it received without editing.
 *
 * The token itself is never printed by `request`. It is written to [AgentAccessStore] and reported
 * only as "granted, expires in …". `auth token` exists for the case where a script genuinely needs
 * the string, and prints nothing else, so redirecting it to a file is unambiguous.
 */
internal class AuthCommand(private val args: List<String>) {

  private val json: Boolean = "--json" in args

  fun run() {
    when (val sub = subcommand()) {
      "request",
      "login",
      null -> request()
      "status" -> status()
      "token" -> token()
      "revoke",
      "logout" -> revoke()
      "forget" -> forget()
      else -> {
        System.err.println("compose-preview auth: unknown subcommand '$sub'")
        printUsage()
        exitProcess(1)
      }
    }
  }

  /**
   * The first positional argument, skipping any token that is a *value* of a preceding flag.
   *
   * A naive "first argument not starting with `-`" reads `auth --server https://x request` as a
   * request for a subcommand called `https://x`, which is a confusing way to be told that the order
   * of one's own arguments matters. [CliFlags.VALUE_FLAGS] already knows which flags consume the
   * next token, so use it.
   */
  private fun subcommand(): String? {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg in CliFlags.VALUE_FLAGS -> i += 2
        arg.startsWith("-") -> i++
        else -> return arg
      }
    }
    return null
  }

  // -------------------------------------------------------------- request

  private fun request() {
    val server = resolveServer()
    val client =
      try {
        AgentAccessClient(server)
      } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --server")
      }
    // Validated here rather than left to the server: an unknown name would otherwise be read as the
    // default, and the agent would spend a human's attention on a request for less access than it
    // meant to ask for.
    val scope = args.flagValue("--scope")?.trim().orEmpty()
    if (scope.isNotEmpty() && ServeAgentGrantScope.parse(scope) == null) {
      fail(
        "unknown --scope '$scope' — expected one of " +
          ServeAgentGrantScope.entries.joinToString(", ") { it.wire },
        code = 64,
      )
    }
    val ttlRaw = args.flagValue("--ttl")
    val ttl =
      ServeAgentGrants.parseDurationSeconds(ttlRaw)
        ?: if (ttlRaw.isNullOrBlank()) DEFAULT_TTL_SECONDS
        else fail("unrecognised --ttl '$ttlRaw' — try 45m, 2h, or a number of seconds", code = 64)
    val label = args.flagValue("--label")?.trim().orEmpty().ifEmpty { defaultLabel() }

    val opened =
      when (val r = client.open(label = label, scope = scope, ttlSeconds = ttl)) {
        is AgentAccessClient.Result.Ok -> r.value
        is AgentAccessClient.Result.Err -> fail(r.reason)
      }

    if (json) {
      // The device secret is deliberately included: `--json` exists for an agent that wants to
      // drive the poll itself, and without it the response is a link it can never redeem. It is
      // not printed in the human form for the same reason it is not in the link.
      println(
        JSON.encodeToString(
          RequestJson.serializer(),
          RequestJson(
            server = client.origin,
            approveUrl = opened.approveUrl,
            userCode = opened.userCode,
            requestId = opened.requestId,
            deviceSecret = opened.deviceSecret,
            expiresInSeconds = opened.expiresInSeconds,
            requestedScope = opened.requestedScope,
            requestedTtlSeconds = opened.requestedTtlSeconds,
          ),
        )
      )
      if ("--no-wait" in args) return
    } else {
      println()
      println("Ask a human with access to ${client.origin} to open this and approve:")
      println()
      println("  ${opened.approveUrl}")
      println("  verification code: ${opened.userCode}")
      println()
      println(
        "  They will be asked to grant: ${opened.requestedScope} · " +
          ServeAgentGrants.formatDuration(opened.requestedTtlSeconds) +
          (if (label.isNotEmpty()) " · \"$label\"" else "")
      )
      println("  The code above must match what they see on that page.")
      println()
      if ("--no-wait" in args) {
        println(
          "Not waiting. Re-run `compose-preview auth status --server ${client.origin}` after they " +
            "approve — or run this without --no-wait to block until they do."
        )
        return
      }
      println(
        "Waiting for approval (this request expires in " +
          "${ServeAgentGrants.formatDuration(opened.expiresInSeconds)})…"
      )
    }

    val outcome = awaitApproval(client, opened)
    val store = AgentAccessStore()
    val saved =
      store.save(
        AgentAccessStore.Entry(
          origin = client.origin,
          token = outcome.token.orEmpty(),
          scopes = outcome.scopes,
          approvedBy = outcome.approvedBy.orEmpty(),
          label = label,
          expiresAtMillis = System.currentTimeMillis() + (outcome.expiresInSeconds ?: 0) * 1000,
        )
      )
    if (json) {
      println(
        JSON.encodeToString(
          GrantedJson.serializer(),
          GrantedJson(
            server = client.origin,
            scopes = outcome.scopes,
            approvedBy = outcome.approvedBy.orEmpty(),
            expiresInSeconds = outcome.expiresInSeconds ?: 0,
            stored = saved,
          ),
        )
      )
      return
    }
    println()
    println(
      "Access granted by ${outcome.approvedBy ?: "an approver"} — " +
        "${outcome.scopes.joinToString(", ")} for " +
        ServeAgentGrants.formatDuration(outcome.expiresInSeconds ?: 0) +
        "."
    )
    println(
      if (saved)
        "Saved for ${client.origin}. Other compose-preview commands against this server will use " +
          "it automatically; `compose-preview auth token` prints it for anything else."
      else
        "WARNING: the grant could not be saved to disk (see the warning above). It is live on the " +
          "server, but this CLI will not remember it."
    )
  }

  /**
   * Poll until the human decides, the request expires, or the caller gives up.
   *
   * Every terminal outcome exits the process here rather than returning a sum type, because there
   * is exactly one caller and each case has a different exit code an agent should be able to branch
   * on: `0` granted, `1` refused/expired, `2` unreachable.
   */
  private fun awaitApproval(
    client: AgentAccessClient,
    opened: AgentAccessClient.OpenResponse,
  ): AgentAccessClient.PollResponse {
    val intervalMillis = opened.pollIntervalSeconds.coerceIn(1, 30) * 1000
    val deadline = System.currentTimeMillis() + (opened.expiresInSeconds + 30) * 1000
    var consecutiveErrors = 0
    while (System.currentTimeMillis() < deadline) {
      when (val r = client.poll(opened.requestId, opened.deviceSecret)) {
        is AgentAccessClient.Result.Ok -> {
          consecutiveErrors = 0
          val response = r.value
          when (response.status) {
            "approved" -> {
              if (response.token.isNullOrEmpty()) {
                fail("the server reported approval but sent no token", code = 2)
              }
              return response
            }
            "denied" ->
              fail(
                "the request was declined" +
                  (response.approvedBy?.let { " by $it" }.orEmpty()) +
                  ". Nothing was granted."
              )
            "expired" ->
              fail("the request expired before anyone approved it. Run `auth request` again.")
            "unknown" ->
              fail("the server no longer knows this request (it may have restarted).", code = 2)
            else -> Unit // pending — fall through and wait.
          }
        }
        is AgentAccessClient.Result.Err -> {
          // A transient network blip in the middle of a ten-minute wait should not throw away a
          // request a human may be about to approve, so retry a few times before giving up. The
          // count resets on any success, so it measures a run of failures rather than a total.
          consecutiveErrors++
          if (consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
            fail("gave up polling: ${r.reason}", code = 2)
          }
        }
      }
      Thread.sleep(intervalMillis)
    }
    fail("timed out waiting for approval. Run `auth request` again when someone is around.")
  }

  // --------------------------------------------------------------- status

  private fun status() {
    val store = AgentAccessStore()
    val explicit = namedServer()
    val entries = store.all().filter { explicit == null || it.origin == explicit }
    val now = System.currentTimeMillis()
    if (json) {
      println(
        JSON.encodeToString(
          StatusJson.serializer(),
          StatusJson(
            grants =
              entries.map {
                StatusEntryJson(
                  server = it.origin,
                  scopes = it.scopes,
                  approvedBy = it.approvedBy,
                  label = it.label,
                  expiresInSeconds = it.secondsUntilExpiry(now),
                )
              }
          ),
        )
      )
      return
    }
    if (entries.isEmpty()) {
      println(
        if (explicit == null) "No access grants. Run `compose-preview auth request --server <url>`."
        else "No access grant for $explicit. Run `compose-preview auth request --server $explicit`."
      )
      return
    }
    for (entry in entries) {
      println(
        "${entry.origin} — ${entry.scopes.joinToString(", ").ifEmpty { "preview" }} · " +
          "expires in ${ServeAgentGrants.formatDuration(entry.secondsUntilExpiry(now))}" +
          (if (entry.approvedBy.isNotEmpty()) " · approved by ${entry.approvedBy}" else "") +
          (if (entry.label.isNotEmpty()) " · \"${entry.label}\"" else "")
      )
    }
  }

  // ---------------------------------------------------------------- token

  /**
   * Print the bearer and nothing else, so `$(compose-preview auth token)` is usable. Exits 1 with a
   * message on stderr when there is none — a missing credential must not become an empty string
   * that a script then sends as a token.
   */
  private fun token() {
    val server = namedServer() ?: soleServer() ?: fail(NO_SERVER_MESSAGE)
    val entry =
      AgentAccessStore().entryFor(server)
        ?: fail(
          "no live access grant for $server. Run `compose-preview auth request --server $server`."
        )
    println(entry.token)
  }

  // --------------------------------------------------------------- revoke

  private fun revoke() {
    val server = namedServer() ?: soleServer() ?: fail(NO_SERVER_MESSAGE)
    val store = AgentAccessStore()
    val entry = store.entryFor(server)
    if (entry == null) {
      println("No access grant for $server — nothing to revoke.")
      return
    }
    val client =
      try {
        AgentAccessClient(server)
      } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid server")
      }
    // Forget locally whatever the server says. If the call failed we cannot know whether it landed,
    // and keeping a token the user has asked us to drop is the worse of the two mistakes; the grant
    // expires on its own regardless.
    val outcome = client.revoke(entry.token)
    store.forget(server)
    when (outcome) {
      is AgentAccessClient.Result.Ok ->
        println(
          if (outcome.value.revoked) "Revoked on $server and forgotten locally."
          else "The server had no live grant for this token; forgotten locally."
        )
      is AgentAccessClient.Result.Err ->
        println(
          "Forgotten locally, but the server could not be told (${outcome.reason}). The grant " +
            "expires on its own; revoke it from ${server}/status to end it now."
        )
    }
  }

  private fun forget() {
    val store = AgentAccessStore()
    val server = namedServer()
    if (server == null) {
      store.clear()
      println(
        "Forgot every stored access grant. They remain live on their servers until they expire."
      )
      return
    }
    println(
      if (store.forget(server)) "Forgot the grant for $server (it remains live until it expires)."
      else "No stored grant for $server."
    )
  }

  // --------------------------------------------------------------- shared

  /**
   * `--server`, else `$COMPOSE_PREVIEW_SERVER`, normalised to an origin. Exits when neither is
   * present — [namedServer] is the variant for the subcommands that can fall back to the sole
   * stored grant.
   */
  private fun resolveServer(): String = namedServer() ?: fail(NO_SERVER_MESSAGE)

  /** The server the caller named, or null when they named none. A malformed one is still fatal. */
  private fun namedServer(): String? {
    val raw =
      args.flagValue("--server")
        ?: System.getenv("COMPOSE_PREVIEW_SERVER")?.takeIf { it.isNotBlank() }
        ?: return null
    return AgentAccessStore.normalizeOrigin(raw)
      ?: fail("--server must be an absolute http(s) URL with no credentials in it: $raw")
  }

  /**
   * The one server we hold a grant for, when there is exactly one — so `auth token` needs no flag.
   */
  private fun soleServer(): String? = AgentAccessStore().all().singleOrNull()?.origin

  private fun defaultLabel(): String =
    System.getenv("COMPOSE_PREVIEW_AGENT_LABEL")?.takeIf { it.isNotBlank() }
      ?: "compose-preview CLI on ${runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "an agent host"}"

  private fun fail(message: String, code: Int = 1): Nothing {
    System.err.println("compose-preview auth: $message")
    exitProcess(code)
  }

  private fun printUsage() {
    System.err.println(
      """
      Usage: compose-preview auth <request|status|token|revoke|forget> [options]

        request   Ask a human to grant this agent temporary access to a preview server.
                  Prints a link and a verification code, then waits for approval.
          --server <url>     The preview server (or ${'$'}COMPOSE_PREVIEW_SERVER).
          --scope <name>     preview | live | playground. Cumulative; default preview.
          --ttl <duration>   How long to ask for, e.g. 45m / 2h (default 1h). The approver
                             chooses the actual lifetime, up to the server's ceiling.
          --label <text>     What the access is for; shown on the approval page.
          --no-wait          Print the link and exit instead of waiting.
          --json             Machine-readable, including the device secret so you can poll
                             the server yourself.

        status    List live grants (--server to filter, --json for machine form).
        token     Print the bearer token for a server and nothing else.
        revoke    End the grant now, on the server and locally.
        forget    Drop the local copy only (--server, or all of them).
      """
        .trimIndent()
    )
  }

  @Serializable
  private data class RequestJson(
    val server: String,
    val approveUrl: String,
    val userCode: String,
    val requestId: String,
    val deviceSecret: String,
    val expiresInSeconds: Long,
    val requestedScope: String,
    val requestedTtlSeconds: Long,
  )

  @Serializable
  private data class GrantedJson(
    val server: String,
    val scopes: List<String>,
    val approvedBy: String,
    val expiresInSeconds: Long,
    val stored: Boolean,
  )

  @Serializable private data class StatusJson(val grants: List<StatusEntryJson>)

  @Serializable
  private data class StatusEntryJson(
    val server: String,
    val scopes: List<String>,
    val approvedBy: String,
    val label: String,
    val expiresInSeconds: Long,
  )

  private companion object {
    const val DEFAULT_TTL_SECONDS = 60 * 60L

    /**
     * Consecutive poll failures tolerated before giving up. At the default three-second interval
     * that is half a minute of silence — long enough to ride out a proxy hiccup, short enough that
     * a server which has genuinely gone away doesn't hold the agent for the request's whole life.
     */
    const val MAX_CONSECUTIVE_POLL_ERRORS = 10

    const val NO_SERVER_MESSAGE =
      "which server? Pass --server https://… or set \$COMPOSE_PREVIEW_SERVER."

    val JSON = Json { prettyPrint = true }
  }
}
