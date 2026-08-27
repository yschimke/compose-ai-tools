package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleSigning
import java.io.File

/**
 * Decides whether a bundle came from a producer the operator trusts, by combining the three
 * [TrustStore] bases against a bundle's `signatures.json` and (optionally) the [Origin] the server
 * fetched it from. The result gates whether the public preview server will **re-render** the
 * bundle's executable Compose — data tiers (baked PNGs, Remote Compose / protolayout / Lottie IR)
 * serve regardless of the verdict because they execute no code.
 *
 * Verification is fail-closed: anything the store can't positively attribute is
 * [Verdict.Unverified].
 */
object BundleVerifier {

  /** Where the server obtained the bundle, when it fetched it itself (vs. a client upload). */
  data class Origin(val repo: String, val branch: String)

  sealed interface Verdict {
    /** At least one trust basis matched. [bases] lists every basis that did, strongest first. */
    data class Trusted(val bases: List<Basis>) : Verdict {
      val primary: Basis
        get() = bases.first()
    }

    /** No basis matched. [reason] is a short human-readable explanation. */
    data class Unverified(val reason: String) : Verdict
  }

  sealed interface Basis {
    /** A pinned Ed25519 key signed the canonical digest and verified. The strongest basis. */
    data class Signature(val keyId: String, val producer: String?) : Basis

    /** The server fetched the bundle from a trusted branch (origin/TLS trust). */
    data class Branch(val repo: String, val branch: String) : Basis

    /**
     * Supplementary CI-provenance context recorded **only alongside a cryptographically-verified
     * [Signature]** — never on its own. A `provenance` block is self-asserted data (any uploader
     * can write any identity), so identity-glob + digest match is *not* proof of origin; granting
     * trust from it alone would be a bypass (an attacker writes `signatures.json` with the
     * recomputed digest and a matching identity → `Trusted` → re-render of executable Compose).
     * Real keyless trust needs Fulcio cert-chain + Rekor verification, which is a follow-up; until
     * then this basis only annotates a signature a pinned key already verified, so it expands the
     * displayed provenance but never the trust decision.
     */
    data class Provenance(val identity: String, val type: String) : Basis
  }

  /** Verify a bundle file. [origin] is non-null only when the server fetched it itself. */
  fun verify(bundle: File, trust: TrustStore, origin: Origin? = null): Verdict =
    verifyCore(
      BundleSigning.canonicalDigest(bundle),
      BundleSigning.readSignatures(bundle)?.signatures.orEmpty(),
      trust,
      origin,
    )

  /**
   * Verify in-memory bundle bytes — the runtime upload path ([ServeBundleStore]) has the bytes, not
   * a file. Accepts either a plain zip or a PNG+ZIP polyglot ([BundleSigning.zipBytesOf]
   * normalizes).
   */
  fun verify(rawBundleBytes: ByteArray, trust: TrustStore, origin: Origin? = null): Verdict {
    val zip = BundleSigning.zipBytesOf(rawBundleBytes)
    return verifyCore(
      BundleSigning.canonicalDigest(zip),
      BundleSigning.readSignatures(zip)?.signatures.orEmpty(),
      trust,
      origin,
    )
  }

  /**
   * Short one-line label for logs / API / UI badge, e.g. `signature:ci`, `branch`, `unverified`.
   */
  fun summary(verdict: Verdict): String =
    when (verdict) {
      is Verdict.Trusted ->
        when (val b = verdict.primary) {
          is Basis.Signature -> "signature:${b.keyId}"
          is Basis.Branch -> "branch:${b.repo}@${b.branch}"
          is Basis.Provenance -> "provenance:${b.identity}"
        }
      is Verdict.Unverified -> "unverified"
    }

  private fun verifyCore(
    digest: ByteArray,
    signatures: List<BundleSigning.Signature>,
    trust: TrustStore,
    origin: Origin?,
  ): Verdict {
    val expectedDigestHex = BundleSigning.hex(digest)
    val bases = ArrayList<Basis>()

    // 1) Origin trust — the server pulled it from a branch it trusts.
    if (origin != null && trust.trustsBranch(origin.repo, origin.branch)) {
      bases.add(Basis.Branch(origin.repo, origin.branch))
    }

    // 2) Signature trust — a pinned key cryptographically verifies the canonical digest. This is
    // the
    // ONLY path that turns a signature into trust. Provenance is recorded as supplementary context
    // for a signature that *already* verified — it never grants trust on its own (see
    // [Basis.Provenance]).
    for (sig in signatures) {
      if (sig.algorithm != BundleSigning.ALG_ED25519) continue
      // The signature's claimed digest must match what we recomputed (no signing a different
      // bundle).
      if (sig.digest != expectedDigestHex) continue
      val key = trust.publicKeyFor(sig.keyId) ?: continue
      val ok = runCatching {
        BundleSigning.verifyEd25519(key, digest, BundleSigning.decodeBase64(sig.signature))
      }
        .getOrDefault(false)
      if (!ok) continue
      bases.add(Basis.Signature(sig.keyId, sig.producer ?: trust.keyName(sig.keyId)))
      // Only now — on a cryptographically verified signature — record a trusted CI identity it
      // carries, as extra provenance context. A pinned key did the actual attesting.
      val prov = sig.provenance
      if (prov != null && trust.trustsIdentity(prov.identity)) {
        bases.add(Basis.Provenance(prov.identity, prov.type))
      }
    }

    // Order strongest-first: Signature > Branch > Provenance.
    val ordered = bases.sortedBy {
      when (it) {
        is Basis.Signature -> 0
        is Basis.Branch -> 1
        is Basis.Provenance -> 2
      }
    }
    return if (ordered.isEmpty())
      Verdict.Unverified(
        if (signatures.isEmpty()) "unsigned bundle" else "no trusted signature/branch/provenance"
      )
    else Verdict.Trusted(ordered)
  }
}
