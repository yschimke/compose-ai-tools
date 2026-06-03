package ee.schimke.composeai.viewer

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Test

/**
 * Coverage for the viewer's [CoordinateResolver]. Local-repo cases run with network disabled
 * (hermetic, temp repo dir); the network cases use a throwaway loopback [HttpServer] as a fake
 * remote Maven repo — no real network. Mirrors the cli resolver's behaviour:
 * pick-matching-candidate, download-on-miss + cache, offline cache read, jar/aar by type,
 * warn-not-fail.
 */
class CoordinateResolverTest {

  private val root = Files.createTempDirectory("viewer-coord-test-").toFile()
  private val cacheDir = Files.createTempDirectory("viewer-coord-cache-").toFile()
  private val warnings = mutableListOf<String>()
  private var server: HttpServer? = null

  @After
  fun cleanup() {
    server?.stop(0)
    root.deleteRecursively()
    cacheDir.deleteRecursively()
  }

  /** A loopback Maven repo that serves [body] for any path; returns its base URL. */
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

  /**
   * Local-only resolve: network off so a miss stays hermetic. `CoordinateResolver.resolve` returns
   * `okio.Path`s; the helper maps back to `File` so the assertions stay file-shaped.
   */
  private fun resolve(vararg coords: ClasspathEntry.Maven): List<File> =
    CoordinateResolver.resolve(
        coords.toList(),
        warn = { warnings += it },
        repositoryRoots = listOf(root.path.toPath()),
        networkEnabled = false,
        downloadCacheDir = cacheDir.path.toPath(),
      )
      .map { it.toFile() }

  /** Network-enabled resolve pointed only at [base], caching into [cacheDir]. */
  private fun resolveNetwork(base: String, vararg coords: ClasspathEntry.Maven): List<File> =
    CoordinateResolver.resolve(
        coords.toList(),
        warn = { warnings += it },
        repositoryRoots = listOf(root.path.toPath()),
        networkEnabled = true,
        remoteRepositories = listOf(base),
        downloadCacheDir = cacheDir.path.toPath(),
      )
      .map { it.toFile() }

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

  @Test
  fun `downloads from a remote repo on a local miss, and caches it`() {
    val bytes = byteArrayOf(7, 7, 7, 7)
    val base = startRepo(body = bytes)

    val out = resolveNetwork(base, maven(sha = sha256(bytes)))

    val cached = File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar")
    assertThat(out.map { it.canonicalFile }).containsExactly(cached.canonicalFile)
    assertThat(cached.readBytes().toList()).isEqualTo(bytes.toList())
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `a prior download is read offline from the cache`() {
    val bytes = byteArrayOf(8, 8, 8)
    File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar").apply {
      parentFile.mkdirs()
      writeBytes(bytes)
    }

    // Network off — must still resolve from the cache populated by an earlier online run.
    val out = resolve(maven(sha = sha256(bytes)))

    assertThat(out).hasSize(1)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `aar coordinate is located as aar and its classes_jar extracted`() {
    // type=aar is looked up as `<artifact>-<version>.aar`; the resolver returns the AAR's extracted
    // `classes.jar` (the only loadable form), which is what the bundle's sha256 is computed over.
    val classesBytes = byteArrayOf(5, 0, 5, 9)
    val aar = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    aar.parentFile.mkdirs()
    aar.writeBytes(makeAar(classesBytes))

    val coord =
      ClasspathEntry.Maven(
        group = "com.example.foo",
        artifact = "widgets",
        version = "1.2.3",
        type = "aar",
        sha256 = sha256(classesBytes),
      )
    val out = resolve(coord)

    assertThat(out).hasSize(1)
    assertThat(out.single().name).isEqualTo("classes.jar")
    assertThat(out.single().readBytes().toList()).isEqualTo(classesBytes.toList())
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `aar published dep recorded as jar still resolves via the aar fallback`() {
    // Older bundles recorded AARs as type=jar; the resolver must still find the `.aar` and extract.
    val classesBytes = byteArrayOf(2, 2, 2, 2)
    val aar = File(root, "com/example/foo/widgets/1.2.3/widgets-1.2.3.aar")
    aar.parentFile.mkdirs()
    aar.writeBytes(makeAar(classesBytes))

    // maven() defaults to type=jar, and only the .aar exists on disk.
    val out = resolve(maven(sha = sha256(classesBytes)))

    assertThat(out).hasSize(1)
    assertThat(out.single().name).isEqualTo("classes.jar")
  }

  @Test
  fun `a failed download keeps a stale cached jar for the fallback`() {
    // The cache holds a copy whose bytes don't match the bundle's hash, and the remote 404s. The
    // failed fetch must not delete the cached file: warn-never-fail returns it as the best jar.
    val cached =
      File(cacheDir, "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(9, 9, 9))
      }
    val base = startRepo(status = 404)

    val out = resolveNetwork(base, maven(sha = "c".repeat(64)))

    assertThat(out.map { it.canonicalFile }).containsExactly(cached.canonicalFile)
    assertThat(cached.isFile).isTrue()
    assertThat(warnings.any { it.contains("hash mismatch") }).isTrue()
  }

  @Test
  fun `remote 404 is dropped with a warning, never throws`() {
    val base = startRepo(status = 404)

    val out = resolveNetwork(base, maven(sha = "a".repeat(64)))

    assertThat(out).isEmpty()
    assertThat(warnings.any { it.contains("could not resolve") }).isTrue()
  }

  @Test
  fun `resolves against an injected FakeFileSystem with no disk access`() {
    // The whole point of injecting the FileSystem: a local-repo resolve runs entirely in memory,
    // touching no real disk. Lay out a Maven-style repo inside a FakeFileSystem and resolve from
    // it.
    val fakeFs = FakeFileSystem()
    val repoRoot = "/repo".toPath()
    val cacheDir = "/cache".toPath()
    val bytes = byteArrayOf(3, 1, 4, 1, 5, 9)
    val jar = repoRoot / "com/example/foo/widgets/1.2.3/widgets-1.2.3.jar"
    fakeFs.createDirectories(jar.parent!!)
    fakeFs.write(jar) { write(bytes.toByteString()) }

    val out =
      CoordinateResolver.resolve(
        listOf(maven(sha = sha256(bytes))),
        warn = { warnings += it },
        repositoryRoots = listOf(repoRoot),
        networkEnabled = false,
        downloadCacheDir = cacheDir,
        fileSystem = fakeFs,
      )

    assertThat(out).containsExactly(jar)
    assertThat(warnings).isEmpty()
    // Nothing leaked onto the real filesystem — the fake recorded every handle and none stayed
    // open.
    fakeFs.checkNoOpenFiles()
  }
}
