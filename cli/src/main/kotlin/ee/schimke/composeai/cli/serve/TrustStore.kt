package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleSigning
import java.io.File
import java.security.PublicKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The set of producers a verifier ([BundleVerifier]) trusts, loaded from a JSON file the operator
 * controls (`trust/producers.json`). Three independent bases, matching the three trust mechanisms a
 * public preview server supports:
 *
 * - [keys] — pinned Ed25519 public keys. A bundle signature whose `keyId` is here and that
 *   cryptographically verifies is **trusted by signature** (the strongest, fully offline basis).
 * - [branches] — GitHub `repo` + `branch` globs the server is willing to fetch design-system
 *   catalogs from. A bundle the server itself pulled from such a branch is **trusted by origin**
 *   (TLS trust in the source, no per-bundle crypto needed). This is how the published
 *   `design-artifacts` catalogs are trusted.
 * - [oidc] — GitHub Actions / Sigstore workload-identity globs. These do **not** by themselves
 *   grant trust: provenance is self-asserted data, so a real keyless proof needs Fulcio
 *   cert-chain + Rekor verification (a follow-up). Until that lands, a trusted `oidc` identity only
 *   *annotates* a signature a pinned [keys] entry already verified — it never expands the trust
 *   decision, so it can't be a bypass. See [BundleVerifier].
 *
 * An empty store trusts nothing (fail-closed): every bundle verifies as `Unverified`, so a public
 * server with no trust store still serves data tiers but never re-renders untrusted Compose.
 */
@Serializable
data class TrustStore(
  val keys: List<TrustedKey> = emptyList(),
  val branches: List<TrustedBranch> = emptyList(),
  val oidc: List<TrustedIdentity> = emptyList(),
) {

  /** Resolve the pinned public key for [keyId], or null when the store doesn't trust it. */
  fun publicKeyFor(keyId: String): PublicKey? {
    val entry = keys.firstOrNull { it.keyId == keyId } ?: return null
    return runCatching { BundleSigning.parsePublicKey(entry.publicKey) }.getOrNull()
  }

  fun keyName(keyId: String): String? = keys.firstOrNull { it.keyId == keyId }?.name

  /** True when the store trusts catalogs fetched from [repo]@[branch] (glob-matched). */
  fun trustsBranch(repo: String, branch: String): Boolean = branches.any {
    globMatch(it.repo, repo) && globMatch(it.branch, branch)
  }

  /** True when the store trusts a CI provenance [identity] (glob-matched). */
  fun trustsIdentity(identity: String): Boolean = oidc.any { globMatch(it.identity, identity) }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writer JSON. Distinct from [json] because rewriting the operator's producers.json has
     * different needs from reading it: the file stays hand-editable, so it's pretty-printed, and
     * defaults are written out rather than elided (a [TrustedBranch] that defaults `branch` to `*`
     * must say so on disk — an operator reading back a bare `{"repo": …}` would have no way to see
     * that it trusts every branch).
     */
    private val writerJson = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      prettyPrintIndent = "  "
      encodeDefaults = true
    }

    /** The empty, fail-closed store — trusts nothing. */
    val EMPTY = TrustStore()

    fun load(file: File): TrustStore =
      json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))

    fun parse(text: String): TrustStore = json.decodeFromString(serializer(), text)

    fun encode(store: TrustStore): String = writerJson.encodeToString(serializer(), store) + "\n"

    /**
     * Producer patterns are globs, so this is deliberately looser than a catalog repo slug — but
     * still a slug alphabet, because a pattern with a newline or a space is a typo that would
     * silently never match.
     */
    private val REPO_PATTERN_RE = Regex("[A-Za-z0-9._*-]{1,64}/[A-Za-z0-9._*-]{1,64}")
    private val BRANCH_PATTERN_RE = Regex("[A-Za-z0-9._*/-]{1,128}")

    /**
     * Why [branch] is unusable as a trust entry, or null when it's well-formed.
     *
     * A repo pattern whose every non-slash character is a wildcard is rejected outright. Branch
     * trust is not only a badge — with `--allow-render-trusted` it gates server-side execution of
     * the producer's Compose — so a match-everything pattern would hand code execution to any repo
     * on GitHub. There is no legitimate use for it, and the failure mode is bad enough that a typo
     * shouldn't be able to reach it.
     */
    fun validateBranch(branch: TrustedBranch): String? =
      when {
        !REPO_PATTERN_RE.matches(branch.repo) -> "invalid repo pattern '${branch.repo}'"
        !BRANCH_PATTERN_RE.matches(branch.branch) -> "invalid branch pattern '${branch.branch}'"
        branch.repo.replace("/", "").all { it == '*' } ->
          "repo pattern '${branch.repo}' matches every repository; name an owner"
        else -> null
      }

    /**
     * Why [key] is unusable, or null when it's well-formed (and its public key actually parses).
     */
    fun validateKey(key: TrustedKey): String? =
      when {
        key.keyId.isBlank() -> "key entry needs a keyId"
        key.publicKey.isBlank() -> "key '${key.keyId}' needs a publicKey"
        runCatching { BundleSigning.parsePublicKey(key.publicKey) }.isFailure ->
          "key '${key.keyId}' has an unparseable publicKey"
        else -> null
      }

    /** Why [identity] is unusable, or null when it's well-formed. */
    fun validateIdentity(identity: TrustedIdentity): String? =
      if (identity.identity.isBlank()) "oidc entry needs an identity" else null

    /**
     * Glob match supporting `*` (any run of chars, including `/`) — enough for `repo`/`branch`/
     * `identity` patterns like `design-artifacts/<glob>` or
     * `repo:yschimke/compose-ai-tools:ref:...`. Anchored (full-string) and case-sensitive. A
     * literal pattern with no `*` is exact-match.
     */
    fun globMatch(pattern: String, value: String): Boolean {
      if (!pattern.contains('*')) return pattern == value
      val regex = buildString {
        append('^')
        for (c in pattern) {
          if (c == '*') append(".*") else append(Regex.escape(c.toString()))
        }
        append('$')
      }
      return Regex(regex).matches(value)
    }
  }
}

/** A pinned producer public key. [publicKey] is PEM or base64 X.509 SPKI (see [BundleSigning]). */
@Serializable
data class TrustedKey(val keyId: String, val publicKey: String, val name: String? = null)

/** A GitHub branch the server may fetch trusted catalogs from. `branch` defaults to "any". */
@Serializable data class TrustedBranch(val repo: String, val branch: String = "*")

/** A trusted CI workload identity (GitHub OIDC subject / Sigstore identity), glob-matched. */
@Serializable data class TrustedIdentity(val identity: String)
