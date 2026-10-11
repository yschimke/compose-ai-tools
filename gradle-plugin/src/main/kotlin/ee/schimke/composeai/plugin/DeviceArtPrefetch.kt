package ee.schimke.composeai.plugin

import java.io.File
import java.time.Duration
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.logging.Logger

/**
 * Downloads device-art bezel layers into the cache the renderer reads
 * (`ee.schimke.composeai.daemon.CachedDeviceArtSource`), in the Gradle JVM rather than the render
 * subprocess (an HTTP client's coroutines would skew Compose there).
 *
 * The frame → layers table mirrors the renderer's `DeviceArtCatalog` to avoid a cross-build
 * dependency; keep them in sync. A missed frame just renders unframed. Best-effort: failed layers
 * are logged and skipped, cached ones not re-fetched.
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
    // OkHttp, not Ktor: Ktor 3.x needs kotlinx-coroutines >= 1.10 and fails on the Gradle daemon's
    // older copy (`Job.invokeOnCompletion$default NoSuchMethodError`).
    val timeout = Duration.ofSeconds(20)
    val client =
      OkHttpClient.Builder()
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .callTimeout(timeout)
        .retryOnConnectionFailure(true)
        .build()
    for (artId in needed) {
      for (resource in FRAMES.getValue(artId)) {
        val dest = File(File(cacheDir, artId), "port_$resource.png")
        if (dest.isFile && dest.length() > 0) continue
        try {
          val request =
            Request.Builder()
              .url("$baseUrl/$artId/port_$resource.png")
              .header("User-Agent", "compose-preview-device-frame")
              .build()
          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
              logger?.warn("device-art prefetch: HTTP ${response.code} for $artId/$resource")
              return@use
            }
            val bytes = response.body.bytes()
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
          }
        } catch (t: Throwable) {
          // Visible by default: a silent prefetch failure means previews render un-framed with no
          // hint why.
          logger?.warn(
            "device-art prefetch failed for $artId/$resource: " +
              "${t.javaClass.simpleName}: ${t.message}"
          )
        }
      }
    }
  }
}
