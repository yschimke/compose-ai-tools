package ee.schimke.composeai.bundle.coordinates

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.openZip

/**
 * Resolves a bundle's detached `maven` coordinates (#1632) into local jar files for a player's
 * classpath.
 *
 * Searches local repositories first (`~/.m2/repository`, the Gradle `modules-2` cache, and
 * [downloadCacheDir]), then, when [networkEnabled], [remoteRepositories]. Downloads are cached in
 * Maven layout under a per-sha256 bucket so two builds of one `-SNAPSHOT` never overwrite each
 * other.
 *
 * A v4 `sha256` mismatch is a loud warning, not an error: an almost-compatible jar beats no
 * preview. Nothing here throws on a miss; it warns and returns null. Transient failures (429, 5xx,
 * dropped connections) are retried with backoff, and the warning names what the network answered.
 */
public class CoordinateResolver(
  private val repositoryRoots: List<File> = defaultRepositoryRoots(),
  private val warn: (String) -> Unit = { System.err.println("compose-preview: $it") },
  private val networkEnabled: Boolean = defaultNetworkEnabled(),
  private val remoteRepositories: List<String> = DEFAULT_REMOTE_REPOSITORIES,
  private val downloadCacheDir: File = defaultDownloadCacheDir(),
  private val fileSystem: FileSystem = SystemFileSystem,
) {

  /** How a retry waits; replaced in tests. */
  internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

  /** Outcome of resolving one coordinate. [file] is null when nothing was found or downloaded. */
  public data class Resolution(
    val coordinate: BundleReader.ClasspathEntry.Maven,
    val file: File?,
    val verified: Boolean,
    val mismatch: Boolean,
    /** True when [file] came from a remote repository rather than a pre-existing local one. */
    val downloaded: Boolean = false,
  )

  /** Resolve [coords] to jars, one [Resolution] per input in order, warning on misses. */
  public fun resolveAll(coords: List<BundleReader.ClasspathEntry.Maven>): List<Resolution> =
    coords.map {
      resolve(it)
    }

  public fun resolve(coord: BundleReader.ClasspathEntry.Maven): Resolution {
    val candidates = locate(coord)
    val expected = coord.sha256

    // A local copy whose bytes match the recorded hash is the ideal outcome — done.
    if (expected != null) {
      candidates
        .firstOrNull { sha256Hex(it).equals(expected, ignoreCase = true) }
        ?.let {
          return Resolution(coord, file = it, verified = true, mismatch = false)
        }
    } else if (candidates.isNotEmpty()) {
      // No hash to disambiguate with — first local candidate, unverifiable.
      return Resolution(coord, file = candidates.first(), verified = false, mismatch = false)
    }

    // Nothing local matched: try the network before settling.
    val fetchFailures = mutableListOf<FetchFailure>()
    if (networkEnabled) {
      val fetched = download(coord, fetchFailures)
      if (fetched != null) {
        if (expected == null || sha256Hex(fetched).equals(expected, ignoreCase = true)) {
          return Resolution(
            coord,
            file = fetched,
            verified = expected != null,
            mismatch = false,
            downloaded = true,
          )
        }
        // Mismatched download: warn but keep it.
        warn(
          "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — downloaded copy " +
            "(${sha256Hex(fetched)}) does not match the bundle's $expected. Rendering with it anyway."
        )
        return Resolution(
          coord,
          file = fetched,
          verified = false,
          mismatch = true,
          downloaded = true,
        )
      }
    }

    // Nothing usable from the network. Fall back to a local mismatch if we have one, else give up.
    if (candidates.isNotEmpty()) {
      val fallback = candidates.first()
      warn(
        "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — bundle expected " +
          "$expected but no local or remote copy matched (using ${fallback.name}, " +
          "${sha256Hex(fallback)}). Rendering with the local copy anyway; the preview may differ " +
          "slightly from the original."
      )
      return Resolution(coord, file = fallback, verified = false, mismatch = true)
    }
    warn(
      "could not resolve ${coord.group}:${coord.artifact}:${coord.version} from any local " +
        "repository${if (networkEnabled) " or remote repository" else ""}" +
        describeFetchFailures(fetchFailures) +
        "; the preview may fail to render if it needs this dependency. Re-pack with " +
        "--embed-deps for an offline bundle."
    )
    return Resolution(coord, file = null, verified = false, mismatch = false)
  }

  /**
   * All candidate jars for [coord] across [repositoryRoots] and [downloadCacheDir], in search
   * order. May hold several; [resolve] picks by hash.
   */
  private fun locate(coord: BundleReader.ClasspathEntry.Maven): List<File> {
    val found = mutableListOf<File>()
    // The download cache is searched too, so earlier downloads resolve offline.
    for (root in repositoryRoots + downloadCacheDir) {
      if (fileSystem.metadataOrNull(root.path.toPath())?.isDirectory != true) continue
      // Recorded type first, then `.aar` (see [candidateFileNames]).
      for (fileName in candidateFileNames(coord)) {
        // Maven layout: <root>/<group as path>/<artifact>/<version>/<artifact>-<version>.<ext>
        val mavenVersionDir =
          File(root, "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}")
        val mavenPath = File(mavenVersionDir, fileName)
        if (fileSystem.metadataOrNull(mavenPath.path.toPath())?.isRegularFile == true)
          found += mavenPath
        // [download]'s per-sha256 buckets, one level deeper.
        found += subdirectoryHits(mavenVersionDir, fileName)
        // Gradle modules-2: <root>/<group>/<artifact>/<version>/<sha1>/<fileName>.
        found +=
          subdirectoryHits(
            File(root, "${coord.group}/${coord.artifact}/${coord.version}"),
            fileName,
          )
      }
    }
    // The bundle's sha256 for an `.aar` is of its extracted `classes.jar`.
    return found.mapNotNull { materialize(it) }
  }

  /** Every `<versionDir>/<*>/<fileName>`, one directory level only (never a filesystem walk). */
  private fun subdirectoryHits(versionDir: File, fileName: String): List<File> {
    if (fileSystem.metadataOrNull(versionDir.path.toPath())?.isDirectory != true) return emptyList()
    return fileSystem
      .listOrNull(versionDir.path.toPath())
      .orEmpty()
      .map { it.toFile() }
      .filter { fileSystem.metadataOrNull(it.path.toPath())?.isDirectory == true }
      .mapNotNull { dir ->
        File(dir, fileName).takeIf {
          fileSystem.metadataOrNull(it.path.toPath())?.isRegularFile == true
        }
      }
  }

  /**
   * Download [coord]'s artifact from the first [remoteRepositories] entry that serves it into
   * [downloadCacheDir] and return the cached file, or null on any failure. Only reached when no
   * cached copy matched.
   */
  private fun download(
    coord: BundleReader.ClasspathEntry.Maven,
    failures: MutableList<FetchFailure>,
  ): File? {
    val versionDir = "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}"
    // Bucketed by the expected sha256 (a per-content partition, not a claim about the file): every
    // build of a snapshot has the same literal file name. Unhashed coordinates stay flat.
    val cacheRel = coord.sha256?.let { "$versionDir/$it" } ?: versionDir
    for (fileName in candidateFileNames(coord)) {
      // Cached under the literal name even when a timestamped snapshot was served.
      val dest = File(downloadCacheDir, "$cacheRel/$fileName")
      for (base in remoteRepositories) {
        val remoteName = remoteFileName(base, coord, versionDir, fileName)
        val url = base.trimEnd('/') + "/" + versionDir + "/" + remoteName
        val failure = fetchTo(url, dest) ?: return materialize(dest)
        failures += failure
      }
    }
    return null
  }

  /**
   * The file name [base] serves for [coord]: [fileName] for a release, the timestamped unique
   * snapshot name from `maven-metadata.xml` for a `-SNAPSHOT` (the literal name 404s; #4259
   * / #4265). Falls back to [fileName] on anything unexpected.
   */
  private fun remoteFileName(
    base: String,
    coord: BundleReader.ClasspathEntry.Maven,
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

  /**
   * Extract an `.aar`'s `classes.jar` to the cache and return it; other files pass through. Null
   * for a resource-only `.aar` or an extraction error.
   */
  private fun materialize(file: File): File? {
    if (!file.name.endsWith(".aar", ignoreCase = true)) return file
    // Keyed on content, never path: snapshot AARs share a literal path, and a path-keyed
    // extraction served one catalog another's stale `classes.jar` (compose-preview-server#187).
    val dest = File(downloadCacheDir, "extracted/${sha256Hex(file)}/classes.jar")
    val destPath = dest.path.toPath()
    if ((fileSystem.metadataOrNull(destPath)?.size ?: 0L) > 0L) return dest
    return try {
      val aar = fileSystem.openZip(file.path.toPath())
      val entry = "classes.jar".toPath()
      if (!aar.exists(entry)) return null
      dest.parentFile?.mkdirs()
      aar.source(entry).use { source ->
        fileSystem.sink(destPath).buffer().use { it.writeAll(source) }
      }
      dest.takeIf { (fileSystem.metadataOrNull(destPath)?.size ?: 0L) > 0L }
    } catch (_: Exception) {
      null
    }
  }

  /** Why one URL gave no bytes, how many attempts it had, and whether the cause was transient. */
  internal data class FetchFailure(
    val url: String,
    val reason: String,
    val transient: Boolean,
    val notFound: Boolean = false,
    val retryAfterMs: Long? = null,
    val attempts: Int = 1,
  )

  /**
   * GET [url] → [dest]: null on a non-empty 2xx, else why not. Transient failures are retried up to
   * [MAX_ATTEMPTS] (Maven Central throttles a cold classpath's burst with 429); a 404 is final.
   */
  private fun fetchTo(url: String, dest: File): FetchFailure? {
    var attempt = 0
    while (true) {
      attempt++
      val failure = fetchOnce(url, dest) ?: return null
      if (!failure.transient || attempt >= MAX_ATTEMPTS) return failure.copy(attempts = attempt)
      sleeper(backoffMs(attempt, failure.retryAfterMs))
    }
  }

  private fun fetchOnce(url: String, dest: File): FetchFailure? {
    val destPath = dest.path.toPath()
    return try {
      dest.parentFile?.mkdirs()
      val failure =
        HttpClient(OkHttp).use { client ->
          runBlocking {
            client.prepareGet(url).execute { response ->
              if (response.status.isSuccess()) {
                fileSystem.sink(destPath).buffer().use { sink ->
                  response.bodyAsChannel().copyTo(sink.outputStream())
                }
                null
              } else {
                val code = response.status.value
                FetchFailure(
                  url = url,
                  reason = "HTTP $code ${response.status.description}".trim(),
                  transient = code == 429 || code >= 500,
                  notFound = code == 404 || code == 410,
                  retryAfterMs = response.headers["Retry-After"]?.let(::retryAfterMs),
                )
              }
            }
          }
        }
      when {
        failure != null -> {
          fileSystem.delete(destPath, mustExist = false)
          failure
        }
        (fileSystem.metadataOrNull(destPath)?.size ?: 0L) > 0L -> null
        else -> {
          fileSystem.delete(destPath, mustExist = false)
          FetchFailure(url, "an empty body", transient = false)
        }
      }
    } catch (e: Exception) {
      fileSystem.delete(destPath, mustExist = false)
      // Transport errors (reset, timeout, dropped tunnel) are transient.
      FetchFailure(url, transportReason(e), transient = true)
    }
  }

  private fun sha256Hex(file: File): String =
    fileSystem.source(file.path.toPath()).buffer().use { source ->
      val hashing = HashingSink.sha256(blackholeSink())
      hashing.buffer().use { it.writeAll(source) }
      hashing.hash.hex()
    }

  public companion object {
    /** Maven Central + Google Maven, the two repos that serve almost every Compose/AndroidX dep. */
    public val DEFAULT_REMOTE_REPOSITORIES: List<String> =
      listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

    /**
     * Attempts per URL, the first included. Only transient answers are retried (a 404 is final);
     * four attempts give a dropped connection a 7s window (1s, 2s, 4s).
     */
    internal const val MAX_ATTEMPTS: Int = 4

    /** The longest a server's `Retry-After` is honoured for; past it, the retry is not worth it. */
    internal const val MAX_RETRY_AFTER_MS: Long = 10_000L

    /** 1s, 2s, 4s: doubling from one second, unless the server said how long to wait. */
    internal fun backoffMs(attempt: Int, retryAfterMs: Long?): Long =
      retryAfterMs?.coerceIn(0L, MAX_RETRY_AFTER_MS) ?: (1_000L shl (attempt - 1))

    /**
     * A transport failure an operator can act on. ktor's `ClosedByteChannelException` only says the
     * stream closed, so the deepest cause (timeout, reset, TLS…) is named beside it.
     */
    internal fun transportReason(e: Throwable): String {
      fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
      var root = e
      val seen = mutableSetOf<Throwable>(e)
      while (true) {
        val next = root.cause ?: break
        if (!seen.add(next)) break
        root = next
      }
      return if (root === e) describe(e) else "${describe(e)} (caused by ${describe(root)})"
    }

    /** `Retry-After` in its delta-seconds form; the HTTP-date form falls back to the backoff. */
    internal fun retryAfterMs(header: String): Long? =
      header.trim().toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)

    /**
     * The "could not resolve" warning's tail: the first non-404 failure (the actionable one), or
     * that every repository answered 404.
     */
    internal fun describeFetchFailures(failures: List<FetchFailure>): String {
      if (failures.isEmpty()) return ""
      val telling = failures.firstOrNull { !it.notFound }
      if (telling == null)
        return " (not found in any of ${failures.map { host(it.url) }.distinct().size} remote repositories)"
      val tries = if (telling.attempts > 1) " after ${telling.attempts} attempts" else ""
      return " (${host(telling.url)} answered ${telling.reason}$tries)"
    }

    private fun host(url: String): String =
      url.substringAfter("://").substringBefore('/').ifEmpty { url }

    /**
     * `<artifact>-<version>.<ext>` names to try: the recorded type, then `.aar` (older bundles
     * recorded Android deps as `jar`, and AndroidX ships `.aar`).
     */
    private fun candidateFileNames(coord: BundleReader.ClasspathEntry.Maven): List<String> =
      listOf(coord.type.ifBlank { "jar" }, "aar").distinct().map {
        "${coord.artifact}-${coord.version}.$it"
      }

    /** A Maven snapshot version — the only kind whose artifact file name isn't the version. */
    public fun isSnapshot(version: String): Boolean = version.endsWith("-SNAPSHOT")

    /**
     * The unique-snapshot version (`1.0.0-20260818.194125-1`) a version-level `maven-metadata.xml`
     * publishes for [extension], or null. A regex scan (no XML parser dependency) that skips
     * classifier blocks.
     */
    public fun snapshotVersion(metadataXml: String, extension: String): String? =
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
     * Default local repositories: `maven.repo.local`, `~/.m2/repository`, the Gradle `modules-2`
     * cache (honouring `GRADLE_USER_HOME`) and the legacy download cache. Missing roots are
     * skipped.
     */
    public fun defaultRepositoryRoots(): List<File> {
      val home = System.getProperty("user.home")?.let(::File)
      val roots = mutableListOf<File>()
      System.getProperty("maven.repo.local")?.let { roots += File(it) }
      if (home != null) roots += File(home, ".m2/repository")
      val gradleHome =
        System.getenv("GRADLE_USER_HOME")?.let(::File) ?: home?.let { File(it, ".gradle") }
      if (gradleHome != null) roots += File(gradleHome, "caches/modules-2/files-2.1")
      legacyDownloadCacheDir()?.let { roots += it }
      return roots
    }

    /** On unless `composeai.bundle.offline=true` or `COMPOSE_PREVIEW_OFFLINE=1`. */
    public fun defaultNetworkEnabled(): Boolean {
      if (System.getProperty("composeai.bundle.offline").toBoolean()) return false
      if (System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1") return false
      return true
    }

    /** Where downloaded coordinate jars are cached (Maven layout). Override-able for tests. */
    public fun defaultDownloadCacheDir(): File {
      System.getProperty("composeai.bundle.cacheDir")?.let {
        return File(it)
      }
      return composeAiCacheDir("bundle-deps")
    }

    /** Pre-XDG download cache, kept as a read-only search root so old downloads resolve offline. */
    private fun legacyDownloadCacheDir(): File? =
      System.getProperty("user.home")?.let { File(it, ".cache/compose-preview/bundle-deps") }
  }
}
