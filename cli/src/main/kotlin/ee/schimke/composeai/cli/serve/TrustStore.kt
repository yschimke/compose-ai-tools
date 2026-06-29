package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BundleSigning
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
 * - [oidc] — GitHub Actions / Sigstore workload-identity globs. A signature carrying a matching
 *   provenance attestation is **trusted by provenance** (keyless CI identity). Advisory until full
 *   Rekor/Sigstore verification lands — see [BundleVerifier].
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

    /** The empty, fail-closed store — trusts nothing. */
    val EMPTY = TrustStore()

    fun load(file: File): TrustStore =
      json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))

    fun parse(text: String): TrustStore = json.decodeFromString(serializer(), text)

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
