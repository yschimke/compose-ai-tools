package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
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
 * Resolves a bundle's detached `maven` coordinates (#1632, Tier 3) into local jar files so a player
 * can put them on the classpath. This is the consumer side of schema v4's content-addressing: a
 * coordinate names *what* dependency the bundle needs, and the resolver finds the bytes from
 * whatever the machine already has — or, failing that, downloads them.
 *
 * # Sources, in order
 *
 * 1. **Local repositories** — a Maven local repo (`~/.m2/repository`, standard
 *    `group/as/path/artifact/version/...` layout) and the Gradle module cache
 *    (`~/.gradle/caches/modules-2/files-2.1`, jar fanned under per-sha1 dirs). A colleague who has
 *    built any project using the same deps already has them here, so the common "someone sent me a
 *    bundle" path stays offline.
 * 2. **Remote repositories** (when [networkEnabled]) — Maven Central + Google Maven by default,
 *    overridable via [remoteRepositories]. Hit only when the local repos can't satisfy the
 *    coordinate (nothing found, or a v4 hash that no local copy matched). A downloaded jar is
 *    cached under [downloadCacheDir] in Maven layout, so a second resolve — or a later local-only
 *    run — finds it without the network.
 *
 * # Verification — warn, never fail
 *
 * When a coordinate carries a v4 `sha256`, a resolved jar's bytes are hashed and compared. A
 * **mismatch is a loud warning, not an error**: a different artifact for the same coordinate is
 * usually almost compatible (point-release skew, a repackaged-but-equivalent jar), and a
 * slightly-off preview beats no preview. The resolver still returns the jar. A missing hash
 * (older/non-Gradle bundles) resolves silently as unverifiable. Mirrors the contract pinned on
 * [BundleReader.ClasspathEntry.Maven] / `PreviewBundleFormat`.
 *
 * The resolver never throws on a missing artifact or a failed download — it returns null and warns,
 * so the caller proceeds with a partial classpath (the bundled renderer's Compose stack covers the
 * common surface).
 */
internal class CoordinateResolver(
  private val repositoryRoots: List<File> = defaultRepositoryRoots(),
  private val warn: (String) -> Unit = { System.err.println("compose-preview: $it") },
  private val networkEnabled: Boolean = defaultNetworkEnabled(),
  private val remoteRepositories: List<String> = DEFAULT_REMOTE_REPOSITORIES,
  private val downloadCacheDir: File = defaultDownloadCacheDir(),
  private val fileSystem: FileSystem = SystemFileSystem,
) {

  /** Outcome of resolving one coordinate. [file] is null when nothing was found or downloaded. */
  data class Resolution(
    val coordinate: BundleReader.ClasspathEntry.Maven,
    val file: File?,
    val verified: Boolean,
    val mismatch: Boolean,
    /** True when [file] came from a remote repository rather than a pre-existing local one. */
    val downloaded: Boolean = false,
  )

  /**
   * Resolve [coords] to jars, warning (never throwing) on misses and hash mismatches. Returns one
   * [Resolution] per input coordinate in order; callers typically take `mapNotNull { it.file }` for
   * the classpath and surface the misses to the user.
   */
  fun resolveAll(coords: List<BundleReader.ClasspathEntry.Maven>): List<Resolution> = coords.map {
    resolve(it)
  }

  fun resolve(coord: BundleReader.ClasspathEntry.Maven): Resolution {
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

    // Local repos couldn't satisfy it (nothing found, or a hash that no local copy matched). Try
    // the
    // network before settling — the whole point of carrying a coordinate is that the bytes can be
    // re-fetched from any source.
    if (networkEnabled) {
      val fetched = download(coord)
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
        // Downloaded bytes don't match either — warn but keep them (almost-compatible beats
        // nothing).
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
        "repository${if (networkEnabled) " or remote repository" else ""}; the preview may fail to " +
        "render if it needs this dependency. Re-pack with --embed-deps for an offline bundle."
    )
    return Resolution(coord, file = null, verified = false, mismatch = false)
  }

  /**
   * All candidate jars for [coord] across [repositoryRoots], in search order (Maven layout before
   * Gradle layout, roots in declared order). May hold more than one when the same GAV is present in
   * several caches or Gradle hash dirs — [resolve] uses the hash to pick among them.
   */
  private fun locate(coord: BundleReader.ClasspathEntry.Maven): List<File> {
    val found = mutableListOf<File>()
    // Search the user's local repos AND our own download cache — a jar fetched during an earlier
    // online run lives in [downloadCacheDir] (Maven layout) and must resolve offline too, since the
    // network gate only governs *new* fetches, not reading what we already have.
    for (root in repositoryRoots + downloadCacheDir) {
      if (fileSystem.metadataOrNull(root.path.toPath())?.isDirectory != true) continue
      // Try each candidate filename: the coordinate's recorded type first, then `.aar` (an Android
      // dep may be recorded as `jar` by an older bundle, or its `.jar` simply isn't published —
      // AndroidX libs ship `.aar`). [materialize] turns any `.aar` hit into its `classes.jar`.
      for (fileName in candidateFileNames(coord)) {
        // Maven layout: <root>/<group as path>/<artifact>/<version>/<artifact>-<version>.<ext>
        val mavenPath =
          File(
            root,
            "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}/$fileName",
          )
        if (fileSystem.metadataOrNull(mavenPath.path.toPath())?.isRegularFile == true)
          found += mavenPath
        // Gradle modules-2 layout fans the artifact under per-hash dirs; collect every match under
        // <root>/<group>/<artifact>/<version>/<hash>/<fileName>. One directory level only, so a
        // huge cache root doesn't turn this into a full filesystem scan.
        val gradleVersionDir = File(root, "${coord.group}/${coord.artifact}/${coord.version}")
        if (fileSystem.metadataOrNull(gradleVersionDir.path.toPath())?.isDirectory == true) {
          fileSystem
            .listOrNull(gradleVersionDir.path.toPath())
            ?.asSequence()
            ?.map { it.toFile() }
            ?.filter { fileSystem.metadataOrNull(it.path.toPath())?.isDirectory == true }
            ?.mapNotNull { hashDir ->
              File(hashDir, fileName).takeIf {
                fileSystem.metadataOrNull(it.path.toPath())?.isRegularFile == true
              }
            }
            ?.let { found += it }
        }
      }
    }
    // Materialize before returning so the hash check (and the caller's classpath) sees real jars:
    // an `.aar` isn't loadable, and the bundle's `sha256` is of the extracted `classes.jar`.
    return found.mapNotNull { materialize(it) }
  }

  /**
   * Download [coord]'s artifact from the first [remoteRepositories] base URL that serves it, into
   * [downloadCacheDir] (Maven layout), and return the cached file. Returns null — never throws — on
   * a total miss or any transport error, so resolution degrades to a warning.
   *
   * A previously-cached download is *not* short-circuited here: [locate] already searches
   * [downloadCacheDir], so reaching this method means either nothing was cached, or the cached
   * bytes didn't match the bundle's hash and we want fresh bytes (which overwrite the stale copy).
   */
  private fun download(coord: BundleReader.ClasspathEntry.Maven): File? {
    // Try the recorded type, then `.aar` — same fallback as [locate], for the same reasons.
    for (fileName in candidateFileNames(coord)) {
      val rel = "${coord.group.replace('.', '/')}/${coord.artifact}/${coord.version}/$fileName"
      val dest = File(downloadCacheDir, rel)
      for (base in remoteRepositories) {
        val url = base.trimEnd('/') + "/" + rel
        if (fetchTo(url, dest)) return materialize(dest)
      }
    }
    return null
  }

  /**
   * An `.aar` isn't classpath-loadable, so extract its `classes.jar` to a stable cache path and
   * return that; a `.jar` (or anything else) passes through unchanged. Returns null for a
   * resource-only `.aar` with no `classes.jar` (nothing to contribute) or on any extraction error —
   * the resolver then treats it as a miss and warns, never throws.
   */
  private fun materialize(file: File): File? {
    if (!file.name.endsWith(".aar", ignoreCase = true)) return file
    // Key the extraction dir on the source path so distinct candidates (hash disambiguation) don't
    // collide, and a re-resolve reuses the already-extracted jar.
    val dest =
      File(
        downloadCacheDir,
        "extracted/${file.absolutePath.hashCode().toUInt().toString(16)}/classes.jar",
      )
    val destPath = dest.path.toPath()
    if ((fileSystem.metadataOrNull(destPath)?.size ?: 0L) > 0L) return dest
    return try {
      // Read the `.aar` (a zip) as an Okio FileSystem — no java.util.zip.ZipFile boundary.
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

  /** GET [url] → [dest] (parent dirs created); true only on a 2xx with a non-empty body. */
  private fun fetchTo(url: String, dest: File): Boolean {
    val destPath = dest.path.toPath()
    return try {
      dest.parentFile?.mkdirs()
      val ok =
        HttpClient(OkHttp).use { client ->
          runBlocking {
            client.prepareGet(url).execute { response ->
              if (response.status.isSuccess()) {
                fileSystem.sink(destPath).buffer().use { sink ->
                  response.bodyAsChannel().copyTo(sink.outputStream())
                }
                true
              } else {
                false
              }
            }
          }
        }
      if (ok && (fileSystem.metadataOrNull(destPath)?.size ?: 0L) > 0L) {
        true
      } else {
        fileSystem.delete(destPath, mustExist = false)
        false
      }
    } catch (_: Exception) {
      fileSystem.delete(destPath, mustExist = false)
      false
    }
  }

  private fun sha256Hex(file: File): String =
    fileSystem.source(file.path.toPath()).buffer().use { source ->
      val hashing = HashingSink.sha256(blackholeSink())
      hashing.buffer().use { it.writeAll(source) }
      hashing.hash.hex()
    }

  companion object {
    /** Maven Central + Google Maven, the two repos that serve almost every Compose/AndroidX dep. */
    val DEFAULT_REMOTE_REPOSITORIES: List<String> =
      listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

    /**
     * Candidate `<artifact>-<version>.<ext>` filenames to look for, in order: the coordinate's
     * recorded type (`jar` desktop / `aar` Android) first, then `.aar` as a fallback. The fallback
     * covers Android deps that an older bundle recorded as `jar`, or whose `.jar` isn't published
     * (AndroidX libs ship `.aar`). De-duplicated, so a `jar`/`aar` coordinate yields one or two
     * names.
     */
    private fun candidateFileNames(coord: BundleReader.ClasspathEntry.Maven): List<String> =
      listOf(coord.type.ifBlank { "jar" }, "aar").distinct().map {
        "${coord.artifact}-${coord.version}.$it"
      }

    /**
     * Default local repositories searched when a caller doesn't pass its own. Honours the standard
     * `maven.repo.local` override and `GRADLE_USER_HOME`; falls back to the conventional `~/.m2`
     * and `~/.gradle` locations. Non-existent roots are simply skipped at lookup time.
     */
    fun defaultRepositoryRoots(): List<File> {
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

    /**
     * Network resolution is on unless `composeai.bundle.offline=true` (sysprop) or
     * `COMPOSE_PREVIEW_OFFLINE=1` (env) — an escape hatch for sandboxes / air-gapped machines that
     * want strictly-local resolution.
     */
    fun defaultNetworkEnabled(): Boolean {
      if (System.getProperty("composeai.bundle.offline").toBoolean()) return false
      if (System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1") return false
      return true
    }

    /** Where downloaded coordinate jars are cached (Maven layout). Override-able for tests. */
    fun defaultDownloadCacheDir(): File {
      System.getProperty("composeai.bundle.cacheDir")?.let {
        return File(it)
      }
      return composeAiCacheDir("bundle-deps")
    }

    /**
     * Pre-XDG download-cache location (`~/.cache/compose-preview/bundle-deps`). Added to
     * [defaultRepositoryRoots] as a read-only fallback so artifacts a previous version downloaded
     * still resolve offline after the cache moved to [composeAiCacheDir]; nothing writes here.
     */
    private fun legacyDownloadCacheDir(): File? =
      System.getProperty("user.home")?.let { File(it, ".cache/compose-preview/bundle-deps") }
  }
}
