package ee.schimke.composeai.bundle

import java.io.File
import java.security.PublicKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The producers a verifier ([BundleVerifier]) trusts, from an operator-controlled
 * `trust/producers.json`:
 *
 * - [keys] — pinned Ed25519 public keys; a verifying signature by one is trusted (fully offline).
 * - [branches] — GitHub `repo` + `branch` globs the server fetches catalogs from; bundles pulled
 *   from them are trusted by origin (how `design-artifacts` catalogs are trusted).
 * - [oidc] — workload-identity globs. These never grant trust on their own (self-asserted until
 *   Fulcio/Rekor verification exists); they only annotate a signature a pinned key already
 *   verified.
 *
 * An empty store trusts nothing (fail-closed): data tiers are served, untrusted Compose is never
 * re-rendered.
 */
@Serializable
public data class TrustStore(
  public val keys: List<TrustedKey> = emptyList(),
  public val branches: List<TrustedBranch> = emptyList(),
  public val oidc: List<TrustedIdentity> = emptyList(),
) {

  /** Resolve the pinned public key for [keyId], or null when the store doesn't trust it. */
  public fun publicKeyFor(keyId: String): PublicKey? {
    val entry = keys.firstOrNull { it.keyId == keyId } ?: return null
    return runCatching { BundleSigning.parsePublicKey(entry.publicKey) }.getOrNull()
  }

  public fun keyName(keyId: String): String? = keys.firstOrNull { it.keyId == keyId }?.name

  /** True when the store trusts catalogs fetched from [repo]@[branch] (glob-matched). */
  public fun trustsBranch(repo: String, branch: String): Boolean = branches.any {
    globMatch(it.repo, repo) && globMatch(it.branch, branch)
  }

  /** True when the store trusts a CI provenance [identity] (glob-matched). */
  public fun trustsIdentity(identity: String): Boolean = oidc.any {
    globMatch(it.identity, identity)
  }

  public companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writer JSON: pretty-printed so the file stays hand-editable, with defaults written out (a
     * [TrustedBranch]'s default `branch = *` must be visible on disk).
     */
    private val writerJson = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      prettyPrintIndent = "  "
      encodeDefaults = true
    }

    /** The empty, fail-closed store — trusts nothing. */
    public val EMPTY: TrustStore = TrustStore()

    public fun load(file: File): TrustStore =
      json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))

    public fun parse(text: String): TrustStore = json.decodeFromString(serializer(), text)

    public fun encode(store: TrustStore): String =
      writerJson.encodeToString(serializer(), store) + "\n"

    /**
     * Producer patterns are globs but still a slug alphabet, so a typo with a space or newline is
     * rejected rather than silently never matching.
     */
    private val REPO_PATTERN_RE = Regex("[A-Za-z0-9._*-]{1,64}/[A-Za-z0-9._*-]{1,64}")
    private val BRANCH_PATTERN_RE = Regex("[A-Za-z0-9._*/-]{1,128}")

    /**
     * Why [branch] is unusable as a trust entry, or null when well-formed.
     *
     * SECURITY: a repo pattern that is all wildcards is rejected — with `--allow-render-trusted`,
     * branch trust gates server-side execution of the producer's Compose.
     */
    public fun validateBranch(branch: TrustedBranch): String? =
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
    public fun validateKey(key: TrustedKey): String? =
      when {
        key.keyId.isBlank() -> "key entry needs a keyId"
        key.publicKey.isBlank() -> "key '${key.keyId}' needs a publicKey"
        runCatching { BundleSigning.parsePublicKey(key.publicKey) }.isFailure ->
          "key '${key.keyId}' has an unparseable publicKey"
        else -> null
      }

    /** Why [identity] is unusable, or null when it's well-formed. */
    public fun validateIdentity(identity: TrustedIdentity): String? =
      if (identity.identity.isBlank()) "oidc entry needs an identity" else null

    /**
     * Anchored, case-sensitive glob match where `*` matches any run of characters (including `/`);
     * a pattern without `*` is an exact match.
     */
    public fun globMatch(pattern: String, value: String): Boolean {
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
public data class TrustedKey(val keyId: String, val publicKey: String, val name: String? = null)

/** A GitHub branch the server may fetch trusted catalogs from. `branch` defaults to "any". */
@Serializable public data class TrustedBranch(val repo: String, val branch: String = "*")

/** A trusted CI workload identity (GitHub OIDC subject / Sigstore identity), glob-matched. */
@Serializable public data class TrustedIdentity(val identity: String)
