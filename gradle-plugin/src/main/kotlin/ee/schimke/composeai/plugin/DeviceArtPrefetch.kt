package ee.schimke.composeai.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger

/**
 * Downloads device-art bezel layers into the on-disk cache the renderer reads
 * (`ee.schimke.composeai.daemon.CachedDeviceArtSource`). Runs in the Gradle daemon JVM via **Ktor
 * over the OkHttp engine** — the repo-standard HTTP stack — deliberately *off* the render
 * subprocess, whose classpath can't carry Ktor's `kotlinx-coroutines` without skewing Compose
 * (`runBlockingK$default NoSuchMethodError`; see docs/RENDERER_COMPATIBILITY.md).
 *
 * The frame → layers table is a small mirror of `DeviceArtCatalog` (the renderer-side source of
 * truth) kept here so the plugin build doesn't take a cross-build project dependency. Keep the two
 * in sync when adding a frame; a frame the prefetch misses just means the renderer finds no cached
 * `back` layer and skips framing (graceful, never a build failure).
 *
 * Best-effort throughout: a failed layer is logged and skipped; already-cached layers aren't
 * re-fetched.
 */
object DeviceArtPrefetch {

  const val AUTO = "auto"

  const val DEFAULT_BASE_URL =
    "https://developer.android.com/distribute/marketing-tools/device-art-resources"

  /** artId -> layer resources to fetch (`port_<resource>.png`). Mirror of DeviceArtCatalog. */
  private val FRAMES: Map<String, List<String>> =
    mapOf(
      "wear_round" to listOf("back"),
      "wear_square" to listOf("back"),
      "pixel_5" to listOf("shadow", "back", "fore"),
    )

  /** Shared cache directory, forwarded to the renderer as `composeai.deviceframe.cacheDir`. */
  fun defaultCacheDir(): File =
    File(System.getProperty("java.io.tmpdir") ?: ".", "compose-preview-device-art")

  /**
   * The frame ids a `composeai.deviceframe.device` value needs: all frames for `auto`, else one.
   */
  fun artIdsFor(device: String): List<String> {
    val d = device.trim()
    if (d.isEmpty()) return emptyList()
    return if (d.equals(AUTO, ignoreCase = true)) FRAMES.keys.toList()
    else if (FRAMES.containsKey(d)) listOf(d) else emptyList()
  }

  fun prefetchInto(
    cacheDir: File,
    artIds: List<String>,
    baseUrl: String = DEFAULT_BASE_URL,
    logger: Logger? = null,
  ) {
    val needed = artIds.filter { FRAMES.containsKey(it) }
    if (needed.isEmpty()) return
    HttpClient(OkHttp).use { client ->
      runBlocking {
        for (artId in needed) {
          for (resource in FRAMES.getValue(artId)) {
            val dest = File(File(cacheDir, artId), "port_$resource.png")
            if (dest.isFile && dest.length() > 0) continue
            try {
              val response = client.get("$baseUrl/$artId/port_$resource.png")
              if (!response.status.isSuccess()) {
                logger?.info(
                  "device-art prefetch: HTTP ${response.status.value} for $artId/$resource"
                )
                continue
              }
              val bytes = response.readRawBytes()
              dest.parentFile?.mkdirs()
              dest.writeBytes(bytes)
            } catch (t: Throwable) {
              logger?.info("device-art prefetch failed for $artId/$resource: ${t.message}")
            }
          }
        }
      }
    }
  }
}
