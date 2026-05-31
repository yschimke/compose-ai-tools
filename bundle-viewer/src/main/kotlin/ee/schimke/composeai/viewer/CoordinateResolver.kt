package ee.schimke.composeai.viewer

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/**
 * Resolves a bundle's detached `maven` coordinates ([ClasspathEntry.Maven]) to jar files so the
 * viewer can add them to a bundle's child classloader. Consumer side of schema v4's
 * content-addressing: a coordinate names *what* dependency a preview needs, and this finds the
 * bytes from whatever the machine already has — or, failing that, downloads them.
 *
 * Mirrors `:cli`'s `CoordinateResolver` (duplicated rather than depended on, same convention as the
 * viewer's copy of `extractZipBytes`, so the viewer's module graph stays minimal):
 * - **Local repos + download cache** — `~/.m2/repository` (Maven layout), `~/.gradle/.../modules-2`
 *   (Gradle cache), and our own download cache, overridable via `maven.repo.local` /
 *   `GRADLE_USER_HOME` / `composeai.bundle.cacheDir`. When several local copies exist for one GAV,
 *   the v4 `sha256` picks the matching one. The cache is read regardless of the network flag.
 * - **Remote repos** (when [networkEnabled]) — Maven Central + Google Maven by default; hit only on
 *   a local miss, and a download is cached for next time. Off via `composeai.bundle.offline=true` /
 *   `COMPOSE_PREVIEW_OFFLINE=1`.
 * - **Warn, never fail**: a miss or hash mismatch logs and still returns the best jar (or none), so
 *   a preview renders with an almost-compatible dep rather than not at all.
 */
internal object CoordinateResolver {

  /** Resolve [coords] to jars (misses dropped), warning on misses/mismatches. */
  fun resolve(
    coords: List<ClasspathEntry.Maven>,
    warn: (String) -> Unit = { System.err.println("compose-preview-viewer: $it") },
    repositoryRoots: List<File> = defaultRepositoryRoots(),
    networkEnabled: Boolean = defaultNetworkEnabled(),
    remoteRepositories: List<String> = DEFAULT_REMOTE_REPOSITORIES,
    downloadCacheDir: File = defaultDownloadCacheDir(),
  ): List<File> = coords.mapNotNull {
    resolveOne(it, warn, repositoryRoots, networkEnabled, remoteRepositories, downloadCacheDir)
  }

  private fun resolveOne(
    coord: ClasspathEntry.Maven,
    warn: (String) -> Unit,
    roots: List<File>,
    networkEnabled: Boolean,
    remoteRepositories: List<String>,
    downloadCacheDir: File,
  ): File? {
    // Local repos AND our download cache — a jar fetched in an earlier online run must resolve
    // offline too (the network gate only governs *new* fetches, not reading what we already have).
    val candidates = locate(coord, roots + downloadCacheDir)
    val expected = coord.sha256

    if (expected != null) {
      candidates
        .firstOrNull { sha256Hex(it).equals(expected, ignoreCase = true) }
        ?.let {
          return it
        }
    } else if (candidates.isNotEmpty()) {
      return candidates.first()
    }

    // Local couldn't satisfy it; try the network before settling.
    if (networkEnabled) {
      val fetched = download(coord, remoteRepositories, downloadCacheDir)
      if (fetched != null) {
        if (expected == null || sha256Hex(fetched).equals(expected, ignoreCase = true))
          return fetched
        warn(
          "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — downloaded copy " +
            "does not match the bundle's $expected; using it anyway."
        )
        return fetched
      }
    }

    if (candidates.isNotEmpty()) {
      val fallback = candidates.first()
      warn(
        "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — no local or remote " +
          "copy matched; using ${fallback.name} anyway, the preview may differ slightly."
      )
      return fallback
    }
    warn(
      "could not resolve ${coord.group}:${coord.artifact}:${coord.version}" +
        "${if (networkEnabled) " from any local or remote repository" else " from a local repository"};" +
        " the preview may fail if it needs this dependency."
    )
    return null
  }

  private fun locate(coord: ClasspathEntry.Maven, roots: List<File>): List<File> {
    val jarName = artifactFileName(coord)
    val found = mutableListOf<File>()
    for (root in roots) {
      if (!root.isDirectory) continue
      val mavenPath = File(root, mavenRelativePath(coord))
      if (mavenPath.isFile) found += mavenPath
      val gradleVersionDir = File(root, "${coord.group}/${coord.artifact}/${coord.version}")
      if (gradleVersionDir.isDirectory) {
        gradleVersionDir
          .listFiles()
          ?.asSequence()
          ?.filter { it.isDirectory }
          ?.mapNotNull { hashDir -> File(hashDir, jarName).takeIf { it.isFile } }
          ?.let { found += it }
      }
    }
    return found
  }

  /**
   * Download [coord]'s artifact from the first [remoteRepositories] base that serves it, into
   * [downloadCacheDir] (Maven layout); return the cached file or null (never throws). A cached copy
   * isn't short-circuited here — [locate] already searches the cache, so reaching this means we
   * want fresh bytes.
   */
  private fun download(
    coord: ClasspathEntry.Maven,
    remoteRepositories: List<String>,
    downloadCacheDir: File,
  ): File? {
    val rel = mavenRelativePath(coord)
    val dest = File(downloadCacheDir, rel)
    for (base in remoteRepositories) {
      if (fetchTo(base.trimEnd('/') + "/" + rel, dest)) return dest
    }
    return null
  }

  /**
   * GET [url] into [dest] (parent dirs created); true only on a 2xx with a non-empty body. The
   * bytes land in a sibling `.part` temp file first and are moved into [dest] only on success, so a
   * failed or empty fetch never clobbers an existing cached copy — which may be the
   * stale-but-usable jar that [resolveOne]'s warn-never-fail fallback then returns.
   */
  private fun fetchTo(url: String, dest: File): Boolean {
    val parent = dest.parentFile
    parent?.mkdirs()
    val tmp = File.createTempFile(dest.name, ".part", parent)
    return try {
      val ok =
        HttpClient(OkHttp).use { client ->
          runBlocking {
            client.prepareGet(url).execute { response ->
              if (response.status.isSuccess()) {
                tmp.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
                true
              } else {
                false
              }
            }
          }
        }
      if (ok && tmp.length() > 0) {
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    } finally {
      tmp.delete()
    }
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Maven Central + Google Maven, the two repos that serve almost every Compose/AndroidX dep. */
  val DEFAULT_REMOTE_REPOSITORIES: List<String> =
    listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

  /** `<artifact>-<version>.<type>` — `type` is `jar` (desktop) or `aar` (Android). */
  private fun artifactFileName(coord: ClasspathEntry.Maven): String =
    "${coord.artifact}-${coord.version}.${coord.type.ifBlank { "jar" }}"

  /** `<group as path>/<artifact>/<version>/<artifact>-<version>.<type>`. */
  private fun mavenRelativePath(coord: ClasspathEntry.Maven): String =
    coord.group.replace('.', '/') + "/${coord.artifact}/${coord.version}/${artifactFileName(coord)}"

  private fun defaultRepositoryRoots(): List<File> {
    val home = System.getProperty("user.home")?.let(::File)
    val roots = mutableListOf<File>()
    System.getProperty("maven.repo.local")?.let { roots += File(it) }
    if (home != null) roots += File(home, ".m2/repository")
    val gradleHome =
      System.getenv("GRADLE_USER_HOME")?.let(::File) ?: home?.let { File(it, ".gradle") }
    if (gradleHome != null) roots += File(gradleHome, "caches/modules-2/files-2.1")
    return roots
  }

  private fun defaultNetworkEnabled(): Boolean {
    if (System.getProperty("composeai.bundle.offline").toBoolean()) return false
    if (System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1") return false
    return true
  }

  private fun defaultDownloadCacheDir(): File {
    System.getProperty("composeai.bundle.cacheDir")?.let {
      return File(it)
    }
    val home = System.getProperty("user.home")?.let(::File) ?: File(".")
    return File(home, ".cache/compose-preview/bundle-deps")
  }
}
