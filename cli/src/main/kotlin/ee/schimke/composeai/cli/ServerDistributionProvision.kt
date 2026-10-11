package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Fetches a distribution this CLI launches but does not contain, on first use, so a documented
 * install works without a second step: the preview server behind `serve` / `browse` / `ui-builder`
 * and the MCP server behind `mcp serve`, both from one release ([ReleasedDistribution]).
 * [ServerBinaryDiscovery] decides which binary to run; this supplies one when there is none.
 * Fetched lazily (like [SkikoNativeProvision] and [XrCompositeProvision]) so commands that never
 * open a socket don't pay for a 120 MB download.
 *
 * **Which server:** the newest published release, resolved from [LATEST_RELEASE_API] and cached
 * under its version. There is deliberately no pin: `serve` talks to it over a process boundary, and
 * `:cli`'s wire-drift suite tests against what would be fetched. `COMPOSE_PREVIEW_SERVER_VERSION`
 * pins a specific release. Only [ensure] touches the network; [cached], [ServerBinaryDiscovery] and
 * `doctor` read the cache, and a failed resolution falls back to the newest complete cached copy.
 *
 * **Where:** `<cache>/preview-server/<version>/` with the distribution's `bin/` + `lib/` layout,
 * keyed by server version so a CLI upgrade doesn't orphan it. Unpacked into a staging directory and
 * moved into place once validated, so readers never see a half-built copy.
 */
internal object ServerDistributionProvision {

  /** Names the release fetched, instead of the newest. See the class note. */
  const val VERSION_ENV: String = "COMPOSE_PREVIEW_SERVER_VERSION"

  /** The document [latestVersion] reads the newest release's tag out of. */
  const val LATEST_RELEASE_API: String =
    "https://api.github.com/repos/$PREVIEW_SERVER_REPO/releases/latest"

  /**
   * The tag in that document, matched by regex: no JSON reader is on this classpath and it is one
   * string.
   */
  private val LATEST_TAG = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

  /**
   * Dotted-numeric order, non-numeric names sorting lowest; the cache can contain anything, and
   * ordering must not throw.
   */
  private val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
    val left = a.split('.').map { it.toIntOrNull() }
    val right = b.split('.').map { it.toIntOrNull() }
    var result = 0
    for (i in 0 until maxOf(left.size, right.size)) {
      val l = left.getOrNull(i)
      val r = right.getOrNull(i)
      result =
        when {
          l == r -> continue
          l == null -> -1
          r == null -> 1
          else -> l.compareTo(r)
        }
      break
    }
    if (result != 0) result else a.compareTo(b)
  }

  /**
   * Fetch seam, faked in tests. Downloads [url] to [dest], throwing on non-2xx or transport errors.
   */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  private val defaultFetcher = Fetcher { url, dest ->
    HttpClient(OkHttp).use { client ->
      runBlocking {
        client.prepareGet(url).execute { response ->
          if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
          dest.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
        }
      }
    }
  }

  /** The default HTTP fetch, shared with [DaemonSidecarProvision] so the two cannot differ. */
  internal fun fetch(url: String, dest: File) = defaultFetcher.fetchTo(url, dest)

  /**
   * The release [VERSION_ENV] asks for, or null (newest wins). Null rather than a default because
   * [cached] and [ensure] treat "no answer" differently.
   */
  fun requestedVersion(env: (String) -> String? = System::getenv): String? =
    env(VERSION_ENV)?.trim()?.takeIf { it.isNotBlank() }

  /**
   * The newest published release's version (tag minus its leading `v`), or null when it can't be
   * resolved. Never throws; [ensure] falls back to the cache.
   */
  fun latestVersion(
    fetcher: Fetcher = defaultFetcher,
    log: (String) -> Unit = {},
  ): String? {
    val body = File.createTempFile("preview-server-latest", ".json")
    return try {
      fetcher.fetchTo(LATEST_RELEASE_API, body)
      val tag = LATEST_TAG.find(body.readText())?.groupValues?.get(1)
      if (tag == null) {
        log("compose-preview: $LATEST_RELEASE_API named no release tag")
        null
      } else tag.removePrefix("v").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      log(
        "compose-preview: could not ask $LATEST_RELEASE_API for the newest release (${e.message ?: e})"
      )
      null
    } finally {
      body.delete()
    }
  }

  /** Every complete distribution under [cacheRoot], newest version first (not mtime). */
  fun cachedVersions(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    cacheRoot: File = defaultCacheRoot(distribution),
    osName: String = System.getProperty("os.name") ?: "",
  ): List<String> =
    cacheRoot
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && !it.name.startsWith(".") }
      .map { it.name }
      .filter { isComplete(cacheBinary(it, distribution, cacheRoot, osName)) }
      .sortedWith(VERSION_ORDER.reversed())

  /** The launcher script name in the distribution's `bin/` (`.bat` on Windows). */
  fun binaryName(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    osName: String = System.getProperty("os.name") ?: "",
  ): String =
    if (osName.lowercase().contains("windows")) "${distribution.binary}.bat"
    else distribution.binary

  /** Release asset name, exactly as compose-preview-server's `release.yml` uploads it. */
  fun assetName(
    version: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
  ): String = "${distribution.binary}-$version.tar.gz"

  /**
   * Download URL for [version]'s distribution on the [PREVIEW_SERVER_REPO] `v<version>` release.
   */
  fun assetUrl(
    version: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
  ): String =
    "https://github.com/$PREVIEW_SERVER_REPO/releases/download/v$version/" +
      assetName(version, distribution)

  /** Per-version cache directory holding the unpacked distribution. */
  fun cacheDir(
    version: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    cacheRoot: File = defaultCacheRoot(distribution),
  ): File = File(cacheRoot, version)

  /** The launcher inside [cacheDir], whether or not it exists yet. */
  fun cacheBinary(
    version: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    cacheRoot: File = defaultCacheRoot(distribution),
    osName: String = System.getProperty("os.name") ?: "",
  ): File =
    File(File(cacheDir(version, distribution, cacheRoot), "bin"), binaryName(distribution, osName))

  /**
   * The provisioned binary, or null. Never downloads: [ServerBinaryDiscovery] and `doctor` must not
   * block on it.
   */
  fun cached(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    env: (String) -> String? = System::getenv,
    cacheRoot: File = defaultCacheRoot(distribution),
    osName: String = System.getProperty("os.name") ?: "",
  ): File? {
    val requested = requestedVersion(env)
    val version = requested ?: cachedVersions(distribution, cacheRoot, osName).firstOrNull()
    return version
      ?.let { cacheBinary(it, distribution, cacheRoot, osName) }
      ?.takeIf { isComplete(it) }
  }

  /**
   * Delete cached versions older than [latest] (and the refresh stamp) so the next launch fetches
   * [latest]. Returns the removed versions; newer or unparseable ones are kept.
   */
  fun pruneOlderThan(
    latest: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    cacheRoot: File = defaultCacheRoot(distribution),
  ): List<String> {
    val removed =
      cacheRoot
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .filter { VERSION_ORDER.compare(it.name, latest) < 0 }
        .filter { it.deleteRecursively() }
        .map { it.name }
    File(cacheRoot, ServerBinaryDiscovery.REFRESH_STAMP).delete()
    return removed.sortedWith(VERSION_ORDER)
  }

  /**
   * Whether [binary] belongs to a complete distribution: the script plus a non-empty sibling
   * `lib/`. Otherwise an interrupted unpack that wrote `bin/` would short-circuit every later run.
   */
  fun isComplete(binary: File): Boolean {
    if (!binary.isFile) return false
    val lib = File(binary.parentFile?.parentFile, "lib")
    return lib.isDirectory && (lib.listFiles()?.isNotEmpty() == true)
  }

  /**
   * Ensure the cache holds a distribution, fetching if needed, and return its launcher. Returns
   * null (never throws) after explaining via [log]; the caller prints
   * [ServerBinaryDiscovery.installationHint].
   *
   * The only function that resolves [latestVersion]; [requested] skips that. Offline
   * (`COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`) never touches the network and
   * uses the newest cached copy, or the hint.
   */
  fun ensure(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    requested: String? = requestedVersion(),
    cacheRoot: File = defaultCacheRoot(distribution),
    osName: String = System.getProperty("os.name") ?: "",
    offline: Boolean = defaultOffline(),
    fetcher: Fetcher = defaultFetcher,
    log: (String) -> Unit = { System.err.println(it) },
  ): File? {
    val cached = cachedVersions(distribution, cacheRoot, osName)

    // Offline: use what's cached or the hint; never call the API.
    if (offline) {
      val have = requested ?: cached.firstOrNull()
      val binary = have?.let { cacheBinary(it, distribution, cacheRoot, osName) }
      if (binary != null && isComplete(binary)) return binary
      log(
        "compose-preview: no cached ${distribution.label} under ${cacheRoot.absolutePath}" +
          (requested?.let { " at $it" } ?: "") +
          ", and offline mode is enabled. Fetch one while online, or point at one you already have."
      )
      return null
    }

    // A requested release is taken as given, not compared against the newest.
    val version =
      requested
        ?: latestVersion(fetcher, log)
        ?: cached.firstOrNull()?.also {
          log(
            "compose-preview: could not resolve the newest ${distribution.label}; using the cached $it"
          )
        }
    if (version == null) {
      log(
        "compose-preview: could not resolve the newest ${distribution.label}, and this machine has " +
          "none cached. Set ${VERSION_ENV} to a release, or point at one you already have."
      )
      return null
    }

    val binary = cacheBinary(version, distribution, cacheRoot, osName)
    if (isComplete(binary)) return binary

    val url = assetUrl(version, distribution)

    val dir = cacheDir(version, distribution, cacheRoot)
    val parent = dir.parentFile
    val archive = File(parent, ".$version.dl-${UUID.randomUUID()}.tar.gz")
    val stage = File(parent, ".$version.stage-${UUID.randomUUID()}")
    return try {
      parent?.mkdirs()
      log("compose-preview: fetching ${distribution.label} $version (this happens once) from $url")
      fetcher.fetchTo(url, archive)
      unpackTarGz(archive, stage)
      val name = binaryName(distribution, osName)
      val root =
        distributionRoot(stage, name)
          ?: run {
            log(
              "compose-preview: $url unpacked without a bin/$name beside a lib/ directory, so it " +
                "is not a ${distribution.binary} distribution."
            )
            return null
          }
      File(File(root, "bin"), name).setExecutable(true, false)
      dir.deleteRecursively()
      try {
        Files.move(root.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        Files.move(root.toPath(), dir.toPath())
      }
      log("compose-preview: installed ${distribution.label} $version into ${dir.absolutePath}")
      binary.takeIf { isComplete(it) }
    } catch (e: Exception) {
      log("compose-preview: could not fetch ${distribution.label} from $url (${e.message ?: e})")
      null
    } finally {
      archive.delete()
      stage.deleteRecursively()
    }
  }

  /**
   * The distribution directory inside an unpacked [stage] (the tarball's wrapper dir, or [stage]
   * itself), found by layout rather than by name.
   */
  fun distributionRoot(stage: File, binaryName: String): File? {
    fun looksRight(dir: File) = isComplete(File(File(dir, "bin"), binaryName))
    if (looksRight(stage)) return stage
    return stage.listFiles().orEmpty().filter { it.isDirectory }.firstOrNull { looksRight(it) }
  }

  /**
   * Unpack a `.tar.gz` with the system `tar` (bsdtar on Windows 10+), as
   * [XrCompositeProvision.unpackTarGz] does. Throws on a non-zero exit.
   */
  fun unpackTarGz(tarGz: File, destDir: File) {
    destDir.mkdirs()
    val proc =
      ProcessBuilder("tar", "-xzf", tarGz.absolutePath, "-C", destDir.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) error("tar exited $code: ${output.trim().takeLast(300)}")
  }

  /** `${XDG_CACHE_HOME:-~/.cache}/composeai/<cacheDirName>`, one directory per distribution. */
  fun defaultCacheRoot(distribution: ReleasedDistribution = ReleasedDistribution.SERVER): File =
    composeAiCacheDir(distribution.cacheDirName)

  internal fun defaultOffline(): Boolean =
    System.getProperty("composeai.bundle.offline").toBoolean() ||
      System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1"
}
