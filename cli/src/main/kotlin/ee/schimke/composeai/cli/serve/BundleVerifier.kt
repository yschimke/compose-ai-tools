package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BundleSigning
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
     * A signature carried a CI provenance attestation whose identity the store trusts, and the
     * attested digest matches the recomputed canonical digest. Advisory: the binding is the
     * identity-glob match plus digest integrity, not (yet) a full Sigstore/Rekor proof.
     */
    data class Provenance(val identity: String, val type: String) : Basis
  }

  /** Verify a bundle file. [origin] is non-null only when the server fetched it itself. */
  fun verify(bundle: File, trust: TrustStore, origin: Origin? = null): Verdict {
    val digest = BundleSigning.canonicalDigest(bundle)
    val expectedDigestHex = BundleSigning.hex(digest)
    val bases = ArrayList<Basis>()

    // 1) Origin trust — the server pulled it from a branch it trusts.
    if (origin != null && trust.trustsBranch(origin.repo, origin.branch)) {
      bases.add(Basis.Branch(origin.repo, origin.branch))
    }

    // 2) Signature trust — a pinned key cryptographically verifies the canonical digest.
    val signatures = BundleSigning.readSignatures(bundle)?.signatures.orEmpty()
    for (sig in signatures) {
      if (sig.algorithm != BundleSigning.ALG_ED25519) continue
      // The signature's claimed digest must match what we recomputed (no signing a different
      // bundle).
      if (sig.digest != expectedDigestHex) continue
      val key = trust.publicKeyFor(sig.keyId)
      if (key != null) {
        val ok =
          runCatching {
              BundleSigning.verifyEd25519(key, digest, BundleSigning.decodeBase64(sig.signature))
            }
            .getOrDefault(false)
        if (ok) bases.add(Basis.Signature(sig.keyId, sig.producer ?: trust.keyName(sig.keyId)))
      }
    }

    // 3) Provenance trust — a CI identity the store trusts attested this exact digest.
    for (sig in signatures) {
      val prov = sig.provenance ?: continue
      if (sig.digest != expectedDigestHex) continue
      if (trust.trustsIdentity(prov.identity)) {
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
