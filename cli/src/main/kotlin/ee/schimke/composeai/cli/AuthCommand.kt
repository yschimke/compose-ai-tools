package ee.schimke.composeai.cli

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
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
 * `request` prints the approval link and code first, unadorned on their own lines, so an agent can
 * relay them verbatim to the human. It never prints the token: that goes to [AgentAccessStore], and
 * `auth token` prints only the bearer for scripts.
 */
internal class AuthCommand(
  private val args: List<String>,
  /** Where grants and un-collected requests live; injectable for tests. */
  injectedStore: AgentAccessStore? = null,
  /**
   * How the default store is opened; a seam so tests can make it throw (`auth request --json` must
   * still print the device secret).
   */
  private val openStore: () -> AgentAccessStore = { AgentAccessStore() },
) {

  /**
   * Opened lazily so a machine with nowhere safe for credentials fails with one clear sentence, and
   * only when a subcommand needs it (`auth --help` still works).
   */
  private val store: AgentAccessStore by lazy {
    optionalStore ?: fail(storeFailure ?: "no user config directory could be determined")
  }

  /**
   * The store, or null when there is nowhere safe to keep credentials. Only `auth request --json`
   * may proceed without one: it prints the device secret for the caller to poll with, and failing
   * after opening the request would leave an approval nobody can collect.
   */
  private val optionalStore: AgentAccessStore? by lazy {
    injectedStore
      ?: try {
        openStore()
      } catch (e: NoCredentialHomeException) {
        storeFailure = e.message
        null
      }
  }

  private var storeFailure: String? = null

  private val json: Boolean = "--json" in args

  fun run() {
    // First, so `--help` never opens a real server-side request.
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }
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
   * The first positional argument, skipping values of preceding flags ([CliFlags.VALUE_FLAGS]), so
   * `auth --server https://x request` works.
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
    // Validate locally: the server would treat an unknown name as the default, and a human would
    // approve less access than intended.
    val scope = args.flagValue("--scope")?.trim().orEmpty()
    if (scope.isNotEmpty() && AgentGrantScope.parse(scope) == null) {
      fail(
        "unknown --scope '$scope' — expected one of " +
          AgentGrantScope.entries.joinToString(", ") { it.wire },
        code = 64,
      )
    }
    // Same for capabilities, which the server would silently drop.
    val capabilities =
      args
        .flagValuesAll("--capability")
        .flatMap { it.split(',', ' ') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { raw ->
          AgentGrantCapability.parse(raw)?.wire
            ?: fail(
              "unknown --capability '$raw' — expected one of " +
                AgentGrantCapability.entries.joinToString(", ") { it.wire },
              code = 64,
            )
        }
        .distinct()
    val ttlRaw = args.flagValue("--ttl")
    val ttl =
      AgentGrantProtocol.parseDurationSeconds(ttlRaw)
        ?: if (ttlRaw.isNullOrBlank()) DEFAULT_TTL_SECONDS
        else fail("unrecognised --ttl '$ttlRaw' — try 45m, 2h, or a number of seconds", code = 64)
    val label = args.flagValue("--label")?.trim().orEmpty().ifEmpty { defaultLabel() }

    // Make sure the result can be kept before opening a request, or an uncollectable request
    // occupies a slot in the server's bounded pending map. `--json` is exempt (it prints the device
    // secret).
    if (!json && optionalStore == null) {
      fail(
        storeFailure
          ?: "no user config directory could be determined, and credentials will not be written " +
            "to the working directory."
      )
    }

    val opened =
      when (
        val r =
          client.open(label = label, scope = scope, ttlSeconds = ttl, capabilities = capabilities)
      ) {
        is AgentAccessClient.Result.Ok -> r.value
        is AgentAccessClient.Result.Err -> fail(r.reason)
      }

    // Persist the device secret before printing anything, whether or not this run waits: it is the
    // only way to redeem the approval later (`--no-wait`, or an interrupted wait).
    val remembered =
      optionalStore?.savePending(
        AgentAccessStore.Pending(
          origin = client.origin,
          requestId = opened.requestId,
          deviceSecret = opened.deviceSecret,
          userCode = opened.userCode,
          approveUrl = opened.approveUrl,
          label = label,
          expiresAtMillis = System.currentTimeMillis() + opened.expiresInSeconds * 1000,
        )
      )

    // The store exists but the write failed: don't ask a human to approve access that would be
    // lost.
    if (remembered != true && !json) {
      fail(
        "opened the request, but could not save it locally (see the warning above) — so nothing " +
          "could collect the token once it was approved. Fix the credential file's directory and " +
          "run this again, or use --json, which prints the device secret for you to poll with. " +
          "The unsaved request expires on its own in " +
          AgentGrantProtocol.formatDuration(opened.expiresInSeconds) +
          "; nobody has been asked to approve anything."
      )
    }

    if (json) {
      // Includes the device secret so a `--json` caller can poll itself; never in the
      // human-readable form.
      printJson(
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
          requestedCapabilities = opened.requestedCapabilities,
          maxCapabilities = opened.maxCapabilities,
        ),
      )
      if ("--no-wait" in args) return
    } else {
      println()
      println("Ask a human with access to ${client.origin} to open this and approve:")
      println()
      println("  ${opened.approveUrl}")
      println("  verification code: ${opened.userCode}")
      println()
      // Print the requested capabilities in the relayed line too, so the human sees the full
      // consent being sought; the note below names any the server's ceiling won't offer.
      val requestedCapabilities =
        if (opened.requestedCapabilities.isEmpty()) ""
        else " + ${opened.requestedCapabilities.joinToString(", ")}"
      println(
        "  They will be asked to grant: ${opened.requestedScope}$requestedCapabilities · " +
          AgentGrantProtocol.formatDuration(opened.requestedTtlSeconds) +
          (if (label.isNotEmpty()) " · \"$label\"" else "")
      )
      println("  The code above must match what they see on that page.")
      // Capabilities above the server's ceiling can never be granted; say so now rather than at the
      // first refused call.
      val notOffered = opened.requestedCapabilities.filterNot { it in opened.maxCapabilities }
      if (notOffered.isNotEmpty()) {
        println(
          "  Note: this server will not offer ${notOffered.joinToString(", ")} — its " +
            "--agent-grant-capabilities does not include them, so the grant cannot carry them."
        )
      }
      println()
      if ("--no-wait" in args) {
        println(
          // `remembered` is true here: an unsaved request aborts earlier.
          "Not waiting. Run `compose-preview auth status --server ${client.origin}` after they " +
            "approve and it will collect the token — or run this without --no-wait to block " +
            "until they do."
        )
        return
      }
      println(
        "Waiting for approval (this request expires in " +
          "${AgentGrantProtocol.formatDuration(opened.expiresInSeconds)})…"
      )
    }

    val outcome = awaitApproval(client, opened)
    // Save first, drop the pending record only on success, or a failed save strands a live grant
    // with nothing left to redeem it. Also hand over any grant this one supersedes.
    optionalStore?.let { handOverSuperseded(it, client, client.origin, outcome.token.orEmpty()) }
    val saved =
      optionalStore?.save(
        AgentAccessStore.Entry(
          origin = client.origin,
          token = outcome.token.orEmpty(),
          scopes = outcome.scopes,
          capabilities = outcome.capabilities,
          approvedBy = outcome.approvedBy.orEmpty(),
          label = label,
          expiresAtMillis = System.currentTimeMillis() + (outcome.expiresInSeconds ?: 0) * 1000,
        )
      )
    if (saved == true) optionalStore?.forgetPendingRequest(opened.requestId)
    if (json) {
      printJson(
        GrantedJson.serializer(),
        GrantedJson(
          server = client.origin,
          scopes = outcome.scopes,
          capabilities = outcome.capabilities,
          approvedBy = outcome.approvedBy.orEmpty(),
          expiresInSeconds = outcome.expiresInSeconds ?: 0,
          stored = saved == true,
          // See [GrantedJson.token]: handed back only when nothing else can retrieve it.
          token = if (saved == true) null else outcome.token,
        ),
      )
      return
    }
    println()
    println(
      "Access granted by ${outcome.approvedBy ?: "an approver"} — " +
        "${outcome.scopes.joinToString(", ")} for " +
        AgentGrantProtocol.formatDuration(outcome.expiresInSeconds ?: 0) +
        "."
    )
    println(
      if (saved == true)
        "Saved for ${client.origin}. Other compose-preview commands against this server will use " +
          "it automatically; `compose-preview auth token` prints it for anything else."
      else
        "WARNING: the grant could not be saved to disk (see the warning above). It is live on the " +
          "server, but this CLI will not remember it."
    )
  }

  /**
   * Poll until the human decides, the request expires, or the caller gives up. Exits directly with
   * a distinct code per outcome: `0` granted, `1` refused/expired, `2` unreachable.
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
          // `Retry-After` is scheduling, not failure: honour it without counting an error.
          val backoff = r.retryAfterSeconds
          if (backoff != null) {
            Thread.sleep(backoff.coerceIn(1, 60) * 1000)
            continue
          }
          // Tolerate a run of transient failures during a long wait; the count resets on success.
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

  /**
   * What this machine holds per server, checked against that server: pending requests are polled
   * (so `--no-wait` approvals get collected) and grants are verified with `whoami` (so revoked or
   * restart-lost grants show as gone). Unreachable servers report `unverified`, never a deletion.
   */
  private fun status() {
    val explicit = namedServer()
    collectPending(store, explicit)
    val now = System.currentTimeMillis()
    val rows =
      store
        .all()
        .filter { explicit == null || it.origin == explicit }
        .map { entry ->
          val state =
            when (val verdict = verify(entry)) {
              null -> "unverified"
              true -> "live"
              false -> "gone"
            }
          if (state == "gone") store.forget(entry.origin)
          Triple(entry, state, entry.secondsUntilExpiry(now))
        }
    val waiting = store.allPending().filter { explicit == null || it.origin == explicit }

    if (json) {
      printJson(
        StatusJson.serializer(),
        StatusJson(
          grants =
            rows.map { (entry, state, left) ->
              StatusEntryJson(
                server = entry.origin,
                scopes = entry.scopes,
                capabilities = entry.capabilities,
                approvedBy = entry.approvedBy,
                label = entry.label,
                expiresInSeconds = left,
                state = state,
              )
            },
          pending =
            waiting.map {
              PendingJson(
                server = it.origin,
                approveUrl = it.approveUrl,
                userCode = it.userCode,
                expiresInSeconds = it.secondsUntilExpiry(now),
              )
            },
        ),
      )
      return
    }

    if (rows.isEmpty() && waiting.isEmpty()) {
      println(
        if (explicit == null) "No access grants. Run `compose-preview auth request --server <url>`."
        else "No access grant for $explicit. Run `compose-preview auth request --server $explicit`."
      )
      return
    }
    for ((entry, state, left) in rows) {
      val suffix =
        when (state) {
          "gone" -> " · REVOKED on the server (forgotten locally)"
          "unverified" -> " · could not reach the server to confirm"
          else -> ""
        }
      val capabilities =
        if (entry.capabilities.isEmpty()) "" else " + ${entry.capabilities.joinToString(", ")}"
      println(
        "${entry.origin} — ${entry.scopes.joinToString(", ").ifEmpty { "preview" }}$capabilities · " +
          "expires in ${AgentGrantProtocol.formatDuration(left)}" +
          (if (entry.approvedBy.isNotEmpty()) " · approved by ${entry.approvedBy}" else "") +
          (if (entry.label.isNotEmpty()) " · \"${entry.label}\"" else "") +
          suffix
      )
    }
    for (p in waiting) {
      if (p.windowClosed(now)) {
        // Still polled: the server keeps an approved-but-uncollected request until its grant
        // expires.
        println(
          "${p.origin} — approval window closed; still checking whether it was approved in time"
        )
        continue
      }
      println(
        "${p.origin} — waiting for approval (${AgentGrantProtocol.formatDuration(
          p.secondsUntilExpiry(now)
        )} left)"
      )
      println("  ${p.approveUrl}")
      println("  verification code: ${p.userCode}")
    }
  }

  /**
   * Revoke the grant that saving [replacement] would evict (one entry per origin), since it would
   * otherwise stay live on the server with nothing able to present or revoke it.
   * [AgentAccessClient.revoke] reports failures as `Result.Err`, so the result is checked; if it
   * can't be handed back, name its fingerprint so a human can end it on `/status`.
   */
  private fun handOverSuperseded(
    store: AgentAccessStore,
    client: AgentAccessClient,
    origin: String,
    replacement: String,
  ) {
    val superseded =
      store.entryFor(origin)?.takeIf { it.token != replacement && it.token.isNotEmpty() } ?: return
    when (val result = client.revoke(superseded.token)) {
      is AgentAccessClient.Result.Ok -> Unit
      is AgentAccessClient.Result.Err ->
        System.err.println(
          "WARNING: replaced an older grant for $origin but could NOT revoke it " +
            "(${result.reason}). It stays live on that server until it expires — revoke " +
            "${superseded.fingerprint} from $origin/status if you want it gone now."
        )
    }
  }

  /**
   * Poll every remembered request once and promote approved ones into grants. Silent about pending
   * ones; [status] prints those with the link.
   */
  private fun collectPending(store: AgentAccessStore, only: String?) {
    for (pending in store.allPending()) {
      if (only != null && pending.origin != only) continue
      val client = runCatching { AgentAccessClient(pending.origin) }.getOrNull() ?: continue
      val polled =
        when (val r = client.poll(pending.requestId, pending.deviceSecret)) {
          is AgentAccessClient.Result.Ok -> r.value
          // Unreachable: keep the request, it may still be approvable when the network is back.
          is AgentAccessClient.Result.Err -> continue
        }
      when (polled.status) {
        "approved" -> {
          val token = polled.token
          if (token.isNullOrEmpty()) continue
          // Save first, drop the pending record only on success, as in the waiting path.
          handOverSuperseded(store, client, pending.origin, token)
          val stored =
            store.save(
              AgentAccessStore.Entry(
                origin = pending.origin,
                token = token,
                scopes = polled.scopes,
                capabilities = polled.capabilities,
                approvedBy = polled.approvedBy.orEmpty(),
                label = pending.label,
                expiresAtMillis =
                  System.currentTimeMillis() + (polled.expiresInSeconds ?: 0) * 1000,
              )
            )
          if (stored) store.forgetPendingRequest(pending.requestId)
        }
        // Terminal and not coming back — stop carrying it.
        "denied",
        "expired",
        "unknown" -> store.forgetPendingRequest(pending.requestId)
        else -> Unit // still pending
      }
    }
  }

  /** True/false from the server, or null when it could not be asked. */
  private fun verify(entry: AgentAccessStore.Entry): Boolean? {
    val client = runCatching { AgentAccessClient(entry.origin) }.getOrNull() ?: return null
    return when (val r = client.whoami(entry.token)) {
      is AgentAccessClient.Result.Ok -> r.value.active
      is AgentAccessClient.Result.Err -> null
    }
  }

  // ---------------------------------------------------------------- token

  /**
   * Print the bearer and nothing else, for `$(compose-preview auth token)`. Exits 1 with a stderr
   * message when there is none, so a script never sends an empty token.
   */
  private fun token() {
    // Collect first: `request --no-wait`, approval, then `auth token` is the common flow.
    collectPending(store, namedServer())
    val server = namedServer() ?: soleServer() ?: fail(NO_SERVER_MESSAGE)
    val entry =
      store.entryFor(server)
        ?: fail(
          "no live access grant for $server. Run `compose-preview auth request --server $server`."
        )
    println(entry.token)
  }

  // --------------------------------------------------------------- revoke

  private fun revoke() {
    val server = namedServer() ?: soleRevocableServer() ?: fail(NO_SERVER_MESSAGE)
    // A pending request is access too, so revoke removes it. Checked before removal because
    // `forgetPending` returning false can also mean the file couldn't be rewritten.
    val hadPending = store.pendingFor(server) != null
    val droppedPending = store.forgetPending(server)
    if (hadPending && !droppedPending) {
      fail(
        "could not rewrite the credential file (see the warning above) — $server's pending access " +
          "request is still on disk and a later `auth status` could still collect it."
      )
    }
    val entry = store.entryFor(server)
    if (entry == null) {
      println(
        if (hadPending) "Dropped the pending request for $server; there was no grant to revoke."
        else "No access grant for $server — nothing to revoke."
      )
      return
    }
    val client =
      try {
        AgentAccessClient(server)
      } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid server")
      }
    // Forget locally whatever the server says; the grant expires on its own anyway.
    val outcome = client.revoke(entry.token)
    // Reported separately: the local forget can fail independently of the remote revoke.
    val dropped = store.forget(server)
    val locally =
      if (dropped) "forgotten locally"
      else
        "NOT forgotten locally — the credential file could not be rewritten (see the warning " +
          "above), so it is still on disk"
    when (outcome) {
      is AgentAccessClient.Result.Ok ->
        println(
          if (outcome.value.revoked) "Revoked on $server and $locally."
          else "The server had no live grant for this token; $locally."
        )
      is AgentAccessClient.Result.Err ->
        println(
          "The server could not be told (${outcome.reason}) and the grant is $locally. It expires " +
            "on its own; revoke it from ${server}/status to end it now."
        )
    }
    if (!dropped) exitProcess(1)
  }

  private fun forget() {
    val server = namedServer()
    if (server == null) {
      // Don't claim "forgotten" over a failed rewrite.
      if (store.clear()) {
        println(
          "Forgot every stored access grant. They remain live on their servers until they expire."
        )
      } else {
        fail(
          "could not rewrite the credential file (see the warning above) — the stored grants are " +
            "still on disk. Revoke them from each server's /status if you need them ended now."
        )
      }
      return
    }
    val hadPendingHere = store.pendingFor(server) != null
    val droppedPending = if (hadPendingHere) store.forgetPending(server) else false
    val hadGrant = store.entryFor(server) != null
    val droppedGrant = store.forget(server)
    println(
      when {
        droppedGrant || droppedPending ->
          "Forgot what this machine held for $server (anything live there remains so until it " +
            "expires or you revoke it)."
        hadGrant || hadPendingHere ->
          fail(
            "could not rewrite the credential file (see the warning above) — $server's " +
              "credentials are still on disk."
          )
        else -> "Nothing stored for $server."
      }
    )
  }

  // --------------------------------------------------------------- shared

  /**
   * `--server`, else `$COMPOSE_PREVIEW_SERVER`, as an origin; exits when neither is set.
   * [namedServer] is the variant that can fall back to the sole stored grant.
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

  /** The one server we hold a grant for, if exactly one, so `auth token` needs no flag. */
  private fun soleServer(): String? = store.all().singleOrNull()?.origin

  /**
   * The one server with any access, granted or pending, so `auth revoke` needs no flag. Wider than
   * [soleServer] because revoke also cleans up pending requests.
   */
  private fun soleRevocableServer(): String? =
    (store.all().map { it.origin } + store.allPending().map { it.origin }).distinct().singleOrNull()

  private fun defaultLabel(): String =
    System.getenv("COMPOSE_PREVIEW_AGENT_LABEL")?.takeIf { it.isNotBlank() }
      ?: "compose-preview CLI on ${runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "an agent host"}"

  /** One compact JSON document, on its own line. See [JSON]. */
  private fun <T> printJson(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T) {
    println(JSON.encodeToString(serializer, value))
  }

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
          --capability <name> An extra, non-cumulative permission to ask for, repeatable or
                             comma-separated: currently `images` (upload rendered previews,
                             on a server that offers it). The approver ticks it separately.
          --ttl <duration>   How long to ask for, e.g. 45m / 2h (default 1h). The approver
                             chooses the actual lifetime, up to the server's ceiling.
          --label <text>     What the access is for; shown on the approval page.
          --no-wait          Print the link and exit instead of waiting. The request is
                             remembered, so a later `auth status` (or `auth token`)
                             collects the token once the human approves.
          --json             Machine-readable JSON Lines — one compact document per line:
                             the request (including the device secret, so you can poll the
                             server yourself) and then, unless --no-wait, the grant.

        status    List grants, each checked against its server, and collect any request a
                  --no-wait run left waiting (--server to filter, --json for machine form).
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
    /**
     * What survived the server's clamp, and what it would grant at all — the only way a `--no-wait`
     * caller learns a capability was dropped before a human approves.
     */
    val requestedCapabilities: List<String> = emptyList(),
    val maxCapabilities: List<String> = emptyList(),
  )

  @Serializable
  private data class GrantedJson(
    val server: String,
    val scopes: List<String>,
    /**
     * The permissions actually granted, so automation can tell a granted capability from a declined
     * one.
     */
    val capabilities: List<String> = emptyList(),
    val approvedBy: String,
    val expiresInSeconds: Long,
    val stored: Boolean,
    /**
     * The bearer, present only when it could not be stored; otherwise withheld to keep it out of
     * logs.
     */
    val token: String? = null,
  )

  @Serializable
  private data class StatusJson(
    val grants: List<StatusEntryJson>,
    /** Requests opened but not yet approved — the `--no-wait` half. */
    val pending: List<PendingJson> = emptyList(),
  )

  @Serializable
  private data class StatusEntryJson(
    val server: String,
    val scopes: List<String>,
    val capabilities: List<String> = emptyList(),
    val approvedBy: String,
    val label: String,
    val expiresInSeconds: Long,
    /** `live` / `gone` / `unverified` — what the server said, not what the local file assumed. */
    val state: String = "unverified",
  )

  @Serializable
  private data class PendingJson(
    val server: String,
    val approveUrl: String,
    val userCode: String,
    val expiresInSeconds: Long,
  )

  private companion object {
    const val DEFAULT_TTL_SECONDS = 60 * 60L

    /** Consecutive poll failures tolerated (~30s at the default interval) before giving up. */
    const val MAX_CONSECUTIVE_POLL_ERRORS = 10

    const val NO_SERVER_MESSAGE =
      "which server? Pass --server https://… or set \$COMPOSE_PREVIEW_SERVER."

    /**
     * One document per line (JSON Lines): a waiting `auth request --json` emits the request and
     * then the grant.
     */
    val JSON = Json { prettyPrint = false }
  }
}
