package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Supplies device-art frame layers (`port_<resource>.png`) for a given Device Art Generator id.
 * Abstracted so the renderer's disk lookup can be swapped for a fixture in tests.
 */
fun interface DeviceArtSource {
  /**
   * Returns the PNG bytes of the `<artId>` frame's `<resource>` layer (e.g. `back`, `shadow`,
   * `fore`), or null when it isn't available. Callers treat a null `back` layer as "skip framing"
   * and never fail the underlying capture.
   */
  fun fetch(artId: String, resource: String): ByteArray?
}

/**
 * Reads device-art layers from the on-disk cache the Gradle plugin pre-populates. Layout:
 * `<cacheDir>/<artId>/port_<resource>.png`.
 *
 * The renderer deliberately does **no** network IO: it rides on the render subprocess classpath,
 * where an HTTP client like Ktor would drag a `kotlinx-coroutines` version that skews Compose
 * (`runBlockingK$default NoSuchMethodError` — see docs/RENDERER_COMPATIBILITY.md). Fetching is done
 * off the subprocess by `DeviceArtPrefetch` (Ktor/OkHttp) in the Gradle plugin, which fills this
 * cache before the render runs. A cache miss returns null, so framing degrades to "no frame" rather
 * than breaking the render.
 */
class CachedDeviceArtSource(
  cacheDir: String?,
  private val fileSystem: FileSystem = SystemFileSystem,
) : DeviceArtSource {

  private val cacheRoot =
    (cacheDir
        ?: (System.getProperty("java.io.tmpdir") ?: ".")
          .trimEnd('/')
          .plus("/compose-preview-device-art"))
      .toPath()

  override fun fetch(artId: String, resource: String): ByteArray? {
    val path = cacheRoot / artId / "port_$resource.png"
    if (!fileSystem.exists(path)) return null
    return runCatching { fileSystem.read(path) { readByteArray() } }
      .getOrNull()
      ?.takeIf { it.isNotEmpty() }
  }
}
