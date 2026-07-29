package ee.schimke.composeai.fonts.google

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * A single resolved Google font file keyed by family + axes. Serialised on disk as
 * `<slug>-<weight>[-italic].ttf` so the cache is human-readable under `~/.cache/composeai/fonts/`.
 *
 * [weight] is a plain CSS numeric weight rather than a Compose `FontWeight`: the two render lanes
 * that resolve fonts through here reach it from different worlds — one from a Compose
 * `Font(GoogleFont(...))` request, one from a `RemoteFontFamily.Named` string in an `.rc` document
 * — and neither should have to adopt the other's type to ask for a file.
 */
data class GoogleFontKey(val name: String, val weight: Int, val italic: Boolean) {
  fun fileName(): String {
    val slug = slugify(name)
    val italicPart = if (italic) "-italic" else ""
    return "$slug-$weight$italicPart.ttf"
  }

  companion object {
    /** Lowercase + replace non-alphanumerics with `-`, no leading/trailing hyphens. */
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
        // A name with no alphanumerics at all would otherwise slug to "", giving every such family
        // the same `-400.ttf` cache filename.
        .ifEmpty { "font" }
  }
}

/** Where a resolved font file comes from. Swappable so tests never touch the network. */
interface GoogleFontSource {
  fun load(key: GoogleFontKey): File?
}

/**
 * A machine-local cache of resolved TTFs, downloading on a miss.
 *
 * The cache is shared across projects on purpose — a font keyed by `(family, weight, italic)` is
 * identical everywhere — so it resolves once per machine and every later render reuses it.
 * [offline] turns a miss into a null rather than a fetch, for deliberately air-gapped renders.
 */
class GoogleFontCache(
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
 * Two-stage lookup:
 * 1. Try `wght@<exact>` — works for static families (Roboto, Lobster Two) and for the default
 *    weight of variable families.
 * 2. If that request *succeeded but carried no TTF URL* (purely-variable fonts like Roboto Flex
 *    reject single-weight requests at non-default weights), retry with `wght@<min>..<max>` covering
 *    the full 1–1000 range. For variable fonts the response is a single `@font-face` pointing at
 *    the variable TTF; for static fonts it's multiple blocks and we pick the closest to the
 *    requested weight.
 *
 * The stage-1/stage-2 distinction is load-bearing for reproducibility, which is why a *failed*
 * stage-1 request returns false rather than falling through. The two stages can legitimately
 * resolve the same `(family, weight, italic)` to different faces — a static sub-font at the exact
 * weight vs the family's variable TTF — and those have different text metrics. Falling through on a
 * network error therefore let a transient blip resolve a key to the other face, and because the
 * result is cached under the same filename, that face then stuck for every later render on the
 * machine. Consumers saw it as a whole-text-layer sub-pixel shift appearing and disappearing
 * between CI runs on unrelated commits. Failing instead routes through the caller's usual
 * unresolved-font path.
 *
 * The CSS2 endpoint serves WOFF2 by default (Android doesn't parse WOFF2 natively), so we send an
 * Android-2.3 User-Agent — one of the few UAs for which the API still returns TrueType. Same
 * mechanism `google-webfonts-helper` and similar offline caches rely on.
 */
fun downloadFromGoogleFonts(
  key: GoogleFontKey,
  destination: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  // A null here is a *failed request*, not "this family has no TTF at this weight" — don't let it
  // silently pick up the range query's (differently-metricked) answer. See the KDoc.
  val exactCss = httpGet(buildCssUrl(key), TTF_USER_AGENT) ?: return false
  val url =
    extractFirstTruetypeUrl(exactCss)
      ?: run {
        val rangeCss = httpGet(buildRangeCssUrl(key), TTF_USER_AGENT) ?: return false
        pickClosestTruetypeUrl(rangeCss, key.weight) ?: return false
      }
  val bytes = httpGetBytes(url, userAgent = TTF_USER_AGENT) ?: return false
  if (bytes.isEmpty()) return false
  destination.parentFile?.mkdirs()
  fileSystem.write(destination.path.toPath()) { write(bytes) }
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

fun buildCssUrl(key: GoogleFontKey): String =
  buildCssUrlForAxis(key, if (key.italic) "ital,wght@1,${key.weight}" else "wght@${key.weight}")

/**
 * Range-query variant. Returns a CSS response with either one `@font-face` (variable font) or many
 * (static families with multiple pre-rendered weights). Used as a fallback when [buildCssUrl]
 * produced no TTF URL — purely variable families like Roboto Flex reject single-weight queries at
 * non-default weights.
 *
 * `100..1000` is the conventional Google Fonts wght axis range and works for Roboto Flex (wght
 * 100..1000), Google Sans Flex (wght 100..1000), and other variable families. Ranges outside a
 * family's declared axis bounds (e.g. `1..1000` on Roboto Flex) return a 400 "Font family not
 * found" HTML page, which our TTF regex correctly treats as a miss.
 */
fun buildRangeCssUrl(key: GoogleFontKey): String =
  buildCssUrlForAxis(key, if (key.italic) "ital,wght@1,100..1000" else "wght@100..1000")

private fun buildCssUrlForAxis(key: GoogleFontKey, axis: String): String {
  // `URLEncoder.encode(s, Charset)` is API 33+. The renderer runs inside
  // Robolectric on JDK 17 where both overloads exist, but the library's
  // `minSdk = 24` trips `lint`. The legacy `encode(s, charsetName)`
  // overload is unchanged and the round-trip is identical.
  @Suppress("DEPRECATION") val family = URLEncoder.encode(key.name, "UTF-8").replace("+", "%20")
  return "https://fonts.googleapis.com/css2?family=$family:$axis&display=swap"
}

fun extractFirstTruetypeUrl(css: String): String? {
  // Matches `url(...) format('truetype')` inside an `@font-face` block.
  val regex = Regex("""url\((https://[^)]+)\)\s*format\(['"]truetype['"]\)""")
  return regex.find(css)?.groupValues?.get(1)
}

/**
 * Parse a CSS response with one-or-many `@font-face` blocks and pick the TTF URL whose declared
 * `font-weight` is closest to [requestedWeight].
 *
 * - Variable-font responses have a single block with `font-weight: 400` but the TTF itself supports
 *   the full axis range — just return that URL.
 * - Static-family range responses carry one block per discrete weight (100, 200, …, 900); pick the
 *   nearest and the consumer's text renders in the closest existing static sub-font.
 */
fun pickClosestTruetypeUrl(css: String, requestedWeight: Int): String? {
  val blockRegex =
    Regex("""font-weight:\s*(\d+)[\s\S]*?url\((https://[^)]+)\)\s*format\(['"]truetype['"]\)""")
  val matches = blockRegex.findAll(css).toList()
  if (matches.isEmpty()) return extractFirstTruetypeUrl(css)
  if (matches.size == 1) return matches[0].groupValues[2]
  return matches
    .minByOrNull { kotlin.math.abs(it.groupValues[1].toInt() - requestedWeight) }
    ?.groupValues
    ?.get(2)
}

private val fontHttpClient: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
}

private fun httpGet(url: String, userAgent: String): String? =
  httpGetBytes(url, userAgent)?.toString(Charsets.UTF_8)

private fun httpGetBytes(url: String, userAgent: String): ByteArray? =
  runCatching {
      val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
      fontHttpClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.bytes() else null
      }
    }
    .getOrNull()
