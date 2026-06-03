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
 * Auto-provisions the native `xr-composite` binary so XR "panels-in-previews" composites bake with
 * zero manual setup after a release. The CLI is the *writer* of a well-known cache that the Gradle
 * plugin's `composePreviewCompositeXr` task *reads* (see
 * `AndroidPreviewSupport.xrCompositeCacheBinaryPath` for the matching reader-side path derivation —
 * the two must stay in sync, exactly like the `BUNDLE_VERSION` / `PluginVersion` split across the
 * includeBuild boundary).
 *
 * Flow, driven from the render commands before they invoke `composePreviewRenderAll`:
 * 1. Gate on XR work — only fetch when a discovered preview is `kind == "XR_SUBSPACE"` (the CLI
 *    learns this from `previews.json` after discovery). A non-XR render never touches the network.
 * 2. If the version+platform binary is already cached, do nothing (idempotent — the common case
 *    after the first run).
 * 3. Otherwise download the per-OS Release tarball, unpack it into the cache (binary +
 *    `materials/`) and continue.
 *
 * Best-effort by contract: ANY failure (offline, 404 for a `-SNAPSHOT` with no published asset,
 * unsupported platform, corrupt archive) logs a concise note to stderr and returns without failing
 * the render — mirroring the plugin task's graceful skip. The composite still is an optional
 * capture.
 *
 * The daemon path does NOT auto-provision yet; that's a follow-up tied to the daemon actually
 * producing composites (the renderer-service RFC). Today only the CLI→Gradle path bakes composites.
 */
object XrCompositeProvision {
  /** `params.kind` value discovery stamps onto XR subspace previews in `previews.json`. */
  internal const val XR_SUBSPACE_KIND = "XR_SUBSPACE"

  /**
   * Pure platform token derivation from JVM `os.name` / `os.arch`, matching the Release asset
   * matrix in `.github/workflows/release.yml` (`build-xr-composite`):
   * - linux + x86_64/amd64 → `linux-x86_64`
   * - mac + aarch64/arm64 → `macos-arm64`
   * - windows + amd64/x86_64 → `windows-x86_64`
   *
   * Returns `null` for any other combination (e.g. linux-arm64, mac-x86_64) — no asset is published
   * for it, so the caller skips provisioning rather than fetching a 404. Kept pure (params, not
   * `System.getProperty`) so unit tests can exercise every matrix cell.
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
   * Release asset filename for a version + platform — `xr-composite-<platform>-<version>.tar.gz`.
   * Exactly the name `release.yml` packs (`xr-composite-${asset}-${PLUGIN_VERSION}.tar.gz`).
   */
  internal fun assetName(version: String, platform: String): String =
    "xr-composite-$platform-$version.tar.gz"

  /**
   * Download URL on the GitHub Release tagged `v<version>`. SNAPSHOT versions have no published
   * release, so this 404s and the caller falls through to a graceful skip.
   */
  internal fun assetUrl(version: String, platform: String): String =
    "https://github.com/$REPO/releases/download/v$version/${assetName(version, platform)}"

  /**
   * Root of the shared, well-known cache: `${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite`.
   * Honours `XDG_CACHE_HOME` (Linux/BSD), else `~/.cache` per the XDG default — the same convention
   * the plugin-side reader derives. [env]/[userHome] are injectable for tests.
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

  /**
   * Cached binary path: `<cacheDir>/xr-composite` (`xr-composite.exe` on Windows). The plugin reads
   * the exact same path — see the reader-side derivation.
   */
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
   * Whether [dir] holds a *complete* provisioned layout — the [binaryName] executable AND a
   * non-empty `materials/` directory (the `.filamat` blobs the binary loads, and which the plugin
   * task passes as the sibling `--materials` dir). A partial extraction (binary written but
   * `materials/` missing/empty — e.g. an interrupted or failed `tar`) is deliberately NOT complete,
   * so it is re-provisioned rather than trusted forever. Without this, a partial cache would short-
   * circuit every future run and make composites fail until the user manually wiped the cache.
   */
  internal fun isComplete(dir: File, binaryName: String): Boolean {
    val materials = File(dir, "materials")
    return File(dir, binaryName).isFile &&
      materials.isDirectory &&
      (materials.listFiles()?.isNotEmpty() == true)
  }

  /**
   * Fetch + unpack seam, injectable so tests exercise the "already cached" / "download failure"
   * branches without hitting GitHub. [fetchTo] downloads [url] to a destination file, throwing on a
   * non-2xx / network error.
   */
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
   * Ensure the cache holds the [version]+host-platform binary, fetching the Release tarball when
   * absent. Best-effort: returns the cached binary [File] on success, or `null` on any skip/failure
   * (and logs a one-line note). Never throws — the render proceeds regardless.
   *
   * @param version the CLI's own released version ([BUNDLE_VERSION]); the binary ships from the
   *   same tag.
   * @param log sink for the concise status/skip note (stderr by default).
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
    // Fast path: only short-circuit on a COMPLETE cache. A partial layout (e.g. an earlier
    // interrupted unpack that left the binary but no materials/) falls through and re-provisions.
    if (isComplete(dir, binaryName)) return binary

    val url = assetUrl(version, platform)
    val parent = dir.parentFile
    val tmp = File(parent, ".${dir.name}.dl-${UUID.randomUUID()}.tar.gz")
    // Unpack into a staging dir and only swap it into place once validated, so the live cache path
    // is never observed half-written — readers see either a complete layout or nothing.
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
      // Atomically replace any prior (incomplete) cache dir with the validated staging dir. We only
      // get here when `dir` wasn't complete, so removing it first is safe; the move is atomic on
      // the
      // shared cache filesystem so a concurrent reader never sees a partial swap.
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
   * Unpack a `.tar.gz` into [destDir] (created if absent) by shelling out to the system `tar`. The
   * Release tarball is `tar -C dist` of `xr-composite[.exe]` + `materials/`, so `tar -xzf <tgz> -C
   * <destDir>` restores that layout (including the binary's executable bit) verbatim. `tar` is
   * present on Linux/macOS and ships as `bsdtar` on Windows 10+; if it's missing or fails, the
   * thrown exception bubbles to [ensureCached]'s best-effort catch. Kept as a shell-out rather than
   * pulling in a new archive dependency for one call site.
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
