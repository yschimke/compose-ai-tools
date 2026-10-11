package ee.schimke.composeai.bundle

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
 * Ed25519 bundle signing + verification, behind `compose-preview bundle keygen|sign|verify` and the
 * public-server trust gate ([BundleVerifier]).
 *
 * Signs the bundle's [canonicalDigest], not the raw file: zip order and compression aren't
 * byte-stable, and `signatures.json` is excluded so further signatures can be appended. Mirrors the
 * `BundleSignatures` contract in `:gradle-plugin`'s `PreviewBundleFormat.kt`.
 */
public object BundleSigning {

  /** Zip path of the detached signatures; excluded from the digest it signs. */
  public const val SIGNATURES_PATH: String = "signatures.json"
  public const val SIGNATURES_SCHEMA: String = "compose-preview-bundle/signatures/v1"
  public const val ALG_ED25519: String = "ed25519"

  /** Mirror of `BundleSignatures` in the Gradle plugin's `PreviewBundleFormat.kt`. */
  @Serializable
  public data class Signatures(
    val schema: String = SIGNATURES_SCHEMA,
    val signatures: List<Signature>,
  )

  /** Mirror of `BundleSignature` in the Gradle plugin's `PreviewBundleFormat.kt`. */
  @Serializable
  public data class Signature(
    val keyId: String,
    val algorithm: String = ALG_ED25519,
    val digest: String,
    val signature: String,
    val producer: String? = null,
    val provenance: Provenance? = null,
  )

  /** CLI-side mirror of `BundleProvenance`. */
  @Serializable
  public data class Provenance(
    val type: String,
    val identity: String,
    val attestation: String? = null,
  )

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  // --- canonical digest -------------------------------------------------------------------------

  /**
   * SHA-256 over the bundle's logical content: one `"<path>:<hex-sha256>"` line per non-directory
   * zip entry except [SIGNATURES_PATH], sorted by path and `\n`-joined.
   */
  public fun canonicalDigest(zipBytes: ByteArray): ByteArray {
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
  public fun canonicalDigest(bundle: File): ByteArray =
    canonicalDigest(BundleReader.extractZipBytes(bundle))

  // --- signatures.json read / write ------------------------------------------------------------

  /** Decode `signatures.json` from a bundle's [zipBytes], or null when the bundle is unsigned. */
  public fun readSignatures(zipBytes: ByteArray): Signatures? {
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

  public fun readSignatures(bundle: File): Signatures? =
    readSignatures(BundleReader.extractZipBytes(bundle))

  /**
   * Append [signature] to [bundle]'s `signatures.json` in place (merging with any signatures
   * already present, replacing one with the same `keyId`), keeping the leading PNG cover and every
   * other entry. Idempotent per keyId. Returns the resulting signature count.
   */
  public fun addSignature(bundle: File, signature: Signature): Int {
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
  public fun signEd25519(privateKey: PrivateKey, digest: ByteArray): ByteArray {
    // Fully qualified — the nested [Signature] data class shadows java.security.Signature here.
    val sig = java.security.Signature.getInstance("Ed25519")
    sig.initSign(privateKey)
    sig.update(digest)
    return sig.sign()
  }

  /** Verify [signatureBytes] over [digest] against an Ed25519 [publicKey]. */
  public fun verifyEd25519(
    publicKey: PublicKey,
    digest: ByteArray,
    signatureBytes: ByteArray,
  ): Boolean = runCatching {
    val sig = java.security.Signature.getInstance("Ed25519")
    sig.initVerify(publicKey)
    sig.update(digest)
    sig.verify(signatureBytes)
  }
    .getOrDefault(false)

  /** A fresh Ed25519 keypair, base64-encoded: private = PKCS#8 DER, public = X.509 SPKI DER. */
  public data class KeyPairB64(val privateKeyB64: String, val publicKeyB64: String)

  public fun generateKeyPair(): KeyPairB64 {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    return KeyPairB64(
      privateKeyB64 = Base64.getEncoder().encodeToString(pair.private.encoded),
      publicKeyB64 = Base64.getEncoder().encodeToString(pair.public.encoded),
    )
  }

  /** Parse an Ed25519 private key from PEM (`-----BEGIN PRIVATE KEY-----`) or raw base64 PKCS#8. */
  public fun parsePrivateKey(text: String): PrivateKey {
    val der = decodePemOrBase64(text, "PRIVATE KEY")
    return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
  }

  /**
   * Parse an Ed25519 public key from PEM (`-----BEGIN PUBLIC KEY-----`) or raw base64 X.509 SPKI.
   */
  public fun parsePublicKey(text: String): PublicKey {
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

  public fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  public fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

  public fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  public fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  /**
   * Normalize raw bundle bytes to the ZIP portion: a plain zip is returned as-is, a PNG+ZIP
   * polyglot has its PNG stripped. Unrecognised input is returned unchanged.
   */
  public fun zipBytesOf(raw: ByteArray): ByteArray {
    if (raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()) return raw
    val pngSig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (raw.size < pngSig.size || !pngSig.indices.all { raw[it] == pngSig[it] }) return raw
    var offset = pngSig.size
    while (offset + 8 <= raw.size) {
      val length =
        ((raw[offset].toInt() and 0xff) shl 24) or
          ((raw[offset + 1].toInt() and 0xff) shl 16) or
          ((raw[offset + 2].toInt() and 0xff) shl 8) or
          (raw[offset + 3].toInt() and 0xff)
      // SECURITY: these bytes are client-controlled; a negative chunk length (e.g. 0xfffffff4)
      // would stall or reverse the scan. Treat as not-a-polyglot, so the upload fails cleanly.
      if (length < 0) return raw
      val type = String(raw, offset + 4, 4, Charsets.US_ASCII)
      // 12 = 4 (length) + 4 (type) + 4 (crc). Compute in Long so a huge length can't overflow Int
      // into a negative offset; require strict forward progress within bounds.
      val next = offset.toLong() + 12L + length.toLong()
      if (type == "IEND")
        return if (next in 0..raw.size.toLong()) raw.copyOfRange(next.toInt(), raw.size) else raw
      if (next <= offset || next > raw.size) return raw
      offset = next.toInt()
    }
    return raw
  }
}
