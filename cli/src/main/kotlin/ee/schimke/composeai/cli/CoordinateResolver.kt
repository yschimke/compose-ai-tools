package ee.schimke.composeai.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

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
    val jarName = artifactFileName(coord)
    val found = mutableListOf<File>()
    for (root in repositoryRoots) {
      if (!root.isDirectory) continue
      // Maven layout: <root>/<group as path>/<artifact>/<version>/<artifact>-<version>.jar
      val mavenPath = File(root, mavenRelativePath(coord))
      if (mavenPath.isFile) found += mavenPath
      // Gradle modules-2 layout fans the jar under per-hash dirs; collect every match under
      // <root>/<group>/<artifact>/<version>/<hash>/<jarName>. One directory level only, so a huge
      // cache root doesn't turn this into a full filesystem scan.
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
   * Download [coord]'s jar from the first [remoteRepositories] base URL that serves it, into
   * [downloadCacheDir] (Maven layout), and return the cached file. Returns null — never throws — on
   * a total miss or any transport error, so resolution degrades to a warning. If the cache already
   * holds the file (a prior download), it's returned without a network hit.
   */
  private fun download(coord: BundleReader.ClasspathEntry.Maven): File? {
    val rel = mavenRelativePath(coord)
    val cached = File(downloadCacheDir, rel)
    if (cached.isFile && cached.length() > 0) return cached
    for (base in remoteRepositories) {
      val url = base.trimEnd('/') + "/" + rel
      if (fetchTo(url, cached)) return cached
    }
    return null
  }

  /** GET [url] → [dest] (parent dirs created); true only on a 2xx with a non-empty body. */
  private fun fetchTo(url: String, dest: File): Boolean =
    try {
      dest.parentFile?.mkdirs()
      val ok =
        HttpClient(OkHttp).use { client ->
          runBlocking {
            client.prepareGet(url).execute { response ->
              if (response.status.isSuccess()) {
                dest.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
                true
              } else {
                false
              }
            }
          }
        }
      if (ok && dest.length() > 0) {
        true
      } else {
        dest.delete()
        false
      }
    } catch (_: Exception) {
      dest.delete()
      false
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

  companion object {
    /** Maven Central + Google Maven, the two repos that serve almost every Compose/AndroidX dep. */
    val DEFAULT_REMOTE_REPOSITORIES: List<String> =
      listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

    /** `<artifact>-<version>.<type>` — `type` is `jar` (desktop) or `aar` (Android). */
    private fun artifactFileName(coord: BundleReader.ClasspathEntry.Maven): String =
      "${coord.artifact}-${coord.version}.${coord.type.ifBlank { "jar" }}"

    /** `<group as path>/<artifact>/<version>/<artifact>-<version>.<type>`. */
    private fun mavenRelativePath(coord: BundleReader.ClasspathEntry.Maven): String =
      coord.group.replace('.', '/') +
        "/${coord.artifact}/${coord.version}/${artifactFileName(coord)}"

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
      val home = System.getProperty("user.home")?.let(::File) ?: File(".")
      return File(home, ".cache/compose-preview/bundle-deps")
    }
  }
}
