package ee.schimke.composeai.viewer

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
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
 * Resolves a bundle's detached `maven` coordinates ([ClasspathEntry.Maven]) to jar files for a
 * bundle's child classloader. Duplicated from `:cli`'s `CoordinateResolver` to keep the viewer's
 * module graph minimal; keep the two in sync.
 * - Local repos and download cache first (`~/.m2/repository`, Gradle `modules-2`, our cache;
 *   overridable via `maven.repo.local` / `GRADLE_USER_HOME` / `composeai.bundle.cacheDir`); the v4
 *   `sha256` picks among several local copies.
 * - Remote repos (when [networkEnabled]; Maven Central + Google Maven) only on a local miss, cached
 *   afterwards. Off via `composeai.bundle.offline=true` / `COMPOSE_PREVIEW_OFFLINE=1`.
 * - Warn, never fail: a miss or hash mismatch logs and returns the best jar (or none).
 *
 * Filesystem access goes through Okio; only `.aar` extraction bridges to `java.io.File`.
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
      // The recorded type first, then `.aar`; [materialize] turns an `.aar` into its classes.jar.
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
   * Extract an `.aar`'s `classes.jar` to a stable cache path and return it; a `.jar` passes
   * through. Null for a resource-only `.aar` or an extraction error.
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
   * Download [coord]'s artifact from the first [remoteRepositories] base that serves it into
   * [downloadCacheDir] (Maven layout), returning the cached file or null. Never throws.
   */
  private fun download(
    coord: ClasspathEntry.Maven,
    remoteRepositories: List<String>,
    downloadCacheDir: Path,
    fileSystem: FileSystem,
  ): Path? {
    val versionDir = "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}"
    for (fileName in candidateFileNames(coord)) {
      // The cache always keys on the LITERAL `<artifact>-<version>.<ext>` name, even when the
      // remote served a timestamped snapshot file, so [locate] finds the download next time.
      val dest = downloadCacheDir / "$versionDir/$fileName"
      for (base in remoteRepositories) {
        val remoteName = remoteFileName(base, coord, versionDir, fileName)
        if (fetchTo(base.trimEnd('/') + "/" + versionDir + "/" + remoteName, dest, fileSystem))
          return materialize(dest, downloadCacheDir, fileSystem)
      }
    }
    return null
  }

  /**
   * The file name [base] serves for [coord]: [fileName] for a release, or for a unique snapshot the
   * timestamped name from `maven-metadata.xml` (the literal `-SNAPSHOT` name 404s). Falls back to
   * [fileName] on anything unexpected.
   */
  private fun remoteFileName(
    base: String,
    coord: ClasspathEntry.Maven,
    versionDir: String,
    fileName: String,
  ): String {
    if (!isSnapshot(coord.version)) return fileName
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    val metadata = fetchText(base.trimEnd('/') + "/" + versionDir + "/maven-metadata.xml")
    val timestamped = metadata?.let { snapshotVersion(it, extension) } ?: return fileName
    return "${coord.artifact}-$timestamped.$extension"
  }

  /** GET [url] as text, or null on a non-2xx / transport error. */
  private fun fetchText(url: String): String? =
    try {
      HttpClient(OkHttp).use { client ->
        runBlocking {
          client.prepareGet(url).execute { response ->
            if (response.status.isSuccess()) response.bodyAsText() else null
          }
        }
      }
    } catch (_: Exception) {
      null
    }

  /** A Maven snapshot version — the only kind whose artifact file name isn't the version. */
  internal fun isSnapshot(version: String): Boolean = version.endsWith("-SNAPSHOT")

  /**
   * The unique-snapshot version (`1.0.0-20260818.194125-1`) a version-level `maven-metadata.xml`
   * publishes for [extension], or null. A regex scan (no XML parser dependency) that skips
   * `<classifier>` blocks and takes the first matching `<extension>`.
   */
  internal fun snapshotVersion(metadataXml: String, extension: String): String? =
    SNAPSHOT_VERSION_BLOCK.findAll(metadataXml)
      .map { it.groupValues[1] }
      .filterNot { "<classifier>" in it }
      .firstOrNull { tagValue(it, "extension").equals(extension, ignoreCase = true) }
      ?.let { tagValue(it, "value") }
      ?.takeIf { it.isNotBlank() }

  private val SNAPSHOT_VERSION_BLOCK =
    Regex("""<snapshotVersion>(.*?)</snapshotVersion>""", RegexOption.DOT_MATCHES_ALL)

  private fun tagValue(block: String, tag: String): String? =
    Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
      .find(block)
      ?.groupValues
      ?.get(1)
      ?.trim()

  /**
   * GET [url] into [dest]; true only on a non-empty 2xx. Downloads to a `.part` sibling and moves
   * on success, so a failed fetch never clobbers a stale-but-usable cached copy.
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
   * Candidate `<artifact>-<version>.<ext>` filenames: the recorded type first, then `.aar` for
   * Android deps an older bundle recorded as `jar`. De-duplicated.
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
    // Pre-XDG download-cache location, read-only fallback for previously downloaded artifacts.
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
