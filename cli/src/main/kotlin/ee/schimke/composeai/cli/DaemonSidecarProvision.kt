package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Fetches the render daemons this CLI launches but does not contain.
 *
 * The desktop renderer, the desktop daemon and the Android (Robolectric) daemon used to be staged
 * into the CLI install from this build's own modules — `lib-renderer/` and `lib-daemon-desktop/`
 * inside the tarball, `lib-daemon-android/` as a separate release archive that had to be unpacked
 * and pointed at by hand. Those modules publish from yschimke/compose-preview-daemon now (#5336),
 * and that repository's release attaches the same two archives:
 * `compose-preview-desktop-daemon-<v>.tar.gz` (holding `lib-daemon-desktop/` + `lib-renderer/`) and
 * `compose-preview-android-daemon-<v>.zip` (holding `lib-daemon-android/`). This fetches the pinned
 * release's archive on the first command that needs it, exactly as [ServerDistributionProvision]
 * fetches the preview server and [XrCompositeProvision] the compositor, and caches it under
 * `<cache>/composeai/preview-daemon/<version>/`.
 *
 * # Which release
 *
 * [PREVIEW_DAEMON_VERSION] — the `composeai-preview-daemon` catalog pin, baked in at build time. A
 * point pin, not "latest": the daemons release on their own cadence, and a daemon this CLI was
 * never built against must not arrive under it without a pull request.
 * `COMPOSE_PREVIEW_DAEMON_VERSION` overrides it, for trying a release the pin has not moved to yet.
 *
 * # How the rest of the CLI finds it
 *
 * Nothing else changes: every launch path resolves a sidecar through
 * [ee.schimke.composeai.bundle.locateBundleSidecarJars], which reads `-Dcomposeai.cli.lib…Dir`
 * before `APP_HOME`. [install] provisions the archive and sets those properties for the sidecars it
 * carries — unless one is already set, or the install already holds that directory (a developer
 * install pointed at a checkout), in which case the explicit choice wins and nothing is fetched.
 */
internal object DaemonSidecarProvision {

  /** Overrides [PREVIEW_DAEMON_VERSION] for the release fetched. See the class note. */
  const val VERSION_ENV: String = "COMPOSE_PREVIEW_DAEMON_VERSION"

  /** One release archive and the sidecar directories it unpacks to. */
  enum class Sidecar(
    /** The asset's middle: `compose-preview-<asset>-<version>.<extension>`. */
    val asset: String,
    val extension: String,
    /** The top-level directories inside the archive, each a `lib-…/` of jars. */
    val directories: List<String>,
    val label: String,
  ) {
    DESKTOP(
      asset = "desktop-daemon",
      extension = "tar.gz",
      directories = listOf("lib-daemon-desktop", "lib-renderer"),
      label = "the desktop render daemon",
    ),
    ANDROID(
      asset = "android-daemon",
      extension = "zip",
      directories = listOf("lib-daemon-android"),
      label = "the Android render daemon",
    ),
  }

  /** Fetch seam, faked in tests. Downloads [url] to [dest], throwing on a non-2xx. */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  private val defaultFetcher = Fetcher { url, dest -> ServerDistributionProvision.fetch(url, dest) }

  /** The release this CLI fetches: the environment override, else the pin it was built against. */
  fun version(env: (String) -> String? = System::getenv): String =
    env(VERSION_ENV)?.trim()?.takeIf { it.isNotBlank() } ?: PREVIEW_DAEMON_VERSION

  /** Release asset name — exactly what compose-preview-daemon's `release.yml` uploads. */
  fun assetName(version: String, sidecar: Sidecar): String =
    "compose-preview-${sidecar.asset}-$version.${sidecar.extension}"

  /**
   * Download URL for [version]'s archive on the [PREVIEW_DAEMON_REPO] release tagged `v<version>`.
   */
  fun assetUrl(version: String, sidecar: Sidecar): String =
    "https://github.com/$PREVIEW_DAEMON_REPO/releases/download/v$version/" +
      assetName(version, sidecar)

  /** Per-version cache directory; the sidecar directories sit directly inside it. */
  fun cacheDir(version: String, cacheRoot: File = defaultCacheRoot()): File =
    File(cacheRoot, version)

  /**
   * Whether [dir] holds every directory of [sidecar], each with at least one jar. A partial unpack
   * is deliberately not complete: an interrupted fetch that wrote one of the two desktop
   * directories must not satisfy every later run and leave `bundle render` failing on a missing
   * class until someone wipes the cache by hand.
   */
  fun isComplete(dir: File, sidecar: Sidecar): Boolean =
    sidecar.directories.all { name ->
      File(dir, name).listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.isNotEmpty() == true
    }

  /**
   * The provisioned cache directory, or null when this machine has not fetched [sidecar]. Never
   * downloads — this is the question `doctor` asks, and it may not block on a transfer to answer.
   */
  fun cached(
    sidecar: Sidecar,
    env: (String) -> String? = System::getenv,
    cacheRoot: File = defaultCacheRoot(),
  ): File? = cacheDir(version(env), cacheRoot).takeIf { isComplete(it, sidecar) }

  /**
   * Ensure the cache holds [version]'s [sidecar], fetching it when it does not, and return the
   * cache directory. Returns null — never throws — on any failure, having explained it through
   * [log]; the caller reports the sidecar as missing exactly as it did when the archive had to be
   * unpacked by hand.
   *
   * Offline (`COMPOSE_PREVIEW_OFFLINE=1` / `-Dcomposeai.bundle.offline=true`, the same gate the
   * bundle resolver and the Skiko provisioner read) never reaches the network.
   */
  fun ensure(
    sidecar: Sidecar,
    version: String = version(),
    cacheRoot: File = defaultCacheRoot(),
    offline: Boolean = defaultOffline(),
    fetcher: Fetcher = defaultFetcher,
    log: (String) -> Unit = { System.err.println(it) },
  ): File? {
    val dir = cacheDir(version, cacheRoot)
    if (isComplete(dir, sidecar)) return dir

    val url = assetUrl(version, sidecar)
    if (offline) {
      log(
        "compose-preview: ${sidecar.label} $version is not cached at ${dir.absolutePath}, and " +
          "offline mode is enabled. Fetch $url while online, or point at one you already have."
      )
      return null
    }

    val parent = dir.parentFile
    val archive = File(parent, ".$version.dl-${UUID.randomUUID()}.${sidecar.extension}")
    val stage = File(parent, ".$version.stage-${UUID.randomUUID()}")
    return try {
      parent?.mkdirs()
      log("compose-preview: fetching ${sidecar.label} $version (this happens once) from $url")
      fetcher.fetchTo(url, archive)
      when (sidecar.extension) {
        "zip" -> unpackZip(archive, stage)
        else -> ServerDistributionProvision.unpackTarGz(archive, stage)
      }
      val root =
        archiveRoot(stage, sidecar)
          ?: run {
            log(
              "compose-preview: $url unpacked without ${sidecar.directories.joinToString(" + ")}, " +
                "so it is not a ${assetName(version, sidecar)} archive."
            )
            return null
          }
      dir.mkdirs()
      // One directory at a time, replacing whatever a previous partial unpack left: the other
      // sidecar's directories in the same version directory stay untouched.
      for (name in sidecar.directories) {
        val target = File(dir, name)
        target.deleteRecursively()
        val source = File(root, name)
        try {
          Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
          Files.move(source.toPath(), target.toPath())
        }
      }
      log("compose-preview: installed ${sidecar.label} $version into ${dir.absolutePath}")
      dir.takeIf { isComplete(it, sidecar) }
    } catch (e: Exception) {
      log("compose-preview: could not fetch ${sidecar.label} from $url (${e.message ?: e})")
      null
    } finally {
      archive.delete()
      stage.deleteRecursively()
    }
  }

  /**
   * Provision [sidecar] and point the sidecar lookup at it. Returns false, having explained why,
   * when the sidecar is neither present nor fetchable.
   *
   * An explicit `-Dcomposeai.cli.lib…Dir` or a directory already inside the install wins: those are
   * a person's choice of daemon, and this must not fetch over it.
   */
  fun install(
    sidecar: Sidecar,
    log: (String) -> Unit = { System.err.println(it) },
    provision: () -> File? = { ensure(sidecar, log = log) },
  ): Boolean {
    if (sidecar.directories.all { locateBundleSidecarJars(it).isNotEmpty() }) return true
    val dir = provision() ?: return false
    for (name in sidecar.directories) {
      val property = sidecarProperty(name)
      if (System.getProperty(property) == null) {
        System.setProperty(property, File(dir, name).absolutePath)
      }
    }
    return true
  }

  /** The `-D` property [locateBundleSidecarJars] reads first for a sidecar directory name. */
  fun sidecarProperty(directory: String): String =
    when (directory) {
      "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
      "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
      "lib-renderer" -> "composeai.cli.libRendererDir"
      else -> error("no sidecar property for $directory")
    }

  /**
   * The directory inside an unpacked [stage] that holds the sidecar directories — [stage] itself
   * (the archives pack flat), or a single wrapper directory if a future release ever adds one.
   */
  fun archiveRoot(stage: File, sidecar: Sidecar): File? {
    fun looksRight(dir: File) = sidecar.directories.all { File(dir, it).isDirectory }
    if (looksRight(stage)) return stage
    return stage.listFiles().orEmpty().filter { it.isDirectory }.firstOrNull { looksRight(it) }
  }

  /**
   * Unpack a zip into [destDir] with the JDK's own reader, refusing an entry that would escape it.
   * No shell-out: `unzip` is not a given on every host the way `tar` is.
   */
  fun unpackZip(zip: File, destDir: File) {
    destDir.mkdirs()
    val root = destDir.toPath().toAbsolutePath().normalize()
    ZipFile(zip).use { archive ->
      for (entry in archive.entries()) {
        // Normalise before the containment check so a `..` segment cannot climb out of the
        // destination (Zip Slip); the check is on the normalised path, never on the raw name. An
        // explicit `if` rather than `require`: the inline stdlib call is invisible to CodeQL's
        // guard detection, and this is the sanitizer form it recognises.
        val target = root.resolve(entry.name).normalize()
        if (!target.startsWith(root)) {
          throw IllegalArgumentException("zip entry escapes the destination: ${entry.name}")
        }
        if (entry.isDirectory) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          archive.getInputStream(entry).use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
          }
        }
      }
    }
  }

  /** `${XDG_CACHE_HOME:-~/.cache}/composeai/preview-daemon`, via the shared cache convention. */
  fun defaultCacheRoot(): File = composeAiCacheDir("preview-daemon")

  private fun defaultOffline(): Boolean =
    System.getProperty("composeai.bundle.offline").toBoolean() ||
      System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1"
}
