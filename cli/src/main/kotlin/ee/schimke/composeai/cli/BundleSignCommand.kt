package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.BundleVerifier
import ee.schimke.composeai.cli.serve.TrustStore
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview bundle <keygen|sign|verify>` — the producer-trust side of portable bundles.
 *
 * A public preview server will re-render a bundle's executable Compose only when it can attribute
 * the bundle to a trusted producer. These three subcommands are how a producer creates that
 * attribution and how anyone checks it:
 *
 * - **keygen** — mint an Ed25519 keypair. The private key signs bundles; the public key (printed as
 *   a ready-to-paste trust-store entry) goes into the server's `trust/producers.json`.
 * - **sign** — append a detached signature over the bundle's canonical digest (see
 *   [BundleSigning]). Idempotent per `--key-id`; multiple producers can each add their own
 *   signature.
 * - **verify** — check a bundle against a trust store and print the verdict (which producer, which
 *   basis — signature / branch / provenance — or why it's unverified).
 */
internal class KeygenSubcommand(private val args: List<String>) {
  fun run() {
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    val explicitId = args.flagValue("--key-id")
    val pair = BundleSigning.generateKeyPair()
    val keyId =
      explicitId
        ?: "key-${BundleSigning.hex(BundleSigning.sha256(BundleSigning.decodeBase64(pair.publicKeyB64))).take(8)}"

    val privatePem =
      "-----BEGIN PRIVATE KEY-----\n" +
        pair.privateKeyB64.chunked(64).joinToString("\n") +
        "\n-----END PRIVATE KEY-----\n"

    if (out != null) {
      val f = File(out).absoluteFile
      f.parentFile?.mkdirs()
      f.writeText(privatePem)
      // Best-effort lock-down of the private key file.
      runCatching {
        f.setReadable(false, false)
        f.setReadable(true, true)
      }
      println("wrote private key → ${f.path}")
    } else {
      println(privatePem.trimEnd())
    }

    println()
    println("public key (add to your trust store's \"keys\"):")
    println(
      """
      {
        "keyId": "$keyId",
        "name": "TODO: producer name",
        "publicKey": "${pair.publicKeyB64}"
      }
      """
        .trimIndent()
    )
    println()
    println(
      "sign with:  compose-preview bundle sign <bundle.png> --key <private-key> --key-id $keyId"
    )
  }
}

internal class SignSubcommand(private val args: List<String>) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val keyArg = args.flagValue("--key")
    val keyId = args.flagValue("--key-id")
    val producer = args.flagValue("--producer")
    val provIdentity = args.flagValue("--provenance-identity")
    val provType = args.flagValue("--provenance-type") ?: "github-oidc"
    if (path == null || keyArg == null || keyId == null) {
      System.err.println(
        "Usage: compose-preview bundle sign <bundle.png> --key <private-key-file> --key-id <id> " +
          "[--producer <name>] [--provenance-identity <id> [--provenance-type github-oidc|sigstore]]"
      )
      exitProcess(64)
    }
    val bundle = resolveLocalBundle(path)
    val keyFile = File(keyArg)
    if (!keyFile.isFile) {
      System.err.println("bundle sign: private key file not found: ${keyFile.path}")
      exitProcess(1)
    }
    val privateKey =
      try {
        BundleSigning.parsePrivateKey(keyFile.readText())
      } catch (e: Exception) {
        System.err.println("bundle sign: could not parse Ed25519 private key (${e.message})")
        exitProcess(1)
      }

    val digest = BundleSigning.canonicalDigest(bundle)
    val sigBytes = BundleSigning.signEd25519(privateKey, digest)
    val signature =
      BundleSigning.Signature(
        keyId = keyId,
        algorithm = BundleSigning.ALG_ED25519,
        digest = BundleSigning.hex(digest),
        signature = BundleSigning.base64(sigBytes),
        producer = producer,
        provenance = provIdentity?.let { BundleSigning.Provenance(type = provType, identity = it) },
      )
    val count = BundleSigning.addSignature(bundle, signature)
    println("signed ${bundle.path}")
    println("  keyId:   $keyId")
    println("  digest:  ${BundleSigning.hex(digest)}")
    println("  signatures now on bundle: $count")
  }
}

internal class VerifySubcommand(private val args: List<String>) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val trustArg = args.flagValue("--trust")
    val originArg = args.flagValue("--origin") // repo@branch
    if (path == null) {
      System.err.println(
        "Usage: compose-preview bundle verify <bundle.png | URL> [--trust <store.json>] " +
          "[--origin <repo@branch>]"
      )
      exitProcess(64)
    }
    val bundle =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val trust =
      if (trustArg != null) {
        val f = File(trustArg)
        if (!f.isFile) {
          System.err.println("bundle verify: trust store not found: ${f.path}")
          exitProcess(1)
        }
        try {
          TrustStore.load(f)
        } catch (e: Exception) {
          System.err.println("bundle verify: could not parse trust store (${e.message})")
          exitProcess(1)
        }
      } else TrustStore.EMPTY

    val origin = originArg?.let {
      val at = it.indexOf('@')
      if (at < 0) BundleVerifier.Origin(it, "*")
      else BundleVerifier.Origin(it.substring(0, at), it.substring(at + 1))
    }

    val digest = BundleSigning.hex(BundleSigning.canonicalDigest(bundle))
    val sigs = BundleSigning.readSignatures(bundle)?.signatures.orEmpty()
    println("bundle:  ${bundle.path}")
    println("digest:  $digest")
    println("signatures present: ${sigs.size}${if (sigs.isEmpty()) " (unsigned)" else ""}")
    for (s in sigs) {
      val provNote = s.provenance?.let { " provenance=${it.type}:${it.identity}" } ?: ""
      println("  - keyId=${s.keyId} alg=${s.algorithm}$provNote")
    }

    when (val verdict = BundleVerifier.verify(bundle, trust, origin)) {
      is BundleVerifier.Verdict.Trusted -> {
        println("verdict: TRUSTED")
        for (b in verdict.bases) println("  via ${describe(b)}")
      }
      is BundleVerifier.Verdict.Unverified -> {
        println("verdict: UNVERIFIED (${verdict.reason})")
        // Non-zero exit so CI / scripts can gate on it.
        exitProcess(3)
      }
    }
  }

  private fun describe(b: BundleVerifier.Basis): String =
    when (b) {
      is BundleVerifier.Basis.Signature ->
        "signature (keyId=${b.keyId}${b.producer?.let { ", $it" } ?: ""})"
      is BundleVerifier.Basis.Branch -> "trusted branch (${b.repo}@${b.branch})"
      is BundleVerifier.Basis.Provenance -> "provenance (${b.type}:${b.identity})"
    }
}

/**
 * Resolve a bundle path for an **in-place** operation (sign rewrites the file), refusing a URL: a
 * downloaded bundle is a delete-on-exit temp file, so signing it in place would vanish on exit.
 */
private fun resolveLocalBundle(path: String): File {
  if (BundleSource.looksLikeUrl(path)) {
    System.err.println("bundle sign: the input is a URL; download it to a local file first.")
    exitProcess(64)
  }
  val f = File(path)
  if (!f.isFile) {
    System.err.println("bundle sign: bundle not found: ${f.path}")
    exitProcess(1)
  }
  return f
}
