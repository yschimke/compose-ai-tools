package ee.schimke.composeai.viewer

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Test

/**
 * Offline coverage for the viewer's [CoordinateResolver] — resolves a bundle's detached `maven`
 * coordinates to local jars against a temp repo dir (no network), exercising the
 * pick-matching-candidate and warn-not-fail behaviour the cli resolver also guarantees.
 */
class CoordinateResolverTest {

  private val root = Files.createTempDirectory("viewer-coord-test-").toFile()
  private val warnings = mutableListOf<String>()

  @After
  fun cleanup() {
    root.deleteRecursively()
  }

  private fun maven(sha: String? = null) =
    ClasspathEntry.Maven(
      group = "com.example.foo",
      artifact = "widgets",
      version = "1.2.3",
      type = "jar",
      sha256 = sha,
    )

  private fun writeMavenJar(bytes: ByteArray): File {
    val f = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar")
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
    return f
  }

  private fun writeGradleJar(bytes: ByteArray): File {
    val f = File(root, "com.example.foo/widgets/1.2.3/deadbeef/widgets-1.2.3.jar")
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
    return f
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun resolve(vararg coords: ClasspathEntry.Maven): List<File> =
    CoordinateResolver.resolve(
      coords.toList(),
      warn = { warnings += it },
      repositoryRoots = listOf(root),
    )

  @Test
  fun `resolves and verifies a matching hash`() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val jar = writeMavenJar(bytes)

    val out = resolve(maven(sha = sha256(bytes)))

    assertThat(out.map { it.canonicalFile }).containsExactly(jar.canonicalFile)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `prefers a hash-matching candidate over an earlier mismatch`() {
    val wanted = byteArrayOf(4, 4, 4, 4)
    writeMavenJar(byteArrayOf(0, 0, 0)) // stale, found first
    val good = writeGradleJar(wanted) // exact match, found later

    val out = resolve(maven(sha = sha256(wanted)))

    assertThat(out.map { it.canonicalFile }).containsExactly(good.canonicalFile)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `missing artifact is dropped with a warning`() {
    val out = resolve(maven(sha = "a".repeat(64)))

    assertThat(out).isEmpty()
    assertThat(warnings.any { it.contains("could not resolve") }).isTrue()
  }

  @Test
  fun `hash mismatch still returns the jar but warns`() {
    val jar = writeMavenJar(byteArrayOf(1, 2, 3, 4))

    val out = resolve(maven(sha = "b".repeat(64)))

    assertThat(out.map { it.canonicalFile }).containsExactly(jar.canonicalFile)
    assertThat(warnings.any { it.contains("hash mismatch") }).isTrue()
  }
}
