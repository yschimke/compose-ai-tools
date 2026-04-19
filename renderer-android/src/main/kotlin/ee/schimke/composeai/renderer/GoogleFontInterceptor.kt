package ee.schimke.composeai.renderer

import androidx.compose.ui.text.font.FontWeight
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Cache and CSS-API helpers that underpin [ShadowFontsContractCompat].
 *
 * `Font(GoogleFont("Lato"), provider)` on a real device goes through
 * `androidx.core.provider.FontsContractCompat.requestFont` → GMS Fonts'
 * ContentProvider. That provider doesn't exist in the Robolectric sandbox:
 * no `com.google.android.gms` package, no registered content provider for
 * `com.google.android.gms.fonts`. Compose's internal `GoogleFontTypefaceLoader`
 * swallows the failure into its async fallback and text silently renders in
 * the platform default (Roboto) — which is why consumers hit the "my
 * downloadable fonts aren't applied in screenshots" symptom when they try to
 * capture previews that use their production GoogleFont typography.
 *
 * The fix ships as a Robolectric shadow ([ShadowFontsContractCompat]) that
 * intercepts `requestFont` before the provider lookup even runs: parse the
 * `FontRequest.query` (the same wire format Compose's `GoogleFont.kt`
 * builds), resolve a TTF from a local cache keyed by `(name, weight, italic)`,
 * and call the supplied callback synchronously with a [Typeface.createFromFile].
 *
 * The cache lives under `.compose-preview-history/fonts/` so downloaded
 * fonts commit alongside baselines — first author run over the network, every
 * subsequent run (author + CI) offline. The cache directory is plumbed via
 * the `composeai.fonts.cacheDir` system property by the plugin's
 * `renderPreviews` `Test` task.
 *
 * Consumer code is unchanged: the same `Font(GoogleFont(...))` that runs on
 * device renders under Robolectric with zero `src/debug` fork, zero
 * `testImplementation` opt-in, zero plugin configuration.
 */
internal object GoogleFontCacheAccess {
    /**
     * The shadow reads this once at the first incoming `requestFont` call.
     * Cached so repeated lookups are allocation-free; re-read is never
     * needed because the system property is pinned for the Test task's
     * lifetime.
     */
    private val cache: GoogleFontSource? by lazy {
        val cacheDirPath = System.getProperty("composeai.fonts.cacheDir") ?: return@lazy null
        val offline = System.getProperty("composeai.fonts.offline")?.lowercase() == "true"
        GoogleFontCache(File(cacheDirPath), offline = offline)
    }

    fun load(name: String, weight: Int, italic: Boolean): File? =
        cache?.load(GoogleFontKey(name, FontWeight(weight), italic))
}

/**
 * Represents a single resolved Google font file keyed by family + axes.
 * Serialised on disk as `<slug>-<weight>[-italic].ttf` so the cache is
 * human-readable and diffable under `.compose-preview-history/fonts/`.
 */
internal data class GoogleFontKey(
    val name: String,
    val weight: FontWeight,
    val italic: Boolean,
) {
    fun fileName(): String {
        val slug = slugify(name)
        val italicPart = if (italic) "-italic" else ""
        return "$slug-${weight.weight}$italicPart.ttf"
    }

    companion object {
        /** Lowercase + replace non-alphanumerics with `-`, no leading/trailing hyphens. */
        fun slugify(name: String): String = buildString {
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
        }.trim('-').ifEmpty { "font" }
    }
}

/**
 * Abstraction over "hand me a cached TTF for `(name, weight, italic)`" so
 * tests can stub the download path with a preseeded directory.
 */
internal interface GoogleFontSource {
    fun load(key: GoogleFontKey): File?
}

/**
 * Disk-backed [GoogleFontSource]. Downloads missing TTFs from the Google
 * Fonts CSS API on first access, then reuses the on-disk copy forever.
 *
 * Two knobs, both off the same system-property surface the rest of the
 * renderer uses:
 *  - `composeai.fonts.cacheDir` — directory root.
 *  - `composeai.fonts.offline` — when `true`, skip network on cache miss
 *    so the render shows the fallback font instead of silently fetching
 *    from a non-deterministic endpoint.
 */
internal class GoogleFontCache(
    private val cacheDir: File,
    private val offline: Boolean = false,
    private val downloader: (GoogleFontKey, File) -> Boolean = ::downloadFromGoogleFonts,
) : GoogleFontSource {

    override fun load(key: GoogleFontKey): File? {
        val file = File(cacheDir, key.fileName())
        if (file.exists() && file.length() > 0) return file
        if (offline) return null
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${file.name}.tmp")
        val ok = runCatching { downloader(key, tmp) }.getOrDefault(false)
        if (!ok || !tmp.exists() || tmp.length() == 0L) {
            tmp.delete()
            return null
        }
        if (!tmp.renameTo(file)) {
            // Atomic rename can fail across filesystems. Fall back to copy.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }
}

/**
 * Fetches a TTF for [key] into [destination]. Returns `true` on success.
 *
 * The CSS2 endpoint serves WOFF2 by default (Android doesn't parse WOFF2
 * natively), so we send an IE-like User-Agent — one of the few UAs for which
 * the API still returns TrueType. Same mechanism `google-webfonts-helper`
 * and similar offline caches rely on; not a hack in the
 * undocumented-behaviour sense, just "ask for a format the server already
 * knows how to produce."
 */
internal fun downloadFromGoogleFonts(key: GoogleFontKey, destination: File): Boolean {
    val cssUrl = buildCssUrl(key)
    val css = httpGet(cssUrl, userAgent = TTF_USER_AGENT) ?: return false
    val ttfUrl = extractFirstTruetypeUrl(css) ?: return false
    val bytes = httpGetBytes(ttfUrl, userAgent = TTF_USER_AGENT) ?: return false
    if (bytes.isEmpty()) return false
    destination.parentFile?.mkdirs()
    destination.writeBytes(bytes)
    return true
}

// The CSS2 endpoint picks the `src: url(...) format(...)` format based on
// User-Agent capabilities. Modern UAs get WOFF2 (Android can't parse it
// natively); IE11 gets WOFF (same problem); the only UAs that reliably
// produce `format('truetype')` are pre-KitKat Android variants — legacy
// devices predating native WOFF2 support. Using a fixed Android 2.3 UA is
// the same approach `google-webfonts-helper` settled on for its "TTF only"
// download mode.
private const val TTF_USER_AGENT =
    "Mozilla/5.0 (Linux; U; Android 2.3.3; en-us) AppleWebKit/533.1 (KHTML, like Gecko)"

internal fun buildCssUrl(key: GoogleFontKey): String {
    // `URLEncoder.encode(s, Charset)` is API 33+. The renderer runs inside
    // Robolectric on JDK 17 where both overloads exist, but the library's
    // `minSdk = 24` trips `lint`. The legacy `encode(s, charsetName)`
    // overload is unchanged and the round-trip is identical.
    @Suppress("DEPRECATION")
    val family = URLEncoder.encode(key.name, "UTF-8").replace("+", "%20")
    val axis = if (key.italic) {
        "ital,wght@1,${key.weight.weight}"
    } else {
        "wght@${key.weight.weight}"
    }
    return "https://fonts.googleapis.com/css2?family=$family:$axis&display=swap"
}

internal fun extractFirstTruetypeUrl(css: String): String? {
    // Matches `url(...) format('truetype')` inside an `@font-face` block.
    val regex = Regex("""url\((https://[^)]+)\)\s*format\(['"]truetype['"]\)""")
    return regex.find(css)?.groupValues?.get(1)
}

private fun httpGet(url: String, userAgent: String): String? = runCatching {
    val conn = openConnection(url, userAgent)
    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}.getOrNull()

private fun httpGetBytes(url: String, userAgent: String): ByteArray? = runCatching {
    val conn = openConnection(url, userAgent)
    conn.inputStream.use { it.readBytes() }
}.getOrNull()

private fun openConnection(url: String, userAgent: String): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 10_000
    conn.readTimeout = 15_000
    conn.setRequestProperty("User-Agent", userAgent)
    conn.instanceFollowRedirects = true
    return conn
}

/**
 * Parses the `FontRequest.query` wire format that Compose's `GoogleFont.kt`
 * builds into a [GoogleFontKey].
 *
 * Expected shape (from `androidx.compose.ui.text.googlefonts.GoogleFont`):
 * ```
 * name=<urlencoded>&weight=<int>&width=<float>&italic=<0.0|1.0>&besteffort=<bool>
 * ```
 * Any missing field falls back to sensible defaults so a slightly different
 * query shape (older or newer Compose, non-Compose callers) still resolves.
 */
internal fun parseFontRequestQuery(query: String?): GoogleFontKey? {
    query ?: return null
    val pairs = query.split('&').mapNotNull { pair ->
        val idx = pair.indexOf('=').takeIf { i -> i > 0 } ?: return@mapNotNull null
        val key = pair.substring(0, idx)
        val raw = pair.substring(idx + 1)
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        key to value
    }.toMap()
    val name = pairs["name"]?.takeIf { it.isNotBlank() } ?: return null
    val weight = pairs["weight"]?.toIntOrNull() ?: 400
    val italic = pairs["italic"]?.toFloatOrNull()?.let { it >= 0.5f } ?: false
    return GoogleFontKey(name, FontWeight(weight), italic)
}
