package ee.schimke.composeai.cli

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking

/**
 * Provisions the host (desktop) builds of native libraries that APKs carry only for Android, which
 * `compose-preview launch` puts on the simulator JVM's `java.library.path` in place of the APK's
 * `lib/<abi>/` libraries (#6342, #6411). They are published from this repository on their own
 * release line, `host-natives-v<version>` (`host-natives.yml`; `docs/RELEASING.md`, "The
 * host-natives binaries"), and the version fetched is the `host-natives` catalog pin
 * ([HOST_NATIVES_VERSION]).
 *
 * Today that is one library, AndroidX graphics-path's `libandroidx.graphics.path`: graphics-path
 * 1.0.1, which every stable Compose UI up to 1.12.x pins, loads it unconditionally, so a Compose
 * app fails at its first `Path.iterator()` without it.
 *
 * Shaped like [XrCompositeProvision]: one tarball per platform, unpacked into a per-version cache
 * through a staging directory and an atomic move; any failure logs and skips, so the launch keeps
 * today's clear `UnsatisfiedLinkError`; under [NetworkPolicy]'s switch an uncached copy refuses.
 */
object HostNativesProvision {
  /** The properties file every tarball carries, naming its version, platform and libraries. */
  internal const val PROPERTIES = "host-natives.properties"

  /**
   * The libraries the published tarballs provide, keyed by the name the APK's code passes to
   * `System.loadLibrary`, each with the properties key naming its file in the tarball.
   */
  internal val LIBRARIES = mapOf("androidx.graphics.path" to "graphics-path.library")

  /**
   * graphics-path versions the host build is verified against (`host-natives/verify`). A wrong
   * version is worse than none — it loads, then fails at a random JNI call — so an APK naming any
   * other version gets nothing. 1.1.0 and later load the library only on Dalvik and need none.
   */
  internal val GRAPHICS_PATH_VERSIONS = setOf("1.0.1")

  /** AndroidX's version marker for graphics-path in an APK. */
  internal const val GRAPHICS_PATH_VERSION_FILE = "META-INF/androidx.graphics_graphics-path.version"

  /**
   * The lowest simulated API level the host build serves. Below API 34, graphics-path walks paths
   * with code tied to Skia's `SkPathRef` layout, while the host build's stand-in `api-level.h`
   * reports 34; those launches keep the `UnsatisfiedLinkError`.
   */
  internal const val MIN_SDK = 34

  /**
   * Platform token from `os.name` / `os.arch`, matching `host-natives.yml`'s build matrix:
   * - linux + x86_64/amd64 → `linux-x86_64`
   * - mac, any architecture → `macos-universal` (one arm64 + x86_64 dylib)
   *
   * Null for anything else, Linux arm64 included: Robolectric publishes no native runtime there for
   * the simulator to run on. Pure for testing.
   */
  internal fun platformToken(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    return when {
      os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x86_64"
      os.contains("mac") || os.contains("darwin") -> "macos-universal"
      else -> null
    }
  }

  internal fun currentPlatformToken(): String? =
    platformToken(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")

  /** Release asset filename, `host-natives-<platform>-<version>.tar.gz`, as `build.sh` packs it. */
  internal fun assetName(version: String, platform: String): String =
    "host-natives-$platform-$version.tar.gz"

  /** Download URL: the `host-natives-v<version>` prerelease of [REPO], not a `v<version>` tag. */
  internal fun assetUrl(version: String, platform: String): String =
    "https://github.com/$REPO/releases/download/host-natives-v$version/" +
      assetName(version, platform)

  /**
   * `${XDG_CACHE_HOME:-~/.cache}/composeai/host-natives/<version>/<platform>`. [env]/[userHome] are
   * injectable for tests.
   */
  internal fun cacheDir(
    version: String,
    platform: String,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
  ): File {
    val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) File(xdg) else File(userHome, ".cache")
    return File(base, "composeai/host-natives/$version/$platform")
  }

  /**
   * Whether [dir] holds a complete layout: [PROPERTIES] and every library it names for [LIBRARIES].
   * A partial extraction is re-provisioned rather than trusted forever.
   */
  internal fun isComplete(dir: File): Boolean {
    val file = File(dir, PROPERTIES)
    if (!file.isFile) return false
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    return LIBRARIES.values.all { key ->
      val name = properties.getProperty(key)?.trim()
      !name.isNullOrEmpty() && File(dir, name).isFile
    }
  }

  /**
   * Which of [LIBRARIES] [apk] needs a host build of: those it carries under `lib/<abi>/`, at a
   * verified version. graphics-path's version comes from AndroidX's `META-INF` marker; an APK
   * without one (stripped by its build) still gets the library, since a 1.1.0+ APK never loads it
   * off Dalvik, and anything else fails the same way without it. Empty when the APK cannot be read.
   */
  internal fun neededBy(apk: File): Set<String> = runCatching {
    ZipFile(apk).use { zip ->
      val carried =
        zip
          .entries()
          .asSequence()
          .map { it.name }
          .filter { it.startsWith("lib/") && it.endsWith(".so") }
          .map { it.substringAfterLast('/').removePrefix("lib").removeSuffix(".so") }
          .toSet()
      val graphicsPathVersion =
        zip.getEntry(GRAPHICS_PATH_VERSION_FILE)?.let { entry ->
          zip.getInputStream(entry).use { it.readBytes().decodeToString().trim() }
        }
      LIBRARIES.keys
        .filter { it in carried }
        .filter { name ->
          name != "androidx.graphics.path" ||
            graphicsPathVersion == null ||
            graphicsPathVersion in GRAPHICS_PATH_VERSIONS
        }
        .toSet()
    }
  }
    .getOrDefault(emptySet())

  /** Whether a launch of [apk] at [sdk] should get the host builds. */
  internal fun wanted(apk: File, sdk: Int): Boolean = sdk >= MIN_SDK && neededBy(apk).isNotEmpty()

  /** Fetch seam for tests. [fetchTo] downloads [url], throwing on non-2xx or network errors. */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  /** Default fetcher: Ktor over OkHttp (follows redirects), streaming the body to disk. */
  internal val defaultFetcher = Fetcher { url, dest ->
    // A backstop; [ensureCached] refuses first, naming the directory to pre-provision.
    NetworkPolicy.require("Downloading $url", "Pre-provision the file it names.")
    ktorOkHttpClient().use { client ->
      runBlocking {
        client.prepareGet(url).execute { response ->
          if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
          dest.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
        }
      }
    }
  }

  /**
   * Ensure the cache holds the [version] + host-platform libraries, fetching when absent. Returns
   * the directory to put on `java.library.path`, or null (with a one-line note) on any skip or
   * failure. Under [NetworkPolicy]'s switch ([noNetwork]) an uncached copy throws
   * [NetworkDisabledException].
   *
   * @param version the pinned `host-natives` release ([HOST_NATIVES_VERSION]), not the CLI's.
   */
  fun ensureCached(
    version: String,
    fetcher: Fetcher = defaultFetcher,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
    log: (String) -> Unit = { System.err.println(it) },
    noNetwork: Boolean = NetworkPolicy.disabled,
    platform: String? = currentPlatformToken(),
  ): File? {
    if (platform == null) {
      log(
        "host-natives: no published build for this platform " +
          "(${System.getProperty("os.name")}/${System.getProperty("os.arch")}); skipping"
      )
      return null
    }
    val dir = cacheDir(version, platform, env, userHome)
    if (isComplete(dir)) return dir

    val url = assetUrl(version, platform)
    NetworkPolicy.require(
      "Fetching the host-natives $version libraries for the simulator",
      "Pre-provision them: `compose-preview provision --host-natives`, or unpack $url into " +
        dir.absolutePath,
      disabled = noNetwork,
    )
    val parent = dir.parentFile
    val tmp = File(parent, ".${dir.name}.dl-${UUID.randomUUID()}.tar.gz")
    val stage = File(parent, ".${dir.name}.stage-${UUID.randomUUID()}")
    return try {
      parent?.mkdirs()
      fetcher.fetchTo(url, tmp)
      stage.deleteRecursively()
      XrCompositeProvision.unpackTarGz(tmp, stage)
      if (!isComplete(stage)) {
        log("host-natives: tarball $url unpacked but a library is missing; skipping")
        return null
      }
      dir.deleteRecursively()
      try {
        Files.move(stage.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        Files.move(stage.toPath(), dir.toPath())
      }
      log("host-natives: provisioned $version/$platform into $dir")
      dir
    } catch (e: NetworkDisabledException) {
      throw e
    } catch (e: Exception) {
      log("host-natives: could not provision from $url (${e.message}); skipping")
      null
    } finally {
      tmp.delete()
      stage.deleteRecursively()
    }
  }
}
