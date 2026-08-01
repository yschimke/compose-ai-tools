package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.TrustStore
import ee.schimke.composeai.cli.serve.TrustedBranch
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundleSignCommandTest {
  @Test
  fun `keygen sign and verify command layer completes a local workflow`() {
    val root = Files.createTempDirectory("bundle-command").toFile()
    val key = root.resolve("producer.pem")
    val bundle = sampleBundle(root.resolve("sample.png"))
    val trust = root.resolve("trust.json")
    trust.writeText(
      TrustStore.encode(TrustStore(branches = listOf(TrustedBranch("owner/repo", "main"))))
    )

    val output = captureStdout {
      KeygenSubcommand(listOf("--output", key.path, "--key-id", "ci-key")).run()
      SignSubcommand(
          listOf(bundle.path, "--key", key.path, "--key-id", "ci-key", "--producer", "CI")
        )
        .run()
      VerifySubcommand(listOf(bundle.path, "--trust", trust.path, "--origin", "owner/repo@main"))
        .run()
    }

    assertTrue(key.isFile)
    assertEquals(1, BundleSigning.readSignatures(bundle)?.signatures?.size)
    assertNotNull(BundleSigning.parsePrivateKey(key.readText()))
    assertTrue(output.contains("signed ${bundle.path}"))
    assertTrue(output.contains("verdict: TRUSTED"))
    assertTrue(output.contains("trusted branch (owner/repo@main)"))
  }

  private fun sampleBundle(file: File): File {
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { out ->
      mapOf(
          "bundle.json" to
            """{"schemaVersion":7,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a","classpath":[],"modulePath":":app","producedBy":"test"}"""
              .toByteArray(),
          "previews/a.png" to png,
        )
        .forEach { (name, bytes) ->
          out.putNextEntry(ZipEntry(name))
          out.write(bytes)
          out.closeEntry()
        }
    }
    file.outputStream().use {
      it.write(png)
      it.write(zip.toByteArray())
    }
    return file
  }

  private fun captureStdout(block: () -> Unit): String {
    val original = System.out
    val bytes = ByteArrayOutputStream()
    try {
      System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
      block()
    } finally {
      System.setOut(original)
    }
    return bytes.toString(Charsets.UTF_8)
  }
}
