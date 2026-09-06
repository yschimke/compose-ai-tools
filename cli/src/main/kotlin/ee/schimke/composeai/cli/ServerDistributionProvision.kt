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
 * [SERVE_VERSION] — the `composeai-preview-server-dist` pin in `gradle/libs.versions.toml`, baked
 * into the jar at build time. That pin, not `composeai-preview-serve`: the latter names the
 * published jar the wire-drift tests compile against, and compose-preview-server can release the
 * distributions without republishing the library, so the two move independently. A **point pin, not
 * "latest"**: the two repositories release on separate cadences, so resolving `latest` at run time
 * would let a server the CLI has never been built against arrive under it without a pull request,
 * which is the skew #5183 names. Moving the pin is a reviewed change, and
 * `.github/ci/check_preview_server_pin.py` fails a PR whose pin names a release with no
 * distribution attached — a pin that 404s is a `serve` that cannot start.
 *
 * `COMPOSE_PREVIEW_SERVER_VERSION` overrides it, for testing a release the pin has not moved to
 * yet. Pointing at a server you already have is [ServerBinaryDiscovery]'s job, not this one.
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

  /** Overrides [SERVE_VERSION] for the release fetched. See the class note. */
  const val VERSION_ENV: String = "COMPOSE_PREVIEW_SERVER_VERSION"

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

  /** The release this CLI fetches: the environment override, else the pin it was built against. */
  fun version(env: (String) -> String? = System::getenv): String =
    env(VERSION_ENV)?.trim()?.takeIf { it.isNotBlank() } ?: SERVE_VERSION

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
  ): File? = cacheBinary(version(env), distribution, cacheRoot, osName).takeIf { isComplete(it) }

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
   * Ensure the cache holds [version]'s distribution, fetching it when it does not, and return its
   * launcher. Returns null — never throws — on any failure, having explained it through [log]; the
   * caller reports [ServerBinaryDiscovery.installationHint] and exits, since `serve` has nothing to
   * degrade to.
   *
   * Offline (`COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`, the same gate the
   * bundle resolver and the Skiko provisioner read) never reaches the network: an air-gapped
   * machine gets the hint, not a hung download.
   */
  fun ensure(
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    version: String = version(),
    cacheRoot: File = defaultCacheRoot(distribution),
    osName: String = System.getProperty("os.name") ?: "",
    offline: Boolean = defaultOffline(),
    fetcher: Fetcher = defaultFetcher,
    log: (String) -> Unit = { System.err.println(it) },
  ): File? {
    val binary = cacheBinary(version, distribution, cacheRoot, osName)
    if (isComplete(binary)) return binary

    val url = assetUrl(version, distribution)
    if (offline) {
      log(
        "compose-preview: ${distribution.label} $version is not cached at " +
          "${cacheDir(version, distribution, cacheRoot).absolutePath}, and offline mode is " +
          "enabled. Fetch $url while online, or point at one you already have."
      )
      return null
    }

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
