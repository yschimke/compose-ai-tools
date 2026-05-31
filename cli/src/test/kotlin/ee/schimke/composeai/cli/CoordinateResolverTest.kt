package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline coverage for [CoordinateResolver] — the Tier 3 (#1632) consumer of schema-v4 detached
 * coordinates. Exercises both local-repo layouts, the warn-not-fail miss path, and the
 * warn-but-return hash-mismatch contract, all against a temp repo dir (no network).
 */
class CoordinateResolverTest {

  private val root = Files.createTempDirectory("coord-resolver-test-").toFile()
  private val warnings = mutableListOf<String>()
  private val resolver =
    CoordinateResolver(repositoryRoots = listOf(root), warn = { warnings += it })

  @AfterTest
  fun cleanup() {
    root.deleteRecursively()
  }

  private fun maven(sha: String? = null) =
    BundleReader.ClasspathEntry.Maven(
      group = "com.example.foo",
      artifact = "widgets",
      version = "1.2.3",
      type = "jar",
      sha256 = sha,
    )

  /**
   * Write `<root>/<group as path>/<artifact>/<version>/<artifact>-<version>.jar` (Maven layout).
   */
  private fun writeMavenJar(bytes: ByteArray): File {
    val f = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar")
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
    return f
  }

  /** Write the Gradle modules-2 layout: `<group>/<artifact>/<version>/<hash>/<jar>`. */
  private fun writeGradleJar(bytes: ByteArray): File {
    val f = File(root, "com.example.foo/widgets/1.2.3/abcdef0123/widgets-1.2.3.jar")
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
    return f
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun `resolves from maven local layout and verifies a matching hash`() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val jar = writeMavenJar(bytes)

    val r = resolver.resolve(maven(sha = sha256(bytes)))

    assertEquals(jar.canonicalFile, r.file?.canonicalFile)
    assertTrue(r.verified)
    assertFalse(r.mismatch)
    assertTrue(warnings.isEmpty(), "a verified resolution must not warn: $warnings")
  }

  @Test
  fun `resolves from gradle modules-2 layout`() {
    val bytes = byteArrayOf(9, 8, 7)
    val jar = writeGradleJar(bytes)

    val r = resolver.resolve(maven(sha = sha256(bytes)))

    assertEquals(jar.canonicalFile, r.file?.canonicalFile)
    assertTrue(r.verified)
  }

  @Test
  fun `missing artifact returns null and warns, never throws`() {
    val r = resolver.resolve(maven(sha = "a".repeat(64)))

    assertNull(r.file)
    assertFalse(r.verified)
    assertTrue(warnings.any { it.contains("could not resolve") })
  }

  @Test
  fun `hash mismatch still returns the jar but warns`() {
    writeMavenJar(byteArrayOf(1, 2, 3, 4))

    // Coordinate claims a different hash than the bytes on disk.
    val r = resolver.resolve(maven(sha = "b".repeat(64)))

    assertNotNullFile(r.file)
    assertFalse(r.verified)
    assertTrue(r.mismatch)
    assertTrue(
      warnings.any { it.contains("hash mismatch") },
      "expected a mismatch warning: $warnings",
    )
  }

  @Test
  fun `null hash resolves as unverifiable without warning`() {
    writeMavenJar(byteArrayOf(5, 5, 5))

    val r = resolver.resolve(maven(sha = null))

    assertNotNullFile(r.file)
    assertFalse(r.verified)
    assertFalse(r.mismatch)
    assertTrue(warnings.isEmpty(), "an unverifiable (no-hash) resolution must not warn: $warnings")
  }

  private fun assertNotNullFile(f: File?) =
    assertTrue(f != null && f.isFile, "expected a resolved jar")
}
