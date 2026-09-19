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
 * Fetches a distribution this CLI launches but does not contain, so a documented install works
 * without a second install step.
 *
 * Two of them, described by [ReleasedDistribution] and both attached to the same release: the
 * preview server behind `serve` / `browse` / `ui-builder`, and the MCP server behind `mcp serve`
 * since `:mcp` moved to the server's repository (#5176). One implementation, because everything
 * except the names is shared — the cache layout, the staged unpack, the offline gate, the
 * completeness rule that stops a half-written `bin/` short-circuiting every later run.
 *
 * [ServerBinaryDiscovery] answers *which* binary to run; this answers *where one comes from* when
 * the machine has none. #5177 turned `serve` into a launcher and closed the dependency cycle, but
 * left the binary to be installed by hand: the one-line installer in `yschimke/skills` fetches the
 * CLI and the skill bundle and knows nothing about the server, so everyone who installed the
 * documented way got an installation hint instead of a server (#5183).
 *
 * The fix is here rather than in the installer, and that is the decision #5183 asked for. An
 * installer that fetched the server unconditionally would put a 120 MB download in front of every
 * user of `render`, `show`, `bundle`, `history`, `a11y` and `mcp` — none of which open a socket —
 * to serve the two commands that do. Fetching on first use is the same trade the CLI already makes
 * for the Skiko native ([SkikoNativeProvision]) and the XR compositor ([XrCompositeProvision]), it
 * costs the installer nothing, and it keeps the install story inside one repository.
 *
 * # Which server
 *
 * **The newest published release**, resolved at run time from [LATEST_RELEASE_API] and cached under
 * the version it resolves to. There is no pin in this repository any more.
 *
 * That is a deliberate reversal, and it is worth stating what it gives up. This used to be a point
 * pin — `composeai-preview-server-dist`, baked into the jar — on the grounds that the two
 * repositories release on separate cadences and `latest` lets a server this CLI has never been
 * built against arrive without a pull request. That risk is real and is now accepted: `serve` is a
 * launcher over a process boundary rather than a linkage, so what the two have to agree on is the
 * wire, and a pin only ever delayed a skew rather than preventing one — an installed CLI kept
 * whatever release it was built against until someone upgraded it, which is its own kind of stale.
 * What replaces the pin as a check is `:cli`'s wire-drift suite, which drives the distribution it
 * would actually fetch.
 *
 * `COMPOSE_PREVIEW_SERVER_VERSION` names a specific release instead, which is how you pin a machine
 * (or a CI job) that must not move, and how you test a release before it is newest. Pointing at a
 * server you already have is [ServerBinaryDiscovery]'s job, not this one.
 *
 * Resolution needs the network, so it is confined to [ensure]. Everything that merely *asks* what
 * is installed — [cached], [ServerBinaryDiscovery], `doctor` — reads the cache and never resolves,
 * and when resolution fails [ensure] falls back to the newest complete copy already on the machine
 * rather than to a number compiled into the jar.
 *
 * # Where it lands
 *
 * `<cache>/preview-server/<version>/`, unpacked, holding the distribution's own `bin/` + `lib/`
 * layout. Keyed on the server's version rather than the CLI's, for the reason
 * [XR_COMPOSITE_VERSION] gives: a CLI upgrade must not orphan a cached copy of a server that did
 * not change. Nothing here is ever written to a path a reader can observe half-built — the unpack
 * goes to a staging directory and is moved into place once validated.
 */
internal object ServerDistributionProvision {

  /** Names the release fetched, instead of the newest. See the class note. */
  const val VERSION_ENV: String = "COMPOSE_PREVIEW_SERVER_VERSION"

  /** The document [latestVersion] reads the newest release's tag out of. */
  const val LATEST_RELEASE_API: String =
    "https://api.github.com/repos/$PREVIEW_SERVER_REPO/releases/latest"

  /**
   * The tag in that document. Matched rather than parsed as JSON: this module has no JSON reader on
   * its classpath, the field is one string, and a body that does not contain it is a failure either
   * way.
   */
  private val LATEST_TAG = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

  /**
   * Dotted-numeric order, with anything non-numeric sorting below anything numeric.
   *
   * Cache directory names are release versions, but the cache is a directory on someone's machine:
   * it can hold whatever a hand-edit or a future format left there, and that must not throw while
   * answering "which server do I have".
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
   * Fetch seam, faked in tests. Downloads [url] to [dest], throwing on a non-2xx or transport
   * error.
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
   * The release [VERSION_ENV] asks for, or null when it asks for nothing and the newest wins.
   *
   * Null rather than a default, because "no answer yet" and "this exact release" are different
   * states and every caller treats them differently: [cached] reads the cache, [ensure] resolves.
   */
  fun requestedVersion(env: (String) -> String? = System::getenv): String? =
    env(VERSION_ENV)?.trim()?.takeIf { it.isNotBlank() }

  /**
   * The newest published release's version, or null when it cannot be resolved.
   *
   * Never throws: a rate-limited API, a proxy, a transport error and an unrecognisable body are all
   * the same answer here — "not resolved" — and [ensure] has a cache to fall back to. The tag is
   * `v<version>`, and the leading `v` is dropped because every other function here takes the bare
   * version.
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

  /**
   * Every complete distribution already unpacked under [cacheRoot], newest first.
   *
   * Newest by version rather than by mtime: a re-fetch of an older release touches its directory,
   * and "the one that was written last" is not the one a caller means by "the server I have".
   */
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

  /**
   * The launcher script name inside the distribution's `bin/`. Gradle's application plugin writes
   * both; Windows needs the `.bat` because the POSIX script is not executable there.
   */
  fun binaryName(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    osName: String = System.getProperty("os.name") ?: "",
  ): String =
    if (osName.lowercase().contains("windows")) "${distribution.binary}.bat"
    else distribution.binary

  /**
   * Release asset name — exactly what compose-preview-server's `release.yml` uploads
   * (`server/build/distributions/compose-preview-server-<version>.tar.gz`).
   */
  fun assetName(
    version: String,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
  ): String = "${distribution.binary}-$version.tar.gz"

  /**
   * Download URL for [version]'s distribution on the [PREVIEW_SERVER_REPO] release tagged
   * `v<version>`.
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
   * The provisioned binary, or null when this machine has not fetched one. Never downloads — this
   * is the question [ServerBinaryDiscovery] and `doctor` ask, and neither may block on a 120 MB
   * transfer to answer it.
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
   * Whether [binary] is a launcher from a *complete* distribution — the script itself, plus a
   * non-empty sibling `lib/`.
   *
   * A partial unpack is deliberately not complete. Without the second half, an interrupted fetch
   * that happened to write `bin/` would short-circuit every later run and leave `serve` failing on
   * a missing main class until someone thought to wipe the cache by hand.
   */
  fun isComplete(binary: File): Boolean {
    if (!binary.isFile) return false
    val lib = File(binary.parentFile?.parentFile, "lib")
    return lib.isDirectory && (lib.listFiles()?.isNotEmpty() == true)
  }

  /**
   * Ensure the cache holds a distribution, fetching it when it does not, and return its launcher.
   * Returns null — never throws — on any failure, having explained it through [log]; the caller
   * reports [ServerBinaryDiscovery.installationHint] and exits, since `serve` has nothing to
   * degrade to.
   *
   * This is the only function here that resolves [latestVersion], because it is the only one whose
   * job includes a download. [requested] short-circuits that: asking for a release means taking it,
   * not comparing it against the newest.
   *
   * Offline (`COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`, the same gate the
   * bundle resolver and the Skiko provisioner read) never reaches the network — not for the
   * archive, and not for the API call that would name it either. An air-gapped machine gets the
   * newest copy it already has, or the hint; never a hung download.
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

    // Offline never resolves and never fetches: an air-gapped machine gets whatever it already has,
    // or the hint. Asking the API first would hang exactly the command that cannot afford it.
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

    // A requested release is taken as given — that is what asking for one means, and resolving the
    // newest as well would make the override advisory.
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
   * The distribution directory inside an unpacked [stage] — the tarball's own
   * `compose-preview-server-<version>/` wrapper, or [stage] itself if a future release ever packs
   * flat. Found by looking for the layout rather than by rebuilding the wrapper's name, so a
   * renamed archive root is not a broken install.
   */
  fun distributionRoot(stage: File, binaryName: String): File? {
    fun looksRight(dir: File) = isComplete(File(File(dir, "bin"), binaryName))
    if (looksRight(stage)) return stage
    return stage.listFiles().orEmpty().filter { it.isDirectory }.firstOrNull { looksRight(it) }
  }

  /**
   * Unpack a `.tar.gz` into [destDir] by shelling out to the system `tar`, exactly as
   * [XrCompositeProvision.unpackTarGz] does and for the same reason: `tar` is present on
   * Linux/macOS and ships as `bsdtar` on Windows 10+, and one call site does not justify a new
   * archive dependency. Throws on a non-zero exit, which [ensure] reports.
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

  /**
   * `${XDG_CACHE_HOME:-~/.cache}/composeai/<cacheDirName>`, via the shared cache convention. A
   * directory per distribution, so upgrading one never orphans the other's cached copy.
   */
  fun defaultCacheRoot(distribution: ReleasedDistribution = ReleasedDistribution.SERVER): File =
    composeAiCacheDir(distribution.cacheDirName)

  private fun defaultOffline(): Boolean =
    System.getProperty("composeai.bundle.offline").toBoolean() ||
      System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1"
}
