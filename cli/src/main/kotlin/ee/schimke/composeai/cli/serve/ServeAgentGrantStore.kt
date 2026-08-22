package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The state behind `/agent-access/…` — pending grant *requests* and the live *grants* they turn
 * into. See
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * Sibling of [PlaygroundTokenStore] in shape (unguessable ids, TTLs, a bounded map that evicts
 * nearest-expiry first) and deliberately unlike it in one respect: **two secrets, not one**.
 *
 * A [Request] has a public [Request.id] — the thing in the link a human is asked to open — and a
 * private [Request.deviceSecret] that only the agent that created it ever holds. The minted token
 * is handed to whoever presents the *secret*, never to whoever opens the *link*. That is what makes
 * the link safe to paste into a chat window: it is a handle, not a credential, and an attacker who
 * intercepts it can at most cause the token to be delivered to the agent that legitimately asked
 * for it.
 *
 * Nothing here is persisted. A restart drops every request and every grant, which is the right
 * trade for a credential whose whole selling point is that it is short-lived and revocable: the
 * TTLs are hours at most, and "survives a redeploy" is a property this must not have.
 */
class ServeAgentGrantStore(
  /** How long an unopened link stays approvable. Short — a request is a live conversation. */
  val requestTtlSeconds: Long = DEFAULT_REQUEST_TTL_SECONDS,
  /** The longest grant this box will mint, whatever the agent asked for or the approver chose. */
  val maxGrantTtlSeconds: Long = DEFAULT_MAX_GRANT_TTL_SECONDS,
  /** The most privileged scope any grant here may carry — the operator's ceiling. */
  val maxScope: ServeAgentGrantScope = ServeAgentGrantScope.DEFAULT_MAX,
  /**
   * The capabilities ([ServeAgentGrantCapability]) any grant here may carry — the operator's other
   * ceiling, and empty by default. Separate from [maxScope] because capabilities are not rungs: a
   * box that grants `live` has said nothing about whether an agent may also publish an image on its
   * origin, and must not be read as having said yes.
   */
  val maxCapabilities: Set<ServeAgentGrantCapability> = emptySet(),
  private val maxActiveGrants: Int = DEFAULT_MAX_ACTIVE_GRANTS,
  private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ::randomId,
  private val mintSecret: () -> String = ::randomSecret,
  private val mintUserCode: () -> String = ::randomUserCode,
  private val mintToken: () -> String = ::randomToken,
  /**
   * Audit sink, called once per mint and once per revoke with a line safe to print — it names the
   * approver, the label, the scope and the fingerprint, never the token. Defaults to a no-op so
   * tests stay quiet; `serve` wires it to the console.
   */
  private val audit: (String) -> Unit = {},
) {

  /** A request waiting for a human, or already resolved by one. */
  data class Request(
    /** Public handle. Appears in the approval link and nowhere sensitive. */
    val id: String,
    /**
     * The agent's half of the flow. Presented on `/agent-access/poll` to collect the token, and
     * compared constant-time. Never rendered, never logged, never in a URL.
     */
    val deviceSecret: String,
    /**
     * The short code the agent prints and the approval page displays, so a human can see that the
     * page in front of them belongs to the request their terminal just made. Anti-phishing, exactly
     * as in RFC 8628 §3.3 — see the design doc.
     */
    val userCode: String,
    /**
     * What the agent said it was for. Free text from the agent, so never trusted, always escaped.
     */
    val label: String,
    /** Where the request came from, for the approval page's "who is asking". Also untrusted. */
    val client: String,
    /** The most privileged scope the agent asked for; the approver may grant this or less. */
    val requestedScope: ServeAgentGrantScope,
    /** Seconds of access the agent asked for; the approver may grant this or less. */
    val requestedTtlSeconds: Long,
    /**
     * The capabilities the agent asked for, already narrowed to this box's ceiling. The approver
     * may grant these or fewer — never more, for the same reason the scope can only be narrowed.
     */
    val requestedCapabilities: Set<ServeAgentGrantCapability> = emptySet(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    @Volatile var state: State = State.PENDING,
    /** Set exactly once, when [State.APPROVED] — the token the poller collects. */
    @Volatile var grantId: String? = null,
    /** Who resolved it, for the poll response and the audit line. */
    @Volatile var resolvedBy: String? = null,
    /**
     * True once the agent has actually fetched its token ([poll] answering [Poll.Approved]).
     *
     * The distinction matters only in one place, and it is a place that used to lose credentials:
     * overflow. `APPROVED` is not the same as "the agent has it" — approval happens in a browser
     * and the poll arrives up to an interval later — so shedding every non-pending entry to make
     * room let an anonymous caller fill the map, wait for a human to approve, and open one more
     * request inside that window, stranding a live token its owner could never collect.
     *
     * Deliberately **not** a deletion trigger. It records that a poll *built* a response, which is
     * not the same as the agent receiving one; a response lost in flight has to be retriable, so
     * expiry is governed by the grant's life ([purge]) and this only ranks eviction candidates.
     */
    @Volatile var collected: Boolean = false,
  ) {
    enum class State {
      PENDING,
      APPROVED,
      DENIED,
    }

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean = other is Request && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  /** A live grant: a bearer token, what it may do, until when, and who said so. */
  data class Grant(
    /** Stable handle for revocation from `/status`. Not a secret. */
    val id: String,
    /** The bearer. Returned exactly once (the poll response) and never rendered again. */
    val token: String,
    val scope: ServeAgentGrantScope,
    /** The independent permissions this grant carries beside its rung. Usually empty. */
    val capabilities: Set<ServeAgentGrantCapability> = emptySet(),
    /** The label from the request, carried through so `/status` says what this is for. */
    val label: String,
    /** GitHub login, or `operator (token)` — see [ServeAgentGrants]. */
    val approvedBy: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    /** Every scope this grant confers, least-privileged first — the poll response's `scopes`. */
    val scopes: List<ServeAgentGrantScope>
      get() = ServeAgentGrantScope.upTo(scope)

    /** True when this grant is at or above [wanted]. The one question every gate asks. */
    fun allows(wanted: ServeAgentGrantScope): Boolean = scope.implies(wanted)

    /**
     * True when this grant carries [wanted]. Deliberately **not** implied by any scope: the rung
     * says how much of the machine the agent may spend, and a capability is a separate yes.
     */
    fun allows(wanted: ServeAgentGrantCapability): Boolean = wanted in capabilities

    /**
     * A short, stable, non-reversible handle on the token, for `/status` and the audit log. SHA-256
     * truncated to 12 hex characters: enough to tell two live grants apart and to match a log line
     * to a row, and useless to anyone who reads it.
     */
    val fingerprint: String by lazy { fingerprintOf(token) }

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean = other is Grant && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  private val requests = ConcurrentHashMap<String, Request>()
  private val grants = ConcurrentHashMap<String, Grant>()

  /**
   * Token → grant id. Kept beside [grants] so the hot path — every gated request on the box, once
   * per call — is one hash lookup on the presented string rather than a scan.
   */
  private val byToken = ConcurrentHashMap<String, String>()

  // ---------------------------------------------------------------- requests

  /**
   * Open a request. Returns null when the box is already tracking [maxPendingRequests] **pending**
   * ones and none can be purged — a refusal, rather than growing a map an anonymous caller
   * controls.
   */
  fun openRequest(
    label: String,
    client: String,
    requestedScope: ServeAgentGrantScope,
    requestedTtlSeconds: Long,
    requestedCapabilities: Set<ServeAgentGrantCapability> = emptySet(),
  ): Request? =
    synchronized(this) {
      openRequestLocked(label, client, requestedScope, requestedTtlSeconds, requestedCapabilities)
    }

  /**
   * The cap has to be checked and the entry inserted under **one** lock.
   *
   * Unsynchronised, the size check and the insert are a check-then-act on a map an *anonymous*
   * caller drives: a burst from several addresses all read `size < max` and then all insert, so the
   * bound this route relies on for its memory is whatever concurrency the attacker can muster. The
   * same lock already guards [approve] and [deny], and nothing here blocks — it is a map write and
   * a comparison.
   */
  private fun openRequestLocked(
    label: String,
    client: String,
    requestedScope: ServeAgentGrantScope,
    requestedTtlSeconds: Long,
    requestedCapabilities: Set<ServeAgentGrantCapability>,
  ): Request? {
    val now = clock()
    purge(now)
    // The cap counts what an anonymous caller can CREATE: rows that are pending, plus rows they got
    // denied. What it does not count is a retained approval, which exists only while its grant does
    // and is therefore already bounded by [maxActiveGrants].
    //
    // Getting this split wrong has now failed in both directions, which is why it is spelled out.
    // Counting every row against one number made the two caps fight: retention (correctly) stopped
    // shedding live approvals, so an operator who set `--agent-grant-max-active` above this cap
    // could never reach it. Counting only *pending* rows then leaked the other way — a denial sits
    // in the map until its own deadline, so an attacker could submit a batch, have an operator deny
    // it, and submit another, growing an anonymously-controlled map without bound while the pending
    // count stayed comfortably under the cap.
    //
    // A denial has to be kept (the agent must be able to learn it was denied rather than being told
    // `unknown`), so it is kept AND charged. That is the honest accounting: the attacker caused the
    // row, the operator's decision does not hand them a free one, and the row still expires on its
    // own deadline. Total map size is bounded by maxPendingRequests + maxActiveGrants.
    //
    // Rows nobody is owed anything for — an approval whose grant has expired or been revoked — are
    // reclaimed FIRST, unconditionally, so they can never be what pushes a legitimate caller over.
    requests.entries.removeIf { (_, r) -> r.state == Request.State.APPROVED && grantIsGone(r, now) }
    val charged =
      requests.values.count {
        it.state == Request.State.PENDING || it.state == Request.State.DENIED
      }
    if (charged >= maxPendingRequests) return null
    val request =
      Request(
        id = mintId(),
        deviceSecret = mintSecret(),
        userCode = mintUserCode(),
        label = sanitizeLabel(label),
        client = sanitizeLabel(client),
        requestedScope = minOf(requestedScope, maxScope),
        requestedTtlSeconds = requestedTtlSeconds.coerceIn(1, maxGrantTtlSeconds),
        // Narrowed at the door, like the scope: the approval page must never offer a capability
        // this box would refuse to mint, and an agent asking for one it cannot have should learn
        // that from the response it already reads rather than from a silent omission later.
        requestedCapabilities = requestedCapabilities intersect maxCapabilities,
        createdAtMillis = now,
        expiresAtMillis = now + requestTtlSeconds * 1000,
      )
    requests[request.id] = request
    return request
  }

  /** The live request for [id], or null when unknown, expired, or malformed. */
  fun request(id: String?): Request? {
    val key = id?.takeIf { isWellFormedId(it) } ?: return null
    val now = clock()
    purge(now)
    // An approved-but-uncollected request stays reachable past its own deadline so its owner can
    // still fetch the token; [purge] is what decides when that ends. A *pending* one still dies on
    // the dot — nobody should be able to approve a request whose window has closed.
    return requests[key]?.takeIf {
      it.expiresAtMillis > now || (it.state == Request.State.APPROVED && !grantIsGone(it, now))
    }
  }

  /**
   * Approve [id], minting a grant. [scope] and [ttlSeconds] are the approver's choice, each clamped
   * to what was asked for **and** to this store's ceiling — an approval can narrow a request but
   * never widen one, so a tampered form field buys nothing.
   *
   * Idempotent within the request's life: approving an already-approved request returns the same
   * grant, so a double-submitted form does not mint two credentials.
   */
  fun approve(
    id: String,
    approvedBy: String,
    scope: ServeAgentGrantScope,
    ttlSeconds: Long,
    capabilities: Set<ServeAgentGrantCapability> = emptySet(),
  ): Grant? {
    synchronized(this) {
      // Lookup, expiry validation and the state transition all inside the lock. Split across it,
      // a concurrent `purge()` — every poll and every `/status` runs one — could remove the entry
      // between the two, after which this minted a grant and marked a *detached* object approved:
      // the page said success and the agent's next poll found no map entry at all.
      val request = request(id) ?: return null
      when (request.state) {
        Request.State.APPROVED -> return request.grantId?.let { grants[it] }
        Request.State.DENIED -> return null
        Request.State.PENDING -> Unit
      }
      val now = clock()
      val granted = minOf(scope, request.requestedScope, maxScope)
      // Three-way intersection, exactly like the scope's three-way `minOf`: what the approver
      // ticked, what the agent asked for, and what this box permits at all. A tampered form field
      // therefore buys nothing here either.
      val grantedCapabilities =
        capabilities intersect request.requestedCapabilities intersect maxCapabilities
      val ttl = ttlSeconds.coerceIn(1, minOf(request.requestedTtlSeconds, maxGrantTtlSeconds))
      val grant =
        Grant(
          id = mintId(),
          token = mintToken(),
          scope = granted,
          capabilities = grantedCapabilities,
          label = request.label,
          approvedBy = approvedBy,
          issuedAtMillis = now,
          expiresAtMillis = now + ttl * 1000,
        )
      grants[grant.id] = grant
      byToken[grant.token] = grant.id
      evictOverflow(keep = grant)
      // Data BEFORE the flag that says the data is there. The lock [poll] now takes is what makes
      // the intermediate state unobservable; this ordering is the belt to that pair of braces, and
      // is the right shape regardless of who else ever reads these.
      request.grantId = grant.id
      request.resolvedBy = approvedBy
      request.state = Request.State.APPROVED
      audit(
        "agent-grant: minted ${grant.fingerprint} scope=${granted.wire} " +
          capabilityAuditField(grantedCapabilities) +
          "ttl=${ttl}s approver=$approvedBy label=\"${grant.label}\""
      )
      return grant
    }
  }

  /** Deny [id]. Idempotent; a no-op on an already-approved request (the token is already out). */
  fun deny(id: String, deniedBy: String): Boolean {
    synchronized(this) {
      // Same reason as [approve]: the lookup belongs inside the lock that purging takes.
      val request = request(id) ?: return false
      if (request.state != Request.State.PENDING) return false
      request.state = Request.State.DENIED
      request.resolvedBy = deniedBy
      audit("agent-grant: denied request by $deniedBy label=\"${request.label}\"")
      return true
    }
  }

  /**
   * Collect the outcome of [id] for a poller that proves possession of [deviceSecret].
   *
   * The secret is compared constant-time and a mismatch is reported as [Poll.Unknown] — the same
   * answer an id that never existed gets. Someone holding a leaked link learns nothing about
   * whether it is real, let alone collects its token.
   */
  fun poll(id: String?, deviceSecret: String?): Poll =
    synchronized(this) { pollLocked(id, deviceSecret) }

  /**
   * Under the lock, so a poll cannot observe a half-published approval.
   *
   * Ordering the writes in [approve] correctly — data, then the flag that says the data is there —
   * is necessary but not sufficient: a poller can simply *interleave* between the two statements,
   * no memory reordering required, see APPROVED with a null `grantId`, answer `expired`, and (the
   * client treats expiry as terminal) throw away the device secret for a grant that had just been
   * minted. Reading under the same lock the transition holds makes that state unobservable rather
   * than merely unlikely — the difference between a guarantee and a narrow window. The critical
   * section is a map lookup and a few field reads, on a lane that is rate-limited per caller.
   */
  private fun pollLocked(id: String?, deviceSecret: String?): Poll {
    val request = request(id) ?: return Poll.Unknown
    if (!ServeUrls.tokensMatch(request.deviceSecret, deviceSecret)) return Poll.Unknown
    return when (request.state) {
      Request.State.PENDING -> Poll.Pending(request.secondsUntilExpiry(clock()))
      Request.State.DENIED -> Poll.Denied(request.resolvedBy)
      Request.State.APPROVED -> {
        // The grant can have been revoked or expired between approval and this poll — a slow agent
        // and an operator with a fast revoke button. Say so plainly rather than handing back a
        // token that is already dead.
        val grant = request.grantId?.let { grant(it) } ?: return Poll.Expired
        request.collected = true
        Poll.Approved(grant)
      }
    }
  }

  /** What a poller is told. Mirrors RFC 8628's `authorization_pending` / `access_denied` shape. */
  sealed interface Poll {
    data class Pending(val secondsUntilExpiry: Long) : Poll

    data class Approved(val grant: Grant) : Poll

    data class Denied(val by: String?) : Poll

    /** The request ran out before anyone approved it, or its grant is already gone. */
    data object Expired : Poll

    /** No such request, or the wrong device secret. Deliberately indistinguishable. */
    data object Unknown : Poll
  }

  // ------------------------------------------------------------------ grants

  /**
   * The live grant a presented bearer names, or null. **This is the hot path** — it runs on every
   * gated request — so it is a shape check, one map lookup, and an expiry comparison, with the
   * purge sweep left to the slower lanes.
   */
  fun grantForToken(presented: String?): Grant? {
    val token = presented?.takeIf { isWellFormedToken(it) } ?: return null
    val id = byToken[token] ?: return null
    val grant = grants[id] ?: return null
    return grant.takeIf { it.expiresAtMillis > clock() }
  }

  /** The live grant with this id, or null when unknown/expired. */
  fun grant(id: String?): Grant? {
    val key = id ?: return null
    val now = clock()
    return grants[key]?.takeIf { it.expiresAtMillis > now }
  }

  /** Revoke a grant by id. Returns true when one went. */
  fun revoke(id: String, by: String): Boolean {
    val grant = grants.remove(id) ?: return false
    byToken.remove(grant.token)
    audit("agent-grant: revoked ${grant.fingerprint} by $by label=\"${grant.label}\"")
    return true
  }

  /** Revoke by presenting the token itself — how an agent hands its own access back. */
  fun revokeToken(presented: String?, by: String): Boolean {
    val grant = grantForToken(presented) ?: return false
    return revoke(grant.id, by)
  }

  /** Live grants, soonest expiry first — the `/status` table. */
  fun activeGrants(): List<Grant> {
    val now = clock()
    purge(now)
    return grants.values.sortedBy { it.expiresAtMillis }
  }

  /** Live (unresolved, unexpired) requests, soonest expiry first — the `/status` table. */
  fun pendingRequests(): List<Request> {
    val now = clock()
    purge(now)
    return requests.values
      .filter { it.state == Request.State.PENDING }
      .sortedBy { it.expiresAtMillis }
  }

  /** True when the grant a request minted is expired or revoked, so the record owes nobody. */
  private fun grantIsGone(request: Request, nowMillis: Long): Boolean {
    val grant = request.grantId?.let { grants[it] } ?: return true
    return grant.expiresAtMillis <= nowMillis
  }

  /** Drop everything — server shutdown, and the tests' reset. */
  fun clear() {
    requests.clear()
    grants.clear()
    byToken.clear()
  }

  /** Drop every expired request and grant; returns how many went in total. */
  fun purge(nowMillis: Long = clock()): Int = synchronized(this) { purgeLocked(nowMillis) }

  /** Reentrant body of [purge]; the lock is what makes it safe against [approve] and [deny]. */
  private fun purgeLocked(nowMillis: Long): Int {
    var dropped = 0
    requests.entries.removeIf { (_, r) ->
      // An approved request outlives its own deadline until its token has actually been fetched.
      // The request TTL bounds *how long a human has to decide*; once they have decided, deleting
      // the record strands the grant it created — which is exactly what happened when someone
      // approved in the last seconds of the window and the agent's next poll landed after it.
      // The grant's own expiry is what reclaims these, via [grantIsGone].
      // Retained while its grant lives, collected or not. `collected` marks that a poll *built* a
      // response, which is not the same as the agent receiving one: a response lost in flight left
      // the retry hitting a purged record and being told `unknown` while the grant was still live.
      // It stays meaningful for overflow (a collected request is the safest thing to shed), but it
      // is no longer a reason to delete anything.
      val keepForCollection = r.state == Request.State.APPROVED && !grantIsGone(r, nowMillis)
      (!keepForCollection && r.expiresAtMillis <= nowMillis).also { if (it) dropped++ }
    }
    grants.entries.removeIf { (_, g) ->
      (g.expiresAtMillis <= nowMillis).also {
        if (it) {
          byToken.remove(g.token)
          dropped++
        }
      }
    }
    return dropped
  }

  /**
   * Hold the active-grant count at its cap by dropping the nearest-expiry first, so a burst sheds
   * the grants closest to dying anyway rather than being refused.
   *
   * [keep] is the grant that was just minted, and it is excluded outright rather than trusted to
   * survive on its expiry. The earlier version reasoned that a new grant is always the furthest
   * from expiry — which is false the moment an approver chooses a shorter lifetime than everything
   * already live. A full map plus "give it fifteen minutes" evicted the grant it had just created:
   * the page said *approved*, and the agent's next poll found its id gone and was told the request
   * had expired.
   */
  /**
   * `caps=images ` for the audit line, or nothing at all when a grant carries none — which is the
   * overwhelmingly common case, and a trailing `caps=` on every line would be noise that trains the
   * reader to skip the field on the lines where it matters.
   */
  private fun capabilityAuditField(capabilities: Set<ServeAgentGrantCapability>): String =
    if (capabilities.isEmpty()) ""
    else "caps=${ServeAgentGrantCapability.wireNames(capabilities).joinToString(",")} "

  private fun evictOverflow(keep: Grant) {
    while (grants.size > maxActiveGrants) {
      val oldest =
        grants.values.filter { it.id != keep.id }.minByOrNull { it.expiresAtMillis } ?: return
      grants.remove(oldest.id)?.let { byToken.remove(it.token) }
      audit("agent-grant: evicted ${oldest.fingerprint} (over the $maxActiveGrants active cap)")
    }
  }

  companion object {
    /** Ten minutes. A link nobody opens should not stay openable all afternoon. */
    const val DEFAULT_REQUEST_TTL_SECONDS = 600L

    /** Eight hours — a working day's debugging, and gone by morning. */
    const val DEFAULT_MAX_GRANT_TTL_SECONDS = 8 * 60 * 60L

    /**
     * The absolute ceiling an operator may raise `--agent-grant-max-ttl` to. A grant that outlives
     * a day stops being "temporary access for this task" and becomes a credential nobody remembers
     * issuing, which is the thing this feature exists to replace.
     */
    const val HARD_MAX_GRANT_TTL_SECONDS = 24 * 60 * 60L

    /** What an agent gets when it names no TTL: long enough for one task. */
    const val DEFAULT_GRANT_TTL_SECONDS = 60 * 60L

    const val DEFAULT_MAX_ACTIVE_GRANTS = 16

    const val DEFAULT_MAX_PENDING_REQUESTS = 32

    /** How often a well-behaved poller should ask. Advertised in the request response. */
    const val POLL_INTERVAL_SECONDS = 3L

    /** Label/client text is display-only; a cap keeps a hostile agent out of the page's layout. */
    const val MAX_LABEL_CHARS = 120

    /**
     * Flatten an anonymous caller's free text to printable characters, then cap it.
     *
     * The label is written by whoever POSTed the request — nobody has authenticated at that point —
     * and it is later interpolated into a line this server prints to its own **audit log**. Left
     * raw it can carry newlines, which forge additional log lines, and terminal escape sequences,
     * which rewrite what the operator sees at the moment they are deciding whether to trust the
     * request. Neither is theoretical: the log line is emitted at approval time, on the operator's
     * console, from text the requester chose.
     *
     * Every C0/C1 control (and DEL), plus every Unicode **format** character — see
     * [isInvisibleFormat], which is what stops a bidi override from rewriting what the approval
     * page appears to say — becomes a space; runs collapse, and the result is trimmed and capped.
     * The HTML page escapes this same value again on its own account: escaping is not a substitute
     * for this, because the characters that matter here survive it untouched.
     */
    fun sanitizeLabel(raw: String): String =
      raw
        .map { if (it.isISOControl() || it == '\u007f' || it.isInvisibleFormat()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARS)

    /**
     * Unicode's **format** category (Cf): the characters that render as nothing but change how
     * everything around them is laid out.
     *
     * Stripping C0/C1 was not enough, and the gap mattered most on the one page whose entire job is
     * honest display. A requester chooses their own label, and `U+202E RIGHT-TO-LEFT OVERRIDE`
     * survives both an `isISOControl` filter and HTML escaping — so a label could visually reverse
     * the text after it and rewrite what the approval page appears to say, including the scope and
     * lifetime the human is agreeing to. The same trick reorders the audit line in the operator's
     * console. The isolates (`U+2066`–`U+2069`) and the zero-width joiners do the same job more
     * quietly.
     *
     * Cf is exactly that set — overrides, embeddings, isolates, marks, zero-widths, the BOM — and
     * nothing a purpose string legitimately needs, so the whole category goes.
     */
    private fun Char.isInvisibleFormat(): Boolean =
      Character.getType(this) == Character.FORMAT.toInt()

    /** The bearer's prefix — greppable, and unmistakable for the operator's `--token`. */
    const val TOKEN_PREFIX = "cpat_"

    private val random = SecureRandom()

    private fun random128(): String {
      val bytes = ByteArray(16)
      random.nextBytes(bytes)
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 128 bits — request and grant ids. Public handles, but unguessable all the same. */
    fun randomId(): String = random128()

    /** 256 bits for the agent's half: it is a credential, and it is never seen by a human. */
    fun randomSecret(): String {
      val bytes = ByteArray(32)
      random.nextBytes(bytes)
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 128 bits, prefixed. The bearer. */
    fun randomToken(): String = TOKEN_PREFIX + random128()

    /**
     * The human-checkable code: `XXXX-XXXX` over an alphabet with no `0/O`, `1/I/L`, `5/S`, `2/Z`,
     * `8/B` — a human reads this off one screen and compares it to another, and every confusable
     * pair is a chance to wave through a mismatch.
     *
     * ~40 bits of entropy, which is not the security boundary (the device secret is) but is well
     * clear of anyone guessing a live code inside its ten-minute life.
     */
    fun randomUserCode(): String {
      val code = CharArray(9)
      for (i in 0 until 9) {
        code[i] = if (i == 4) '-' else USER_CODE_ALPHABET[random.nextInt(USER_CODE_ALPHABET.length)]
      }
      return String(code)
    }

    private const val USER_CODE_ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679"

    /** Cheap shape check on a public id before a map lookup. */
    fun isWellFormedId(id: String): Boolean = id.matches(ID_SHAPE)

    /** Cheap shape check on a presented bearer, so an ordinary `--token` never reaches the map. */
    fun isWellFormedToken(token: String): Boolean = token.matches(TOKEN_SHAPE)

    /** SHA-256, first 12 hex characters — the only form of a token that is ever displayed. */
    fun fingerprintOf(token: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)

    private val ID_SHAPE = Regex("[A-Za-z0-9_-]{16,64}")
    private val TOKEN_SHAPE = Regex("${Regex.escape(TOKEN_PREFIX)}[A-Za-z0-9_-]{16,64}")
  }
}
