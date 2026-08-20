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
internal class AuthCommand(
  private val args: List<String>,
  /**
   * Where grants and un-collected requests live. Injected so a test can drive the collect/verify
   * behaviour against a real server without reaching for the caller's actual credential file —
   * production always gets the default.
   */
  injectedStore: AgentAccessStore? = null,
  /**
   * How the default store is opened when none was injected. A seam, because the one behaviour worth
   * pinning here is what happens when this **throws** — `auth request --json` must still print the
   * device secret rather than exiting, and a test cannot make a real machine forget where its home
   * directory is.
   */
  private val openStore: () -> AgentAccessStore = { AgentAccessStore() },
) {

  /**
   * Opened lazily so a machine with nowhere safe to keep credentials fails with one clear sentence
   * instead of a stack trace out of a constructor default — and only when a subcommand actually
   * needs the store, so `auth --help` still works there.
   */
  private val store: AgentAccessStore by lazy {
    optionalStore ?: fail(storeFailure ?: "no user config directory could be determined")
  }

  /**
   * The store, or null when this machine has nowhere safe to keep credentials.
   *
   * Separate from [store] because **one path legitimately does not need it**: `auth request --json`
   * prints the device secret, which is the whole point of that mode — the caller polls for itself.
   * Failing there would open a request on the server and then exit before printing the secret that
   * could redeem it, leaving the human with an approval link that mints a credential nobody can
   * ever collect. Every other path needs somewhere to write and says so through [store].
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
    // Before anything else: `auth --help` and `auth request --help` used to fall through to
    // `request()`, so asking for usage on a machine with $COMPOSE_PREVIEW_SERVER set opened a real
    // server-side request and sat there waiting for a human to approve it.
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

    // Remembered before anything is printed, and whether or not this run intends to wait. The
    // device secret is the only thing that can redeem the approval, so a `--no-wait` that printed
    // "re-run auth status" without persisting it was telling the user to do something impossible —
    // and a wait interrupted by Ctrl-C would have thrown the request away just as completely.
    // `optionalStore` rather than `store`: with `--json` the device secret is printed, so a machine
    // with nowhere to write can still drive the flow. Handled below.
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

    // Nothing was persisted, so nothing later can redeem an approval — and the human-readable path
    // never prints the token, by design. Waiting would mean asking a person to approve access that
    // is guaranteed to be lost. `--json` is exempt: it prints the device secret, so its caller can
    // poll for itself and needs no store at all.
    if (remembered != true && !json) {
      fail(
        "opened the request, but could not save it locally (see the warning above) — so nothing " +
          "could collect the token once it was approved. Fix the credential file's directory and " +
          "run this again, or use --json, which prints the device secret for you to poll with. " +
          "The unsaved request expires on its own in " +
          ServeAgentGrants.formatDuration(opened.expiresInSeconds) +
          "; nobody has been asked to approve anything."
      )
    }

    if (json) {
      // The device secret is deliberately included: `--json` exists for an agent that wants to
      // drive the poll itself, and without it the response is a link it can never redeem. It is
      // not printed in the human form for the same reason it is not in the link.
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
      println(
        "  They will be asked to grant: ${opened.requestedScope} · " +
          ServeAgentGrants.formatDuration(opened.requestedTtlSeconds) +
          (if (label.isNotEmpty()) " · \"$label\"" else "")
      )
      println("  The code above must match what they see on that page.")
      println()
      if ("--no-wait" in args) {
        println(
          // `remembered` is guaranteed true here — an unsaved request aborts above, before anyone
          // is asked to approve anything.
          "Not waiting. Run `compose-preview auth status --server ${client.origin}` after they " +
            "approve and it will collect the token — or run this without --no-wait to block " +
            "until they do."
        )
        return
      }
      println(
        "Waiting for approval (this request expires in " +
          "${ServeAgentGrants.formatDuration(opened.expiresInSeconds)})…"
      )
    }

    val outcome = awaitApproval(client, opened)
    // Save first, drop the pending record only if that worked. The other order lost credentials on
    // a full disk: the device secret — the one thing that can re-poll for this token — was deleted,
    // and the human-readable path never prints the token itself, so a failed save stranded a live
    // grant with nothing left to redeem it.
    // The waiting path replaces this origin's entry too, and had no superseded handling at all —
    // the previous round only taught the `--no-wait` collector to do this.
    optionalStore?.let { handOverSuperseded(it, client, client.origin, outcome.token.orEmpty()) }
    val saved =
      optionalStore?.save(
        AgentAccessStore.Entry(
          origin = client.origin,
          token = outcome.token.orEmpty(),
          scopes = outcome.scopes,
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
          approvedBy = outcome.approvedBy.orEmpty(),
          expiresInSeconds = outcome.expiresInSeconds ?: 0,
          stored = saved == true,
        ),
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
      if (saved == true)
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
          // A server that answered with `Retry-After` is not failing — it is scheduling us. Honour
          // it, and don't spend an error on being told to wait: on a host whose
          // `--agent-grant-rate-limit` is below this cadence, counting throttles as failures burned
          // the whole budget before one token refilled.
          val backoff = r.retryAfterSeconds
          if (backoff != null) {
            Thread.sleep(backoff.coerceIn(1, 60) * 1000)
            continue
          }
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

  /**
   * What this machine holds for each server, **checked against that server** rather than reported
   * from local bookkeeping alone.
   *
   * Two things happen here, and both exist because the local file is a cache of someone else's
   * state. A remembered-but-uncollected request is polled, so the token an approval produced is
   * picked up by the first `status` after it (this is what makes `--no-wait` work). A stored grant
   * is verified with `whoami`, so a grant the operator revoked, or one that died with a server
   * restart, is reported as gone instead of being confidently listed as live until its local expiry
   * — which would send the next command into an unexplained 404.
   *
   * A server that cannot be reached yields `unverified` rather than a deletion: an unreachable host
   * is not evidence that the grant is dead, and throwing a live credential away on a flaky network
   * is the more expensive mistake.
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
      println(
        "${entry.origin} — ${entry.scopes.joinToString(", ").ifEmpty { "preview" }} · " +
          "expires in ${ServeAgentGrants.formatDuration(left)}" +
          (if (entry.approvedBy.isNotEmpty()) " · approved by ${entry.approvedBy}" else "") +
          (if (entry.label.isNotEmpty()) " · \"${entry.label}\"" else "") +
          suffix
      )
    }
    for (p in waiting) {
      if (p.windowClosed(now)) {
        // Kept and still polled: the server holds an approved-but-uncollected request until its
        // grant expires, so a decision made in the last seconds of the window still lands here.
        println(
          "${p.origin} — approval window closed; still checking whether it was approved in time"
        )
        continue
      }
      println(
        "${p.origin} — waiting for approval (${ServeAgentGrants.formatDuration(
          p.secondsUntilExpiry(now)
        )} left)"
      )
      println("  ${p.approveUrl}")
      println("  verification code: ${p.userCode}")
    }
  }

  /**
   * Hand back the grant that saving [replacement] is about to make unreachable.
   *
   * The store keeps one entry per origin, so a second approved grant for the same server evicts the
   * first — which stays **live on the server** with nothing left able to present or revoke it. So
   * it is revoked here, before it is dropped.
   *
   * The revoke result is *read*, not merely attempted: [AgentAccessClient.revoke] reports HTTP and
   * network failures as `Result.Err` rather than throwing, so wrapping it in `runCatching` (as the
   * first version of this did) treated every such failure as a success and orphaned the credential
   * anyway. When it genuinely cannot be handed back, say so and name its fingerprint, because at
   * that point the only thing that can still end it is a human on `/status`.
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
   * Poll every remembered request once and promote the approved ones into grants. Silent about a
   * request still pending — [status] prints those itself, with the link, so the human can still be
   * pointed at it.
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
          // Save first, drop the pending record only if that worked — the same order as the
          // waiting path, for the same reason: this record holds the only secret that can re-poll
          // for the token, and nothing here prints the token itself.
          handOverSuperseded(store, client, pending.origin, token)
          val stored =
            store.save(
              AgentAccessStore.Entry(
                origin = pending.origin,
                token = token,
                scopes = polled.scopes,
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
   * Print the bearer and nothing else, so `$(compose-preview auth token)` is usable. Exits 1 with a
   * message on stderr when there is none — a missing credential must not become an empty string
   * that a script then sends as a token.
   */
  private fun token() {
    // Collect first: the common shape is `auth request --no-wait`, a human approving, and then a
    // script reaching straight for the token. Making that work is the whole point of remembering
    // the request.
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
    // A remembered-but-uncollected request is also access this machine asked for; revoking should
    // leave nothing behind, including the thing that could still turn into a credential. Asked
    // before it is removed, because `forgetPending` returning false means *either* "there was none"
    // *or* "the file could not be rewritten" — and reporting the second as the first told the user
    // there was nothing to revoke while the device secret sat on disk, still collectable.
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
    // Forget locally whatever the server says. If the call failed we cannot know whether it landed,
    // and keeping a token the user has asked us to drop is the worse of the two mistakes; the grant
    // expires on its own regardless.
    val outcome = client.revoke(entry.token)
    // Reported separately from the server's answer, because they fail separately — and a bearer
    // that is still readable by the next process is not "forgotten" however the remote call went.
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
      // The store reports a failed rewrite; saying "forgotten" over the top of one would be the
      // same false claim `forget(origin)` was fixed for, one level up.
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
  private fun soleServer(): String? = store.all().singleOrNull()?.origin

  /**
   * The one server this machine has *any* access to, granted or merely asked for — so `auth revoke`
   * needs no flag either.
   *
   * Wider than [soleServer] on purpose, and only for revoke. That command already treats a pending
   * request as revocable access (its device secret can still become a credential), so resolving the
   * target from grants alone made the single most obvious case — one `--no-wait` request
   * outstanding, nothing else — fail with "which server?" and skip the cleanup it was asked for.
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
  )

  @Serializable
  private data class GrantedJson(
    val server: String,
    val scopes: List<String>,
    val approvedBy: String,
    val expiresInSeconds: Long,
    val stored: Boolean,
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

    /**
     * Consecutive poll failures tolerated before giving up. At the default three-second interval
     * that is half a minute of silence — long enough to ride out a proxy hiccup, short enough that
     * a server which has genuinely gone away doesn't hold the agent for the request's whole life.
     */
    const val MAX_CONSECUTIVE_POLL_ERRORS = 10

    const val NO_SERVER_MESSAGE =
      "which server? Pass --server https://… or set \$COMPOSE_PREVIEW_SERVER."

    /**
     * Compact, one document per line. `--json` on a *waiting* `auth request` emits two documents —
     * the request (so the agent can relay the link now) and the grant (once it lands) — and two
     * pretty-printed objects concatenated are not parseable as anything. One object per line is
     * JSON Lines, which every consumer already knows how to read incrementally.
     */
    val JSON = Json { prettyPrint = false }
  }
}
