package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.BundleVerifier
import ee.schimke.composeai.cli.serve.TrustStore
import ee.schimke.composeai.cli.serve.TrustedBranch
import ee.schimke.composeai.cli.serve.TrustedIdentity
import ee.schimke.composeai.cli.serve.TrustedKey
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ed25519 bundle signing + trust verification ([BundleSigning], [TrustStore], [BundleVerifier]) —
 * the producer-trust core that lets the public preview server attribute a bundle before
 * re-rendering its executable Compose. Exercises the canonical digest, the sign→verify round trip,
 * tamper detection, wrong-key rejection, and the branch / provenance trust bases.
 */
class BundleSigningTest {

  private val workRoot = Files.createTempDirectory("bundle-signing-test-").toFile()

  @AfterTest
  fun cleanup() {
    workRoot.deleteRecursively()
  }

  private fun png(w: Int = 4, h: Int = 4, rgb: Int = 0x112233): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, rgb)
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  private fun bundle(name: String, entries: Map<String, ByteArray>): File {
    // The leading PNG cover is outside the appended zip and excluded from the canonical digest, so
    // a
    // fixed valid PNG keeps the polyglot readable without affecting any digest assertion.
    val cover = png(2, 2)
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { z ->
      for ((path, bytes) in entries) {
        z.putNextEntry(ZipEntry(path))
        z.write(bytes)
        z.closeEntry()
      }
    }
    val file = File(workRoot, name)
    file.outputStream().use {
      it.write(cover)
      it.write(zip.toByteArray())
    }
    return file
  }

  private fun sampleBundle(name: String = "bundle.png"): File {
    val cover = png(4, 8)
    return bundle(
      name,
      linkedMapOf(
        "bundle.json" to
          """{"schemaVersion":7,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test"}"""
            .toByteArray(),
        "previews/a.png" to cover,
        "classes/app.jar" to "FAKEJAR".toByteArray(),
      ),
    )
  }

  @Test
  fun `canonical digest is stable across zip ordering and changes on tamper`() {
    val a = sampleBundle("a.png")
    val b =
      bundle(
        "b.png",
        // Same logical content, different insertion order.
        linkedMapOf(
          "classes/app.jar" to "FAKEJAR".toByteArray(),
          "previews/a.png" to png(4, 8),
          "bundle.json" to
            """{"schemaVersion":7,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test"}"""
              .toByteArray(),
        ),
      )
    assertEquals(
      BundleSigning.hex(BundleSigning.canonicalDigest(a)),
      BundleSigning.hex(BundleSigning.canonicalDigest(b)),
      "digest must be independent of zip entry order",
    )

    val tampered = sampleBundle("tampered.png")
    injectRawZipEntries(tampered, mapOf("classes/app.jar" to "EVIL".toByteArray()))
    assertFalse(
      BundleSigning.hex(BundleSigning.canonicalDigest(a)) ==
        BundleSigning.hex(BundleSigning.canonicalDigest(tampered)),
      "changing a covered entry must change the digest",
    )
  }

  @Test
  fun `sign then verify is trusted by signature`() {
    val keys = BundleSigning.generateKeyPair()
    val priv = File(workRoot, "key.b64").apply { writeText(keys.privateKeyB64) }
    val file = sampleBundle()

    val digest = BundleSigning.canonicalDigest(file)
    val sig =
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(priv.readText()), digest)
          ),
        producer = "Test CI",
      )
    assertEquals(1, BundleSigning.addSignature(file, sig))

    val trust = TrustStore(keys = listOf(TrustedKey("ci", keys.publicKeyB64, "Test CI")))
    val verdict = BundleVerifier.verify(file, trust)
    assertTrue(verdict is BundleVerifier.Verdict.Trusted, "expected trusted, got $verdict")
    val basis = (verdict as BundleVerifier.Verdict.Trusted).primary
    assertTrue(basis is BundleVerifier.Basis.Signature && basis.keyId == "ci")
  }

  @Test
  fun `tampering after signing fails verification`() {
    val keys = BundleSigning.generateKeyPair()
    val file = sampleBundle()
    val digest = BundleSigning.canonicalDigest(file)
    BundleSigning.addSignature(
      file,
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(keys.privateKeyB64), digest)
          ),
      ),
    )
    // Swap the executable jar after the signature was applied.
    injectRawZipEntries(file, mapOf("classes/app.jar" to "EVIL".toByteArray()))

    val trust = TrustStore(keys = listOf(TrustedKey("ci", keys.publicKeyB64)))
    assertTrue(BundleVerifier.verify(file, trust) is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `signature from an untrusted key is unverified`() {
    val signer = BundleSigning.generateKeyPair()
    val other = BundleSigning.generateKeyPair()
    val file = sampleBundle()
    val digest = BundleSigning.canonicalDigest(file)
    BundleSigning.addSignature(
      file,
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(signer.privateKeyB64), digest)
          ),
      ),
    )
    // Trust store pins a DIFFERENT public key under the same keyId.
    val trust = TrustStore(keys = listOf(TrustedKey("ci", other.publicKeyB64)))
    assertTrue(BundleVerifier.verify(file, trust) is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `unsigned bundle is unverified`() {
    val verdict = BundleVerifier.verify(sampleBundle(), TrustStore.EMPTY)
    assertTrue(verdict is BundleVerifier.Verdict.Unverified)
    assertEquals("unsigned bundle", (verdict as BundleVerifier.Verdict.Unverified).reason)
  }

  @Test
  fun `trusted branch origin verifies an unsigned catalog`() {
    val file = sampleBundle()
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val verdict =
      BundleVerifier.verify(
        file,
        trust,
        BundleVerifier.Origin("yschimke/compose-ai-tools", "design-artifacts/compose-m3"),
      )
    assertTrue(verdict is BundleVerifier.Verdict.Trusted)
    assertTrue((verdict as BundleVerifier.Verdict.Trusted).primary is BundleVerifier.Basis.Branch)
  }

  /**
   * Provenance is self-asserted data, so an identity glob + digest match is NOT proof of origin —
   * granting trust from it alone would let an attacker write a `signatures.json` with the
   * recomputed digest and a matching identity and have executable Compose re-rendered. With only
   * the OIDC identity trusted (no pinned key), the verdict must stay Unverified.
   */
  @Test
  fun `provenance identity alone does not grant trust`() {
    val keys = BundleSigning.generateKeyPair()
    val file = sampleBundle()
    val digest = BundleSigning.canonicalDigest(file)
    BundleSigning.addSignature(
      file,
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(keys.privateKeyB64), digest)
          ),
        provenance =
          BundleSigning.Provenance(
            type = "github-oidc",
            identity = "repo:yschimke/compose-ai-tools:ref:refs/heads/main",
          ),
      ),
    )
    // Only the OIDC identity is trusted; the signing key is NOT pinned.
    val trust = TrustStore(oidc = listOf(TrustedIdentity("repo:yschimke/compose-ai-tools:ref:*")))
    assertTrue(BundleVerifier.verify(file, trust) is BundleVerifier.Verdict.Unverified)
  }

  /**
   * Provenance is recorded as supplementary context only on a signature a pinned key already
   * verified — so it annotates, never expands, the trust decision.
   */
  @Test
  fun `provenance annotates a signature verified by a pinned key`() {
    val keys = BundleSigning.generateKeyPair()
    val file = sampleBundle()
    val digest = BundleSigning.canonicalDigest(file)
    BundleSigning.addSignature(
      file,
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(keys.privateKeyB64), digest)
          ),
        provenance =
          BundleSigning.Provenance(
            type = "github-oidc",
            identity = "repo:yschimke/compose-ai-tools:ref:refs/heads/main",
          ),
      ),
    )
    // Both the pinned key AND the OIDC identity are trusted.
    val trust =
      TrustStore(
        keys = listOf(TrustedKey("ci", keys.publicKeyB64)),
        oidc = listOf(TrustedIdentity("repo:yschimke/compose-ai-tools:ref:*")),
      )
    val verdict = BundleVerifier.verify(file, trust)
    assertTrue(verdict is BundleVerifier.Verdict.Trusted)
    val bases = (verdict as BundleVerifier.Verdict.Trusted).bases
    // The cryptographic signature is the trust basis; provenance rides along as context.
    assertTrue(bases.first() is BundleVerifier.Basis.Signature)
    assertTrue(bases.any { it is BundleVerifier.Basis.Provenance })
  }

  @Test
  fun `glob match anchors and supports wildcards`() {
    assertTrue(TrustStore.globMatch("design-artifacts/*", "design-artifacts/compose-m3"))
    assertFalse(TrustStore.globMatch("design-artifacts/*", "other/compose-m3"))
    assertTrue(TrustStore.globMatch("repo:org/repo:ref:*", "repo:org/repo:ref:refs/heads/main"))
    assertFalse(TrustStore.globMatch("exact", "exact-suffix"))
    assertTrue(TrustStore.globMatch("exact", "exact"))
  }
}
