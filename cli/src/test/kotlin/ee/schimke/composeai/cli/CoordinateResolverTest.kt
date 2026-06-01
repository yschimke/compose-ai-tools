package ee.schimke.composeai.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
  // Offline resolver that materializes AARs into the test cache (not the real ~/.cache).
  private val localCacheResolver =
    CoordinateResolver(
      repositoryRoots = listOf(root),
      warn = { warnings += it },
      networkEnabled = false,
      downloadCacheDir = cacheDir,
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

  /** A minimal `.aar`: a zip carrying a single `classes.jar` entry with [classesJar] bytes. */
  private fun makeAar(classesJar: ByteArray): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.putNextEntry(ZipEntry("classes.jar"))
      zip.write(classesJar)
      zip.closeEntry()
    }
    return baos.toByteArray()
  }

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
  fun `a prior download is read offline from the cache`() {
    // Simulates a later offline run: a jar cached by an earlier online fetch must resolve even with
    // the network disabled, since the cache is read independently of the network gate.
    val bytes = byteArrayOf(8, 8, 8)
    File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar").apply {
      parentFile.mkdirs()
      writeBytes(bytes)
    }
    val offline =
      CoordinateResolver(
        repositoryRoots = listOf(root),
        warn = { warnings += it },
        networkEnabled = false,
        downloadCacheDir = cacheDir,
      )

    val r = offline.resolve(maven(sha = sha256(bytes)))

    assertNotNullFile(r.file)
    assertTrue(r.verified)
    assertFalse(r.downloaded, "came from the cache, not a fresh download")
    assertTrue(warnings.isEmpty(), "a cached offline hit must not warn: $warnings")
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
  fun `aar coordinate is located as aar and its classes_jar extracted`() {
    // An Android coordinate (type=aar) is looked up as `<artifact>-<version>.aar`, then the
    // resolver hands back the AAR's extracted `classes.jar` — the only classpath-loadable form. The
    // bundle's sha256 is of that classes.jar (what the plugin hashes), so it still verifies.
    val classesBytes = byteArrayOf(5, 0, 5, 9)
    val aar = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    aar.parentFile.mkdirs()
    aar.writeBytes(makeAar(classesBytes))

    val coord =
      BundleReader.ClasspathEntry.Maven(
        group = "com.example.foo",
        artifact = "widgets",
        version = "1.2.3",
        type = "aar",
        sha256 = sha256(classesBytes),
      )
    val r = localCacheResolver.resolve(coord)

    assertNotNullFile(r.file)
    assertEquals("classes.jar", r.file?.name)
    assertEquals(classesBytes.toList(), r.file?.readBytes()?.toList())
    assertTrue(r.verified, "sha256 of the extracted classes.jar should verify: $warnings")
  }

  @Test
  fun `aar published dep recorded as jar still resolves via the aar fallback`() {
    // Older bundles (and the plugin before the type fix) recorded AARs as `type=jar`; the resolver
    // must still find `<artifact>-<version>.aar` when no `.jar` exists and extract its classes.
    val classesBytes = byteArrayOf(2, 2, 2, 2)
    val aar = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    aar.parentFile.mkdirs()
    aar.writeBytes(makeAar(classesBytes))

    // maven() defaults to type=jar — and there is no widgets-1.2.3.jar on disk, only the .aar.
    val r = localCacheResolver.resolve(maven(sha = sha256(classesBytes)))

    assertNotNullFile(r.file)
    assertEquals("classes.jar", r.file?.name)
    assertTrue(r.verified)
  }

  @Test
  fun `aar coordinate downloads as aar and extracts classes_jar`() {
    val classesBytes = byteArrayOf(1, 0, 0, 1, 7)
    val base = startRepo(body = makeAar(classesBytes))

    val coord =
      BundleReader.ClasspathEntry.Maven(
        group = "com.example.foo",
        artifact = "widgets",
        version = "1.2.3",
        type = "aar",
        sha256 = sha256(classesBytes),
      )
    val r = networkResolver(base).resolve(coord)

    assertNotNullFile(r.file)
    assertEquals("classes.jar", r.file?.name)
    assertTrue(r.downloaded)
    assertTrue(r.verified)
    // The raw aar is cached under the aar filename; the extracted jar lives under extracted/.
    assertTrue(File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar").isFile)
  }

  private fun assertNotNullFile(f: File?) =
    assertTrue(f != null && f.isFile, "expected a resolved jar")
}
