package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [CoordinateResolver] — the Tier 3 (#1632) consumer of schema-v4 detached
 * coordinates. The local-repo cases run with network disabled (hermetic, temp repo dir); the
 * network cases use a throwaway loopback [HttpServer] as a fake remote Maven repo — no real
 * network.
 */
class CoordinateResolverTest {

  private val root = Files.createTempDirectory("coord-resolver-test-").toFile()
  private val cacheDir = Files.createTempDirectory("coord-resolver-cache-").toFile()
  private val warnings = mutableListOf<String>()
  // Local-only resolver for the offline cases: network off so a miss doesn't reach out.
  private val resolver =
    CoordinateResolver(
      repositoryRoots = listOf(root),
      warn = { warnings += it },
      networkEnabled = false,
    )
  private var server: HttpServer? = null

  @AfterTest
  fun cleanup() {
    server?.stop(0)
    root.deleteRecursively()
    cacheDir.deleteRecursively()
  }

  /** A loopback Maven repo that serves [body] for any request path; returns its base URL. */
  private fun startRepo(status: Int = 200, body: ByteArray = byteArrayOf(1)): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange ->
      val len = if (body.isEmpty() || status >= 400) -1L else body.size.toLong()
      exchange.sendResponseHeaders(status, len)
      exchange.responseBody.use { if (status < 400 && body.isNotEmpty()) it.write(body) }
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  /** A network-enabled resolver pointed only at [base] (no real repos), caching into [cacheDir]. */
  private fun networkResolver(base: String) =
    CoordinateResolver(
      repositoryRoots = listOf(root),
      warn = { warnings += it },
      networkEnabled = true,
      remoteRepositories = listOf(base),
      downloadCacheDir = cacheDir,
    )

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
  fun `prefers a hash-matching candidate over an earlier mismatch`() {
    // A stale Maven-layout copy (wrong bytes) plus a Gradle-cache copy with the bundle's exact
    // bytes. The resolver must skip the first-found mismatch and pick the matching one.
    val wanted = byteArrayOf(4, 4, 4, 4)
    writeMavenJar(byteArrayOf(0, 0, 0)) // stale, found first
    val good = writeGradleJar(wanted) // exact match, found later

    val r = resolver.resolve(maven(sha = sha256(wanted)))

    assertEquals(good.canonicalFile, r.file?.canonicalFile)
    assertTrue(r.verified)
    assertFalse(r.mismatch)
    assertTrue(warnings.isEmpty(), "picking the matching candidate must not warn: $warnings")
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

  @Test
  fun `downloads from a remote repo when local misses, and caches it`() {
    val bytes = byteArrayOf(7, 7, 7, 7)
    val base = startRepo(body = bytes)

    val r = networkResolver(base).resolve(maven(sha = sha256(bytes)))

    assertNotNullFile(r.file)
    assertTrue(r.downloaded, "should have come from the remote repo")
    assertTrue(r.verified)
    assertFalse(r.mismatch)
    // Cached in Maven layout for next time.
    val cached = File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar")
    assertTrue(cached.isFile)
    assertEquals(bytes.toList(), cached.readBytes().toList())
  }

  @Test
  fun `served cache is reused without re-downloading`() {
    // Pre-seed the cache; point the resolver at a server that would 404 if hit.
    val bytes = byteArrayOf(3, 1, 4)
    File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar").apply {
      parentFile.mkdirs()
      writeBytes(bytes)
    }
    val base = startRepo(status = 404)

    val r = networkResolver(base).resolve(maven(sha = sha256(bytes)))

    assertNotNullFile(r.file)
    assertTrue(r.verified)
  }

  @Test
  fun `remote 404 returns null and warns, never throws`() {
    val base = startRepo(status = 404)

    val r = networkResolver(base).resolve(maven(sha = "a".repeat(64)))

    assertNull(r.file)
    assertTrue(warnings.any { it.contains("could not resolve") && it.contains("remote") })
  }

  @Test
  fun `local hash-match wins without any network call`() {
    // A local match exists; the server would 404 if the resolver wrongly reached for the network.
    val bytes = byteArrayOf(2, 2, 2)
    val jar = writeMavenJar(bytes)
    val base = startRepo(status = 404)

    val r = networkResolver(base).resolve(maven(sha = sha256(bytes)))

    assertEquals(jar.canonicalFile, r.file?.canonicalFile)
    assertFalse(r.downloaded)
    assertTrue(r.verified)
    assertTrue(warnings.isEmpty(), "a local hash-match must not warn or hit the network: $warnings")
  }

  @Test
  fun `aar coordinate resolves by its type extension, not jar`() {
    // An Android coordinate (type=aar) must be looked up / downloaded as
    // `<artifact>-<version>.aar`.
    val bytes = byteArrayOf(5, 0, 5)
    val aar = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    aar.parentFile.mkdirs()
    aar.writeBytes(bytes)

    val coord =
      BundleReader.ClasspathEntry.Maven(
        group = "com.example.foo",
        artifact = "widgets",
        version = "1.2.3",
        type = "aar",
        sha256 = sha256(bytes),
      )
    val r = resolver.resolve(coord)

    assertEquals(aar.canonicalFile, r.file?.canonicalFile)
    assertTrue(r.verified)
  }

  @Test
  fun `aar coordinate downloads with the aar extension`() {
    val bytes = byteArrayOf(1, 0, 0, 1)
    val base = startRepo(body = bytes)

    val coord =
      BundleReader.ClasspathEntry.Maven(
        group = "com.example.foo",
        artifact = "widgets",
        version = "1.2.3",
        type = "aar",
        sha256 = sha256(bytes),
      )
    val r = networkResolver(base).resolve(coord)

    assertNotNullFile(r.file)
    assertTrue(r.downloaded)
    // Cached under the aar filename, not jar.
    val cached = File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    assertTrue(cached.isFile)
  }

  private fun assertNotNullFile(f: File?) =
    assertTrue(f != null && f.isFile, "expected a resolved jar")
}
