package ee.schimke.composeai.renderer.xr.client

import java.io.File

/**
 * Resolves the native `xr-composite` binary + its compiled materials directory for the server-mode
 * client. Mirrors the precedence the Gradle plugin / CLI use for the one-shot tool, minus the
 * Gradle property layer (this runs outside Gradle): explicit env override, then the shared
 * provisioning cache.
 *
 * 1. `XR_COMPOSITE_BIN` — explicit path to the binary (and `XR_COMPOSITE_MATERIALS` for its
 *    `materials/` dir; defaults to a `materials` sibling of the binary).
 * 2. The shared cache the CLI provisions into:
 *    `${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/`.
 *
 * Returns `null` when nothing resolves to an existing file — callers degrade gracefully (the XR
 * render service is best-effort, exactly like the one-shot composite).
 */
public object XrCompositeBinary {

  /** Resolve the binary, or `null` if unavailable. [version]/[platform] address the cache layer. */
  public fun resolve(
    env: (String) -> String? = System::getenv,
    version: String? = null,
    platform: String = currentPlatform(),
  ): File? {
    env("XR_COMPOSITE_BIN")?.let { p ->
      val f = File(p)
      if (f.isFile) return f
    }
    if (version != null) {
      val cached = cacheRoot(env).resolve("$version/$platform/${binaryName(platform)}")
      if (cached.isFile) return cached
    }
    return null
  }

  /**
   * Resolve the materials directory: `XR_COMPOSITE_MATERIALS`, else a `materials` sibling of
   * [binary]. Returns `null` if neither exists.
   */
  public fun resolveMaterials(binary: File, env: (String) -> String? = System::getenv): File? {
    env("XR_COMPOSITE_MATERIALS")?.let { p ->
      val f = File(p)
      if (f.isDirectory) return f
    }
    val sibling = binary.parentFile?.resolve("materials")
    return sibling?.takeIf { it.isDirectory }
  }

  private fun cacheRoot(env: (String) -> String?): File {
    val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
    return File(base, "composeai/xr-composite")
  }

  private fun binaryName(platform: String): String =
    if (platform.startsWith("windows")) "xr-composite.exe" else "xr-composite"

  /** The provisioning platform key, e.g. `linux-x86_64` / `macos-arm64` / `windows-x86_64`. */
  public fun currentPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osKey =
      when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("win") -> "windows"
        else -> "linux"
      }
    val archKey =
      when {
        arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
        else -> "x86_64"
      }
    return "$osKey-$archKey"
  }
}
