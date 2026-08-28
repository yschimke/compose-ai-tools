package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire types, the CSRF seal, and the pure decisions behind `/agent-access/…` — the device-grant
 * flow described in
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * The routes themselves are `handleAgentGrant*` on [ServeHttpServer], like every other lane on this
 * server, because they need its gates, its site skin and its external-origin view. What lives here
 * is everything that can be decided without a live call — which is deliberately most of it, so the
 * interesting parts are unit-testable without standing up Ktor.
 */
object ServeAgentGrants {

  /** Every route under this prefix; a constant first segment, so it outscores `/{system}`. */
  const val BASE_PATH = "/agent-access"

  const val REQUEST_PATH = "$BASE_PATH/request"
  const val POLL_PATH = "$BASE_PATH/poll"
  const val REVOKE_PATH = "$BASE_PATH/revoke"
  const val WHOAMI_PATH = "$BASE_PATH/whoami"

  /** The human's page for one request: `/agent-access/{requestId}`. */
  fun approvalPath(requestId: String): String =
    "$BASE_PATH/${WebEscaping.urlEncodeSegment(requestId)}"

  // ------------------------------------------------------------------- wire

  /**
   * `POST /agent-access/request` body. Every field optional: an agent that knows nothing about this
   * server can POST `{}` and get a `preview` grant request with the default TTL.
   */
  @Serializable
  data class OpenRequest(
    /** What the access is for, shown to the approver. Free text; displayed escaped. */
    val label: String = "",
    /** Highest scope wanted, by wire name. Unknown/absent ⇒ [AgentGrantScope.DEFAULT_REQUEST]. */
    val scope: String = "",
    /** Requested lifetime. Clamped to the box's `--agent-grant-max-ttl`. */
    @SerialName("ttlSeconds") val ttlSeconds: Long = 0,
    /**
     * Independent permissions wanted beside [scope], by wire name ([AgentGrantCapability]). Unknown
     * names are ignored rather than refused: this is an agent describing what it would like, and a
     * newer client naming a capability this server has never heard of should get the rest of its
     * request honoured, not a 400.
     */
    val capabilities: List<String> = emptyList(),
  )

  /**
   * What the agent gets back. [deviceSecret] is the only secret here and it is the one the agent
   * must keep — [approveUrl] is a handle a human is meant to be shown, and carries no authority.
   */
  @Serializable
  data class OpenResponse(
    val requestId: String,
    val deviceSecret: String,
    val userCode: String,
    /** Absolute — the agent prints this and a human opens it. */
    val approveUrl: String,
    /** Absolute — where to POST [PollRequest]. */
    val pollUrl: String,
    val expiresInSeconds: Long,
    val pollIntervalSeconds: Long,
    /** What was actually requested after clamping to this box's ceiling. */
    val requestedScope: String,
    val requestedTtlSeconds: Long,
    /** The most privileged scope this server will grant at all, so an agent can stop asking. */
    val maxScope: String,
    val maxTtlSeconds: Long,
    /** What was requested after clamping, so an agent can see a capability was dropped. */
    val requestedCapabilities: List<String> = emptyList(),
    /** Every capability this server will grant at all — empty on a box that offers none. */
    val maxCapabilities: List<String> = emptyList(),
  )

  @Serializable data class PollRequest(val requestId: String = "", val deviceSecret: String = "")

  /**
   * The poll answer. [status] follows RFC 8628's vocabulary closely enough to be unsurprising:
   * `pending`, `approved`, `denied`, `expired`, `unknown`.
   */
  @Serializable
  data class PollResponse(
    val status: String,
    /**
     * Present exactly once in this token's life, on the first `approved` poll and every re-poll.
     */
    val token: String? = null,
    /** Where to put [token] — spelled out so an agent needn't know this server's conventions. */
    val tokenHeader: String? = null,
    val scopes: List<String> = emptyList(),
    /** Independent permissions the human actually ticked. Usually empty. */
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    /** How long the agent should wait before polling again. */
    val retryAfterSeconds: Long? = null,
    /** Human-readable, safe to print. Never contains the token. */
    val message: String? = null,
  ) {
    companion object {
      const val PENDING = "pending"
      const val APPROVED = "approved"
      const val DENIED = "denied"
      const val EXPIRED = "expired"
      const val UNKNOWN = "unknown"
    }
  }

  /** `GET /agent-access/whoami` with a bearer — what this grant is, without ever echoing it. */
  @Serializable
  data class WhoamiResponse(
    val active: Boolean,
    val scopes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val label: String? = null,
    /** SHA-256 prefix, so a caller can match its grant to a `/status` row without disclosing it. */
    val fingerprint: String? = null,
  )

  @Serializable data class RevokeResponse(val revoked: Boolean, val message: String? = null)

  // --------------------------------------------------------------- approver

  /**
   * Who is approving, and what they are themselves allowed to pass on.
   *
   * The second half is the rule that matters: **an approver may never grant a capability they do
   * not hold**. On a GitHub-gated box, [ceiling] drops to [AgentGrantScope.LIVE] for a visitor
   * without access to `--github-auth-repo`, because that repo check is exactly the playground's own
   * gate — letting them tick the playground box would be a privilege escalation dressed as a
   * delegation.
   */
  data class Approver(
    /** Display name, and what the audit line records: a GitHub login, or `operator (token)`. */
    val name: String,
    val ceiling: AgentGrantScope,
    /**
     * The capabilities this approver may pass on — the same "never grant what you do not hold"
     * rule, applied to the half of a grant that is not a rung.
     *
     * [AgentGrantCapability.IMAGES] is the case that makes it concrete. The image lane admits a
     * caller who has **write access to the gating repository**, so a signed-in visitor without that
     * access cannot upload — and must not be able to hand an agent a token that does. It is the
     * identical argument to the playground's, which is why the two are computed from the same
     * `repositoryAccess` bit.
     */
    val capabilityCeiling: Set<AgentGrantCapability> = emptySet(),
  ) {
    companion object {
      /** The holder of `--token` on a box with no GitHub auth: the operator, so no narrowing. */
      fun operator(
        storeCeiling: AgentGrantScope,
        storeCapabilities: Set<AgentGrantCapability> = emptySet(),
      ): Approver = Approver("operator (token)", storeCeiling, storeCapabilities)

      fun github(
        login: String,
        repositoryAccess: Boolean,
        storeCeiling: AgentGrantScope,
        storeCapabilities: Set<AgentGrantCapability> = emptySet(),
      ) =
        Approver(
          name = "@$login",
          ceiling =
            if (repositoryAccess) storeCeiling else minOf(storeCeiling, AgentGrantScope.LIVE),
          capabilityCeiling = if (repositoryAccess) storeCapabilities else emptySet(),
        )
    }
  }

  // ------------------------------------------------------------------- CSRF

  /**
   * A per-process seal over `(requestId, approver, action)`, embedded in the approval form and
   * required back on the POST.
   *
   * `SameSite=Lax` on the session cookie already means a cross-site POST arrives without one, and
   * on a token-gated box the attacker would additionally need the `?token=`. This is the third
   * lock, and it is the one that does not depend on a browser honouring an attribute: a POST whose
   * seal was minted for a different approver, a different request, or a different action is refused
   * outright.
   *
   * The key is random per process and never persisted, so seals do not survive a restart. Neither
   * do grant requests, so there is nothing to be compatible with.
   */
  class Csrf(private val key: ByteArray = randomKey()) {

    fun seal(requestId: String, approver: String, action: String): String =
      mac("$requestId|$approver|$action")

    /** Constant-time. False for anything that wasn't minted here, for this exact triple. */
    fun verify(requestId: String, approver: String, action: String, presented: String?): Boolean =
      ServeUrls.tokensMatch(seal(requestId, approver, action), presented)

    private fun mac(payload: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    companion object {
      const val ACTION_APPROVE = "approve"
      const val ACTION_DENY = "deny"

      private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
  }

  // ----------------------------------------------------------- pure helpers

  /**
   * The scopes an approver may actually tick on the page: everything up to the lower of the store's
   * ceiling, the approver's own ceiling, and what the agent asked for. Asking for less than the
   * ceiling is the agent's own restraint and is honoured — the page never offers to *widen* a
   * request, because the agent has not told its human it wants more.
   */
  /**
   * The capabilities an approver may actually tick: the same three-way narrowing the scopes get —
   * what the agent asked for, what this approver holds, and what the box permits — so the form can
   * never offer something the POST would then refuse.
   */
  fun selectableCapabilities(
    requested: Set<AgentGrantCapability>,
    approver: Approver,
    storeCeiling: Set<AgentGrantCapability>,
  ): Set<AgentGrantCapability> =
    requested intersect approver.capabilityCeiling intersect storeCeiling

  fun selectableScopes(
    requested: AgentGrantScope,
    approver: Approver,
    storeCeiling: AgentGrantScope,
  ): List<AgentGrantScope> = AgentGrantScope.upTo(minOf(requested, approver.ceiling, storeCeiling))
}
