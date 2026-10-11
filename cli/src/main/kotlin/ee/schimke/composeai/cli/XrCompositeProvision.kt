package ee.schimke.composeai.cli

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
 * Auto-provisions the native `xr-composite` binary into the cache the plugin's
 * `composePreviewCompositeXr` task reads (keep in sync with
 * `AndroidPreviewSupport.xrCompositeCacheBinaryPath`).
 *
 * Before `composePreviewRenderAll`:
 * 1. Only fetch when a discovered preview is `XR_SUBSPACE`; non-XR renders never touch the network.
 * 2. Do nothing if the pinned version + platform is already cached.
 * 3. Otherwise download the per-OS release tarball and unpack it (binary + `materials/`).
 *
 * Best-effort: any failure logs a note and the render proceeds without composites. The daemon path
 * doesn't auto-provision yet.
 */
object XrCompositeProvision {
  /** `params.kind` value discovery stamps onto XR subspace previews in `previews.json`. */
  internal const val XR_SUBSPACE_KIND = "XR_SUBSPACE"

  /**
   * Platform token from `os.name` / `os.arch`, matching the release matrix of [XR_COMPOSITE_REPO]:
   * - linux + x86_64/amd64 → `linux-x86_64`
   * - mac + aarch64/arm64 → `macos-arm64`
   * - windows + amd64/x86_64 → `windows-x86_64`
   *
   * Null for anything else (no asset is published). Pure for testing.
   */
  internal fun platformToken(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    return when {
      os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x86_64"
      (os.contains("mac") || os.contains("darwin")) && (arch == "aarch64" || arch == "arm64") ->
        "macos-arm64"
      os.contains("windows") && (arch == "amd64" || arch == "x86_64") -> "windows-x86_64"
      else -> null
    }
  }

  /** Current host's platform token, or `null` when no Release asset targets it. */
  internal fun currentPlatformToken(): String? =
    platformToken(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")

  /**
   * Release asset filename, `xr-composite-<platform>-<version>.tar.gz`, as `release.yml` packs it.
   */
  internal fun assetName(version: String, platform: String): String =
    "xr-composite-$platform-$version.tar.gz"

  /**
   * Download URL on the [XR_COMPOSITE_REPO] release `v<version>`, where `version` is the pinned
   * `xr-composite` release, not the CLI's. A missing asset 404s and is skipped.
   */
  internal fun assetUrl(version: String, platform: String): String =
    "https://github.com/$XR_COMPOSITE_REPO/releases/download/v$version/${assetName(version, platform)}"

  /**
   * `${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite`, as the plugin-side reader derives it.
   * [env]/[userHome] are injectable for tests.
   */
  internal fun cacheRoot(
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
  ): File {
    val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) File(xdg) else File(userHome, ".cache")
    return File(base, "composeai/xr-composite")
  }

  /** Per-version+platform cache directory: `<cacheRoot>/<version>/<platform>`. */
  internal fun cacheDir(
    version: String,
    platform: String,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
  ): File = File(File(cacheRoot(env, userHome), version), platform)

  /** `<cacheDir>/xr-composite` (`.exe` on Windows); the plugin reads the same path. */
  internal fun cacheBinary(
    version: String,
    platform: String,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
  ): File {
    val name = if (platform.startsWith("windows")) "xr-composite.exe" else "xr-composite"
    return File(cacheDir(version, platform, env, userHome), name)
  }

  /**
   * Whether [dir] holds a complete layout: the [binaryName] executable and a non-empty `materials/`
   * (the `.filamat` blobs). A partial extraction is re-provisioned rather than trusted forever.
   */
  internal fun isComplete(dir: File, binaryName: String): Boolean {
    val materials = File(dir, "materials")
    return File(dir, binaryName).isFile &&
      materials.isDirectory &&
      (materials.listFiles()?.isNotEmpty() == true)
  }

  /** Fetch seam for tests. [fetchTo] downloads [url], throwing on non-2xx or network errors. */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  /** Default fetcher: Ktor over OkHttp (follows redirects), streaming the body to disk. */
  internal val defaultFetcher = Fetcher { url, dest ->
    HttpClient(OkHttp).use { client ->
      runBlocking {
        client.prepareGet(url).execute { response ->
          if (!response.status.isSuccess()) {
            error("HTTP ${response.status.value}")
          }
          dest.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
        }
      }
    }
  }

  /**
   * Ensure the cache holds the [version] + host-platform binary, fetching when absent. Returns it,
   * or null (with a one-line note) on any skip or failure; never throws.
   *
   * @param version the pinned `xr-composite` release ([XR_COMPOSITE_VERSION]), not the CLI's
   * version.
   * @param log sink for the status note (stderr by default).
   */
  fun ensureCached(
    version: String,
    fetcher: Fetcher = defaultFetcher,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
    log: (String) -> Unit = { System.err.println(it) },
  ): File? {
    val platform =
      currentPlatformToken()
        ?: run {
          log(
            "xr-composite: no published binary for this platform " +
              "(${System.getProperty("os.name")}/${System.getProperty("os.arch")}); " +
              "skipping XR composite provisioning"
          )
          return null
        }
    val binaryName = if (platform.startsWith("windows")) "xr-composite.exe" else "xr-composite"
    val dir = cacheDir(version, platform, env, userHome)
    val binary = File(dir, binaryName)
    // Only a complete cache short-circuits; a partial one re-provisions.
    if (isComplete(dir, binaryName)) return binary

    val url = assetUrl(version, platform)
    val parent = dir.parentFile
    val tmp = File(parent, ".${dir.name}.dl-${UUID.randomUUID()}.tar.gz")
    // Unpack to a staging dir and move it into place once validated, so readers never see a partial
    // cache.
    val stage = File(parent, ".${dir.name}.stage-${UUID.randomUUID()}")
    return try {
      parent?.mkdirs()
      fetcher.fetchTo(url, tmp)
      stage.deleteRecursively()
      unpackTarGz(tmp, stage)
      if (!isComplete(stage, binaryName)) {
        log(
          "xr-composite: tarball $url unpacked but layout incomplete (binary or materials/ missing); skipping"
        )
        return null
      }
      File(stage, binaryName).setExecutable(true, false)
      // `dir` is incomplete here, so replacing it is safe; the move is atomic on the cache
      // filesystem.
      dir.deleteRecursively()
      try {
        Files.move(stage.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        Files.move(stage.toPath(), dir.toPath()) // fall back to a non-atomic move if unsupported
      }
      log("xr-composite: provisioned $version/$platform into $dir")
      binary
    } catch (e: Exception) {
      // Offline / 404 (SNAPSHOT or missing asset) / corrupt archive — all best-effort skips.
      log("xr-composite: could not provision from $url (${e.message}); skipping composite stills")
      null
    } finally {
      tmp.delete()
      stage.deleteRecursively()
    }
  }

  /**
   * Unpack a `.tar.gz` into [destDir] with the system `tar` (bsdtar on Windows 10+), preserving the
   * executable bit. Failures propagate to [ensureCached]'s best-effort catch.
   */
  internal fun unpackTarGz(tarGz: File, destDir: File) {
    destDir.mkdirs()
    val proc =
      ProcessBuilder("tar", "-xzf", tarGz.absolutePath, "-C", destDir.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) error("tar exited $code: ${output.trim().takeLast(300)}")
  }
}
