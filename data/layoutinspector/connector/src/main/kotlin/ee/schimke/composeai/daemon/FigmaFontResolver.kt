package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Resolves a `(family, weight, italic)` to the raw **WOFF2** bytes of that face, for the
 * `compose/figma-svg` font-embedding path. WOFF2 (not the TTF the Android renderer needs) because
 * the SVG's consumers — headless Chromium in the fidelity harness and Figma on import — read WOFF2
 * natively and it's roughly half the size. Returns null when the face can't be resolved (unknown
 * family, offline, network error) so the export degrades to a named `sans-serif` rather than
 * failing.
 */
fun interface FigmaFontResolver {
  fun woff2(family: String, weight: Int, italic: Boolean): ByteArray?
}

/**
 * [FigmaFontResolver] backed by the Google Fonts CSS2 API, disk-cached alongside the renderer's own
 * font cache (`composeai.fonts.cacheDir`, a `.woff2` sibling of the `.ttf` the renderer downloads)
 * and gated by the same `composeai.fonts.offline` switch. A modern User-Agent makes the CSS2
 * endpoint serve WOFF2 (the opposite of the renderer's Android-2.3 UA trick, which forces TrueType
 * because Android can't parse WOFF2). Best-effort and side-effect-free beyond the cache write.
 */
class GoogleFontsWoff2Resolver(
  private val cacheDir: File?,
  private val offline: Boolean = false,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val httpGet: (String, String) -> ByteArray? = ::defaultFontHttpGet,
  // Bounded retries around the fetch (see [downloadWithRetry]); injectable so tests can drop the
  // sleep and drive the failure sequence deterministically.
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : FigmaFontResolver {

  override fun woff2(family: String, weight: Int, italic: Boolean): ByteArray? {
    val cacheFile = cacheDir?.let { File(it, cacheName(family, weight, italic)) }
    cacheFile
      ?.takeIf { it.exists() && it.length() > 0 }
      ?.let {
        return runCatching { fileSystem.read(it.path.toPath()) { readByteArray() } }.getOrNull()
      }
    if (offline) return null
    val bytes = downloadWithRetry(family, weight, italic) ?: return null
    cacheFile?.let { f ->
      runCatching {
        f.parentFile?.mkdirs()
        fileSystem.write(f.path.toPath()) { write(bytes) }
      }
    }
    return bytes
  }

  /**
   * [download] wrapped in bounded retries with exponential backoff. A whole-catalog render fires
   * its first font fetches the instant the daemon subprocess starts — a cold DNS/TLS path that
   * intermittently times out. A single such failure used to *permanently* degrade whichever
   * previews raced the cold start: their SVGs fell back to a named `sans-serif` (rendering with a
   * substitute typeface), while later previews — served from the now-warm disk cache — embedded the
   * real face. Retrying the transient failure keeps one slow first connection from stranding a
   * sticker. A genuinely unresolvable family (Google has no such face, so every attempt returns
   * null) just exhausts the attempts and returns null — the same degradation as before, after a
   * bounded wait.
   */
  private fun downloadWithRetry(family: String, weight: Int, italic: Boolean): ByteArray? {
    var attempt = 1
    while (true) {
      download(family, weight, italic)?.let {
        return it
      }
      if (attempt >= maxAttempts) return null
      sleep(RETRY_BASE_DELAY_MS shl (attempt - 1))
      attempt++
    }
  }

  private fun download(family: String, weight: Int, italic: Boolean): ByteArray? {
    val css =
      httpGet(cssUrl(family, weight, italic), MODERN_UA)?.toString(Charsets.UTF_8) ?: return null
    val url = firstWoff2Url(css) ?: return null
    return httpGet(url, MODERN_UA)?.takeIf { it.isNotEmpty() }
  }

  private fun cacheName(family: String, weight: Int, italic: Boolean): String =
    "${slugify(family)}-$weight${if (italic) "-italic" else ""}.woff2"

  companion object {
    /** Total fetch attempts (initial + retries) before giving up on a transient network failure. */
    const val DEFAULT_MAX_ATTEMPTS: Int = 3

    /** First retry backoff (ms); doubles per attempt — 300, 600 for the default 3 attempts. */
    private const val RETRY_BASE_DELAY_MS: Long = 300L

    private const val MODERN_UA =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    /** CSS2 request for a single face; a modern UA gets WOFF2 `src` URLs back. */
    fun cssUrl(family: String, weight: Int, italic: Boolean): String {
      @Suppress("DEPRECATION") val fam = URLEncoder.encode(family, "UTF-8").replace("+", "%20")
      val axis = if (italic) "ital,wght@1,$weight" else "wght@$weight"
      return "https://fonts.googleapis.com/css2?family=$fam:$axis&display=swap"
    }

    /**
     * Pick the WOFF2 `src` URL from a CSS2 response — preferring the `latin` subset block (the
     * common case for UI text; Google emits one `@font-face` per unicode-range subset), else the
     * first WOFF2 URL of any subset. Google's CSS2 uses single-quoted `format('woff2')`.
     */
    fun firstWoff2Url(css: String): String? {
      val woff2 = """url\((https://[^)]+)\)\s*format\('woff2'\)"""
      Regex("""/\*\s*latin\s*\*/[\s\S]*?$woff2""").find(css)?.groupValues?.get(1)?.let {
        return it
      }
      return Regex(woff2).find(css)?.groupValues?.get(1)
    }

    /** Lowercase, non-alphanumerics → `-`, trimmed — matches the renderer's font-cache slug. */
    fun slugify(name: String): String =
      buildString {
          var prevDash = true
          for (ch in name) {
            val lower = ch.lowercaseChar()
            if (lower in 'a'..'z' || lower in '0'..'9') {
              append(lower)
              prevDash = false
            } else if (!prevDash) {
              append('-')
              prevDash = true
            }
          }
        }
        .trim('-')
        .ifEmpty { "font" }
  }
}

private fun defaultFontHttpGet(url: String, userAgent: String): ByteArray? =
  runCatching {
      val conn = (URL(url).openConnection() as HttpURLConnection)
      conn.connectTimeout = 10_000
      conn.readTimeout = 15_000
      conn.setRequestProperty("User-Agent", userAgent)
      conn.inputStream.use { if (conn.responseCode == 200) it.readBytes() else null }
    }
    .getOrNull()
