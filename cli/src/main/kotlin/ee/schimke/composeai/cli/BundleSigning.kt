package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ed25519 bundle signing + verification — the reference implementation shared by `compose-preview
 * bundle sign|verify|keygen` ([BundleSignCommand]) and the public-server trust gate
 * ([BundleVerifier]).
 *
 * The signed bytes are the bundle's **canonical digest**, not the raw `.png` file: zip ordering /
 * compression aren't byte-stable and `signatures.json` itself must be excluded so a second producer
 * can append a signature without invalidating the first. See [canonicalDigest] for the exact recipe
 * (it mirrors the contract documented on `BundleSignatures` in `:gradle-plugin`'s
 * `PreviewBundleFormat.kt` — the CLI re-declares the wire shape here rather than depend on that
 * module, same pattern as [BundleReader]).
 */
internal object BundleSigning {

  /** Zip path of the detached signatures; excluded from the digest it signs. */
  const val SIGNATURES_PATH = "signatures.json"
  const val SIGNATURES_SCHEMA = "compose-preview-bundle/signatures/v1"
  const val ALG_ED25519 = "ed25519"

  /** CLI-side mirror of `BundleSignatures` in `PreviewBundleFormat.kt`. */
  @Serializable
  data class Signatures(val schema: String = SIGNATURES_SCHEMA, val signatures: List<Signature>)

  /** CLI-side mirror of `BundleSignature`. */
  @Serializable
  data class Signature(
    val keyId: String,
    val algorithm: String = ALG_ED25519,
    val digest: String,
    val signature: String,
    val producer: String? = null,
    val provenance: Provenance? = null,
  )

  /** CLI-side mirror of `BundleProvenance`. */
  @Serializable
  data class Provenance(val type: String, val identity: String, val attestation: String? = null)

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  // --- canonical digest -------------------------------------------------------------------------

  /**
   * The SHA-256 over the bundle's logical content (see the class kdoc). Every zip entry except
   * [SIGNATURES_PATH] and directory entries contributes a `"<path>:<hex-sha256>"` line; the lines
   * are sorted by path and joined with `\n`, and the digest is the SHA-256 of that string.
   * Deterministic regardless of zip ordering or compression, and stable when signatures are
   * appended.
   */
  fun canonicalDigest(zipBytes: ByteArray): ByteArray {
    val lines = ArrayList<String>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && entry.name != SIGNATURES_PATH) {
          val sha = sha256(zin.readBytes())
          lines.add("${entry.name}:${hex(sha)}")
        }
        zin.closeEntry()
      }
    }
    lines.sort()
    return sha256(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
  }

  /** Canonical digest of a bundle file (polyglot-aware). */
  fun canonicalDigest(bundle: File): ByteArray =
    canonicalDigest(BundleReader.extractZipBytes(bundle))

  // --- signatures.json read / write ------------------------------------------------------------

  /** Decode `signatures.json` from a bundle's [zipBytes], or null when the bundle is unsigned. */
  fun readSignatures(zipBytes: ByteArray): Signatures? {
    var bytes: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && entry.name == SIGNATURES_PATH) bytes = zin.readBytes()
        zin.closeEntry()
      }
    }
    val raw = bytes ?: return null
    return runCatching {
        json.decodeFromString(Signatures.serializer(), raw.toString(Charsets.UTF_8))
      }
      .getOrNull()
  }

  fun readSignatures(bundle: File): Signatures? =
    readSignatures(BundleReader.extractZipBytes(bundle))

  /**
   * Append [signature] to [bundle]'s `signatures.json` in place (merging with any signatures
   * already present, replacing one with the same `keyId`), keeping the leading PNG cover and every
   * other entry. Idempotent per keyId. Returns the resulting signature count.
   */
  fun addSignature(bundle: File, signature: Signature): Int {
    val existing =
      readSignatures(bundle)?.signatures.orEmpty().filter { it.keyId != signature.keyId }
    val merged = Signatures(signatures = existing + signature)
    val bytes = json.encodeToString(Signatures.serializer(), merged).toByteArray(Charsets.UTF_8)
    // Reuse the polyglot-preserving zip rewriter the semantics/web injectors use.
    injectRawZipEntries(bundle, mapOf(SIGNATURES_PATH to bytes))
    return merged.signatures.size
  }

  // --- Ed25519 ----------------------------------------------------------------------------------

  /** Sign [digest] with an Ed25519 [privateKey]; returns the raw signature bytes. */
  fun signEd25519(privateKey: PrivateKey, digest: ByteArray): ByteArray {
    // Fully qualified — the nested [Signature] data class shadows java.security.Signature here.
    val sig = java.security.Signature.getInstance("Ed25519")
    sig.initSign(privateKey)
    sig.update(digest)
    return sig.sign()
  }

  /** Verify [signatureBytes] over [digest] against an Ed25519 [publicKey]. */
  fun verifyEd25519(publicKey: PublicKey, digest: ByteArray, signatureBytes: ByteArray): Boolean =
    runCatching {
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initVerify(publicKey)
        sig.update(digest)
        sig.verify(signatureBytes)
      }
      .getOrDefault(false)

  /** A fresh Ed25519 keypair, base64-encoded: private = PKCS#8 DER, public = X.509 SPKI DER. */
  data class KeyPairB64(val privateKeyB64: String, val publicKeyB64: String)

  fun generateKeyPair(): KeyPairB64 {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    return KeyPairB64(
      privateKeyB64 = Base64.getEncoder().encodeToString(pair.private.encoded),
      publicKeyB64 = Base64.getEncoder().encodeToString(pair.public.encoded),
    )
  }

  /** Parse an Ed25519 private key from PEM (`-----BEGIN PRIVATE KEY-----`) or raw base64 PKCS#8. */
  fun parsePrivateKey(text: String): PrivateKey {
    val der = decodePemOrBase64(text, "PRIVATE KEY")
    return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
  }

  /**
   * Parse an Ed25519 public key from PEM (`-----BEGIN PUBLIC KEY-----`) or raw base64 X.509 SPKI.
   */
  fun parsePublicKey(text: String): PublicKey {
    val der = decodePemOrBase64(text, "PUBLIC KEY")
    return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
  }

  private fun decodePemOrBase64(text: String, label: String): ByteArray {
    val trimmed = text.trim()
    val body =
      if (trimmed.contains("-----BEGIN")) {
        trimmed
          .substringAfter("-----BEGIN $label-----", trimmed)
          .substringBefore("-----END $label-----")
          .replace("\\s".toRegex(), "")
      } else {
        trimmed.replace("\\s".toRegex(), "")
      }
    return Base64.getDecoder().decode(body)
  }

  // --- helpers ----------------------------------------------------------------------------------

  fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

  fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
