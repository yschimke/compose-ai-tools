package ee.schimke.composeai.viewer

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.openZip

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
 *
 * All filesystem access funnels through `:common-io`'s Okio helpers; jar paths are [okio.Path]. The
 * one place a `java.io.File` is still required is `java.util.zip.ZipFile` for `.aar` extraction —
 * bridged at that call with [Path.toFile].
 */
internal object CoordinateResolver {

  /** Resolve [coords] to jars (misses dropped), warning on misses/mismatches. */
  fun resolve(
    coords: List<ClasspathEntry.Maven>,
    warn: (String) -> Unit = { System.err.println("compose-preview-viewer: $it") },
    repositoryRoots: List<Path> = defaultRepositoryRoots(),
    networkEnabled: Boolean = defaultNetworkEnabled(),
    remoteRepositories: List<String> = DEFAULT_REMOTE_REPOSITORIES,
    downloadCacheDir: Path = defaultDownloadCacheDir(),
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Path> = coords.mapNotNull {
    resolveOne(
      it,
      warn,
      repositoryRoots,
      networkEnabled,
      remoteRepositories,
      downloadCacheDir,
      fileSystem,
    )
  }

  private fun resolveOne(
    coord: ClasspathEntry.Maven,
    warn: (String) -> Unit,
    roots: List<Path>,
    networkEnabled: Boolean,
    remoteRepositories: List<String>,
    downloadCacheDir: Path,
    fileSystem: FileSystem,
  ): Path? {
    // Local repos AND our download cache — a jar fetched in an earlier online run must resolve
    // offline too (the network gate only governs *new* fetches, not reading what we already have).
    val candidates = locate(coord, roots + downloadCacheDir, downloadCacheDir, fileSystem)
    val expected = coord.sha256

    if (expected != null) {
      candidates
        .firstOrNull { sha256Hex(it, fileSystem).equals(expected, ignoreCase = true) }
        ?.let {
          return it
        }
    } else if (candidates.isNotEmpty()) {
      return candidates.first()
    }

    // Local couldn't satisfy it; try the network before settling.
    if (networkEnabled) {
      val fetched = download(coord, remoteRepositories, downloadCacheDir, fileSystem)
      if (fetched != null) {
        if (expected == null || sha256Hex(fetched, fileSystem).equals(expected, ignoreCase = true))
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

  private fun locate(
    coord: ClasspathEntry.Maven,
    roots: List<Path>,
    downloadCacheDir: Path,
    fileSystem: FileSystem,
  ): List<Path> {
    val found = mutableListOf<Path>()
    for (root in roots) {
      if (fileSystem.metadataOrNull(root)?.isDirectory != true) continue
      // Coordinate's recorded type first, then `.aar` (Android deps recorded as `jar` by an older
      // bundle, or whose `.jar` isn't published). [materialize] turns an `.aar` into its
      // classes.jar.
      for (fileName in candidateFileNames(coord)) {
        val mavenPath =
          root / "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}/$fileName"
        if (fileSystem.metadataOrNull(mavenPath)?.isRegularFile == true) found += mavenPath
        val gradleVersionDir = root / "${coord.group}/${coord.artifact}/${coord.version}"
        if (fileSystem.metadataOrNull(gradleVersionDir)?.isDirectory == true) {
          fileSystem
            .list(gradleVersionDir)
            .asSequence()
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .mapNotNull { hashDir ->
              (hashDir / fileName).takeIf { fileSystem.metadataOrNull(it)?.isRegularFile == true }
            }
            .let { found += it }
        }
      }
    }
    // Materialize before returning so the hash check (and the classpath) sees real jars — an `.aar`
    // isn't loadable and the bundle's `sha256` is of the extracted `classes.jar`.
    return found.mapNotNull { materialize(it, downloadCacheDir, fileSystem) }
  }

  /**
   * An `.aar` isn't classpath-loadable, so extract its `classes.jar` to a stable cache path under
   * [downloadCacheDir] and return that; a `.jar` passes through. Returns null for a resource-only
   * `.aar` (no `classes.jar`) or any extraction error — the caller then treats it as a miss.
   */
  private fun materialize(file: Path, downloadCacheDir: Path, fileSystem: FileSystem): Path? {
    if (!file.name.endsWith(".aar", ignoreCase = true)) return file
    val canonical = fileSystem.canonicalize(file)
    val dest =
      downloadCacheDir /
        "extracted/${canonical.toString().hashCode().toUInt().toString(16)}/classes.jar"
    if ((fileSystem.metadataOrNull(dest)?.size ?: 0L) > 0L) return dest
    return try {
      // Read the `.aar` (a zip) as an Okio FileSystem — no `java.util.zip.ZipFile` / `java.io.File`
      // boundary. Entries are addressed relative to the zip root.
      val aar = fileSystem.openZip(file)
      val entry = "classes.jar".toPath()
      if (!aar.exists(entry)) return null
      fileSystem.createDirectories(dest.parent!!)
      aar.source(entry).use { source -> fileSystem.sink(dest).buffer().use { it.writeAll(source) } }
      dest.takeIf { (fileSystem.metadataOrNull(it)?.size ?: 0L) > 0L }
    } catch (_: Exception) {
      null
    }
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
    downloadCacheDir: Path,
    fileSystem: FileSystem,
  ): Path? {
    for (fileName in candidateFileNames(coord)) {
      val rel = "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}/$fileName"
      val dest = downloadCacheDir / rel
      for (base in remoteRepositories) {
        if (fetchTo(base.trimEnd('/') + "/" + rel, dest, fileSystem))
          return materialize(dest, downloadCacheDir, fileSystem)
      }
    }
    return null
  }

  /**
   * GET [url] into [dest] (parent dirs created); true only on a 2xx with a non-empty body. The
   * bytes land in a sibling `.part` temp file first and are moved into [dest] only on success, so a
   * failed or empty fetch never clobbers an existing cached copy — which may be the
   * stale-but-usable jar that [resolveOne]'s warn-never-fail fallback then returns.
   */
  private fun fetchTo(url: String, dest: Path, fileSystem: FileSystem): Boolean {
    val parent = dest.parent
    val tmp = (parent ?: ".".toPath()) / "${dest.name}.${UUID.randomUUID()}.part"
    return try {
      // ktor's HTTP client is suspend; `runBlocking` keeps this resolver synchronous (the viewer's
      // bundle load runs off the UI thread already).
      val ok =
        HttpClient(OkHttp).use { client ->
          runBlocking {
            client.prepareGet(url).execute { response ->
              if (response.status.isSuccess()) {
                if (parent != null) fileSystem.createDirectories(parent)
                fileSystem.sink(tmp).buffer().use { sink ->
                  response.bodyAsChannel().copyTo(sink.outputStream())
                }
                true
              } else {
                false
              }
            }
          }
        }
      if (ok && (fileSystem.metadataOrNull(tmp)?.size ?: 0L) > 0L) {
        fileSystem.atomicMove(tmp, dest)
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    } finally {
      fileSystem.delete(tmp, mustExist = false)
    }
  }

  private fun sha256Hex(file: Path, fileSystem: FileSystem): String =
    fileSystem.source(file).buffer().use { source ->
      val hashing = HashingSink.sha256(blackholeSink())
      hashing.buffer().use { it.writeAll(source) }
      hashing.hash.hex()
    }

  /** Maven Central + Google Maven, the two repos that serve almost every Compose/AndroidX dep. */
  val DEFAULT_REMOTE_REPOSITORIES: List<String> =
    listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

  /**
   * Candidate `<artifact>-<version>.<ext>` filenames, in order: the coordinate's recorded type
   * (`jar` desktop / `aar` Android) first, then `.aar` as a fallback for Android deps an older
   * bundle recorded as `jar` or whose `.jar` isn't published. De-duplicated.
   */
  private fun candidateFileNames(coord: ClasspathEntry.Maven): List<String> =
    listOf(coord.type.ifBlank { "jar" }, "aar").distinct().map {
      "${coord.artifact}-${coord.version}.$it"
    }

  private fun defaultRepositoryRoots(): List<Path> {
    val home = System.getProperty("user.home")?.toPath()
    val roots = mutableListOf<Path>()
    System.getProperty("maven.repo.local")?.let { roots += it.toPath() }
    if (home != null) roots += home / ".m2/repository"
    val gradleHome = System.getenv("GRADLE_USER_HOME")?.toPath() ?: home?.let { it / ".gradle" }
    if (gradleHome != null) roots += gradleHome / "caches/modules-2/files-2.1"
    // Pre-XDG download-cache location — read-only fallback so artifacts a previous version
    // downloaded into `~/.cache/compose-preview/bundle-deps` still resolve after the move to
    // [composeAiCacheDir]. Nothing writes here.
    if (home != null) roots += home / ".cache/compose-preview/bundle-deps"
    return roots
  }

  private fun defaultNetworkEnabled(): Boolean {
    if (System.getProperty("composeai.bundle.offline").toBoolean()) return false
    if (System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1") return false
    return true
  }

  private fun defaultDownloadCacheDir(): Path {
    System.getProperty("composeai.bundle.cacheDir")?.let {
      return it.toPath()
    }
    return composeAiCacheDir("bundle-deps").path.toPath()
  }
}
