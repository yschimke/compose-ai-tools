package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Fetches the render daemons this CLI launches but doesn't contain, from
 * yschimke/compose-preview-daemon releases: `compose-preview-desktop-daemon-<v>.tar.gz`
 * (`lib-daemon-desktop/` + `lib-renderer/`) and `compose-preview-android-daemon-<v>.zip`
 * (`lib-daemon-android/`). Fetched on first need (like [ServerDistributionProvision] and
 * [XrCompositeProvision]) into `<cache>/composeai/preview-daemon/<version>/`.
 *
 * The version is [PREVIEW_DAEMON_VERSION], a point pin (not "latest") so an unvetted daemon never
 * arrives without a PR; `COMPOSE_PREVIEW_DAEMON_VERSION` overrides it.
 *
 * Launch paths still resolve sidecars via [ee.schimke.composeai.bundle.locateBundleSidecarJars]
 * (`-Dcomposeai.cli.lib…Dir`, then `APP_HOME`); [install] sets those properties unless one is
 * already set or the install has the directory, in which case nothing is fetched.
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
   * Whether [dir] holds every directory of [sidecar], each with at least one jar, so a partial
   * unpack never satisfies later runs.
   */
  fun isComplete(dir: File, sidecar: Sidecar): Boolean =
    sidecar.directories.all { name ->
      File(dir, name).listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.isNotEmpty() == true
    }

  /** The provisioned cache directory, or null. Never downloads (`doctor` must not block on it). */
  fun cached(
    sidecar: Sidecar,
    env: (String) -> String? = System::getenv,
    cacheRoot: File = defaultCacheRoot(),
  ): File? = cacheDir(version(env), cacheRoot).takeIf { isComplete(it, sidecar) }

  /**
   * Ensure the cache holds [version]'s [sidecar], fetching if needed, and return its directory.
   * Returns null (never throws) after explaining via [log]. Offline (`COMPOSE_PREVIEW_OFFLINE=1` /
   * `-Dcomposeai.bundle.offline=true`) never touches the network.
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
      // Replace one directory at a time; the other sidecar's directories stay untouched.
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
   * Provision [sidecar] and point the sidecar lookup at it; false (explained) when it is neither
   * present nor fetchable. An explicit `-Dcomposeai.cli.lib…Dir` or an installed directory wins.
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
   * The directory in an unpacked [stage] holding the sidecar dirs: [stage] itself, or a single
   * wrapper dir.
   */
  fun archiveRoot(stage: File, sidecar: Sidecar): File? {
    fun looksRight(dir: File) = sidecar.directories.all { File(dir, it).isDirectory }
    if (looksRight(stage)) return stage
    return stage.listFiles().orEmpty().filter { it.isDirectory }.firstOrNull { looksRight(it) }
  }

  /**
   * Unpack a zip into [destDir] with the JDK reader (no `unzip` dependency), refusing entries that
   * escape it.
   */
  fun unpackZip(zip: File, destDir: File) {
    destDir.mkdirs()
    val root = destDir.toPath().toAbsolutePath().normalize()
    ZipFile(zip).use { archive ->
      for (entry in archive.entries()) {
        // Normalise before checking containment (Zip Slip). An explicit `if` rather than `require`
        // so CodeQL recognises the sanitizer.
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
