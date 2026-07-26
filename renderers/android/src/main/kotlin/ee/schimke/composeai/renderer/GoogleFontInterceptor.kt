package ee.schimke.composeai.renderer

import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Cache and CSS-API helpers that underpin [ShadowFontsContractCompat].
 *
 * `Font(GoogleFont("Lato"), provider)` on a real device goes through
 * `androidx.core.provider.FontsContractCompat.requestFont` → GMS Fonts' ContentProvider. That
 * provider doesn't exist in the Robolectric sandbox: no `com.google.android.gms` package, no
 * registered content provider for `com.google.android.gms.fonts`. Compose's internal
 * `GoogleFontTypefaceLoader` swallows the failure into its async fallback and text silently renders
 * in the platform default (Roboto) — which is why consumers hit the "my downloadable fonts aren't
 * applied in screenshots" symptom when they try to capture previews that use their production
 * GoogleFont typography.
 *
 * The fix ships as a Robolectric shadow ([ShadowFontsContractCompat]) that intercepts `requestFont`
 * before the provider lookup even runs: parse the `FontRequest.query` (the same wire format
 * Compose's `GoogleFont.kt` builds), resolve a TTF from a local cache keyed by `(name, weight,
 * italic)`, and call the supplied callback synchronously with a [Typeface.createFromFile].
 *
 * The cache lives in a shared, machine-local directory (`$XDG_CACHE_HOME/composeai/fonts`, else
 * `~/.cache/composeai/fonts`) — a font keyed by `(family, weight, italic)` is identical across
 * projects, so it resolves once per machine and is reused by every render thereafter. The cache
 * directory is plumbed via the `composeai.fonts.cacheDir` system property by the plugin's
 * `composePreviewRender` `Test` task.
 *
 * Consumer code is unchanged: the same `Font(GoogleFont(...))` that runs on device renders under
 * Robolectric with zero `src/debug` fork, zero `testImplementation` opt-in, zero plugin
 * configuration.
 */
internal object GoogleFontCacheAccess {
  /**
   * The shadow reads this once at the first incoming `requestFont` call. Cached so repeated lookups
   * are allocation-free; re-read is never needed because the system property is pinned for the Test
   * task's lifetime.
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
 * Render-time surfacing for downloadable-font resolution failures.
 *
 * A `Font(GoogleFont(...))` that can't be resolved — offline, no cache dir, a failed download, or a
 * family/weight Google serves no TTF for — is reported to Compose by [ShadowFontsContractCompat],
 * which then *silently* substitutes the platform default (Roboto). That's the "my branded fonts
 * aren't applied in screenshots" symptom: a preview that asks for Orbitron renders in Roboto with no
 * trace in the render log to say which face fell back or why. That output is *wrong* — a branded
 * sticker rendered in the wrong typeface — so by default the renderer treats a fallback as a
 * **fatal** per-preview error (the render loop drops the PNG and writes the usual `.error.json`).
 *
 * Opt out with `-Dcomposeai.fonts.failOnFallback=false` to downgrade a fallback to a non-fatal
 * warning: the PNG is kept and the fell-back faces are recorded in a `<png>.warnings.json` sidecar
 * instead. Use that for a deliberately-offline render, or a catalog that genuinely tolerates the
 * substitute face.
 *
 * Collection is per-preview: the render loop calls [beginPreview] before a render and [drainPreview]
 * after, so each preview only owns the fonts *it* asked for. A one-line stderr note is emitted once
 * per distinct `(family, weight, italic)` per process (a catalog render asks for the same face
 * hundreds of times) — matching the daemon's other self-diagnostics, surfaced in the VS Code
 * extension as `[daemon stderr] …`.
 */
// Public (not `internal`) so BOTH Android render paths can drive it: the gradle-plugin's
// `RobolectricRenderTest` (same module) and the CLI `bundle pack` / serve daemon's
// `:daemon:android` `RenderEngine`, which lives in a different module and would otherwise be unable
// to bracket a preview's font resolution — the gap that let a daemon-path render silently ship a
// Roboto-fallback sticker.
object FontResolutionDiagnostics {

  /** One downloadable face that couldn't be resolved and fell back to the platform default. */
  data class FontFallback(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val reason: String,
  )

  private val warnedThisProcess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val currentPreview = java.util.Collections.synchronizedList(mutableListOf<FontFallback>())

  /**
   * Whether an unresolved downloadable font fails its preview (default) or degrades to a warning.
   * Read per call so a test / daemon can flip `composeai.fonts.failOnFallback` between renders.
   */
  val failOnFallback: Boolean
    get() =
      System.getProperty("composeai.fonts.failOnFallback")?.toBooleanStrictOrNull() ?: true

  /** Reset the per-preview buffer. Called by the render loop before each preview's render. */
  fun beginPreview() {
    synchronized(currentPreview) { currentPreview.clear() }
  }

  /** Snapshot and clear the fonts that fell back during the just-finished preview render. */
  fun drainPreview(): List<FontFallback> =
    synchronized(currentPreview) {
      val snapshot = currentPreview.toList()
      currentPreview.clear()
      snapshot
    }

  /**
   * Record that [key] couldn't be resolved (so text will render in the platform fallback) for
   * [reason]. Adds it to the current preview's buffer and emits a de-duplicated stderr line.
   */
  internal fun recordFallback(key: GoogleFontKey, reason: String) {
    val fallback = FontFallback(key.name, key.weight.weight, key.italic, reason)
    currentPreview.add(fallback)
    if (warnedThisProcess.add(key.fileName())) System.err.println(describe(fallback))
  }

  /**
   * Best-effort explanation for *why* a resolution just failed, from the process's font config. The
   * shadow doesn't get a reason back from the null [GoogleFontCacheAccess.load] result, so we infer
   * it from the same knobs the cache reads: an unset cache dir, offline mode, else a live fetch that
   * failed (network, or Google serves no TTF for the family/weight).
   */
  fun currentFailureReason(): String {
    val cacheDir = System.getProperty("composeai.fonts.cacheDir")
    val offline = System.getProperty("composeai.fonts.offline")?.equals("true", ignoreCase = true)
    return when {
      cacheDir.isNullOrBlank() -> "no font cache configured (composeai.fonts.cacheDir unset)"
      offline == true ->
        "offline (composeai.fonts.offline=true) and the face was not already cached"
      else ->
        "download from Google Fonts failed (network error, or Google serves no TTF for this " +
          "family/weight)"
    }
  }

  /** The human-readable line for [fallback], used for stderr and the sidecar/exception message. */
  fun describe(fallback: FontFallback): String =
    "ComposeAiFonts: could not resolve downloadable font \"${fallback.family}\" " +
      "(weight=${fallback.weight}${if (fallback.italic) ", italic" else ""}) — ${fallback.reason}; " +
      "text renders in the platform fallback (Roboto)"

  /** Reset process-wide dedupe + the per-preview buffer. Tests only. */
  internal fun resetForTest() {
    warnedThisProcess.clear()
    synchronized(currentPreview) { currentPreview.clear() }
  }
}

/**
 * Thrown by the render loop when a preview asked for one or more downloadable fonts that couldn't be
 * resolved and `composeai.fonts.failOnFallback` is on (the default). Routes through the renderer's
 * existing per-preview `catch (Throwable)` so the failure lands in the `.error.json` sidecar and the
 * (wrong-typeface) PNG is dropped — the same surface a preview that threw uses.
 */
class FontFallbackException(
  fallbacks: List<FontResolutionDiagnostics.FontFallback>
) :
  RuntimeException(
    buildString {
      append("Downloadable font(s) fell back to the platform default (Roboto), so this preview ")
      append("would render in the wrong typeface. Warm the font cache (composeai.fonts.cacheDir) ")
      append("or allow egress to fonts.googleapis.com + fonts.gstatic.com; set ")
      append("-Dcomposeai.fonts.failOnFallback=false to allow the fallback as a warning instead. ")
      append("Unresolved: ")
      append(
        fallbacks.joinToString("; ") {
          "${it.family} (weight=${it.weight}${if (it.italic) ", italic" else ""}) — ${it.reason}"
        }
      )
    }
  )

/**
 * Represents a single resolved Google font file keyed by family + axes. Serialised on disk as
 * `<slug>-<weight>[-italic].ttf` so the cache is human-readable under `~/.cache/composeai/fonts/`.
 */
internal data class GoogleFontKey(val name: String, val weight: FontWeight, val italic: Boolean) {
  fun fileName(): String {
    val slug = slugify(name)
    val italicPart = if (italic) "-italic" else ""
    return "$slug-${weight.weight}$italicPart.ttf"
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
        .ifEmpty { "font" }
  }
}

/**
 * Abstraction over "hand me a cached TTF for `(name, weight, italic)`" so tests can stub the
 * download path with a preseeded directory.
 */
internal interface GoogleFontSource {
  fun load(key: GoogleFontKey): File?
}

/**
 * Disk-backed [GoogleFontSource]. Downloads missing TTFs from the Google Fonts CSS API on first
 * access, then reuses the on-disk copy forever.
 *
 * Two knobs, both off the same system-property surface the rest of the renderer uses:
 * - `composeai.fonts.cacheDir` — directory root.
 * - `composeai.fonts.offline` — when `true`, skip network on cache miss so the render shows the
 *   fallback font instead of silently fetching from a non-deterministic endpoint.
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
 * Two-stage lookup:
 * 1. Try `wght@<exact>` — works for static families (Roboto, Lobster Two) and for the default
 *    weight of variable families.
 * 2. If that request *succeeded but carried no TTF URL* (purely-variable fonts like Roboto Flex
 *    reject single-weight requests at non-default weights), retry with `wght@<min>..<max>` covering
 *    the full 1–1000 range. For variable fonts the response is a single `@font-face` pointing at the
 *    variable TTF; for static fonts it's multiple blocks and we pick the closest to the requested
 *    weight.
 *
 * The stage-1/stage-2 distinction is load-bearing for reproducibility, which is why a *failed*
 * stage-1 request returns false rather than falling through. The two stages can legitimately resolve
 * the same `(family, weight, italic)` to different faces — a static sub-font at the exact weight vs
 * the family's variable TTF — and those have different text metrics. Falling through on a network
 * error therefore let a transient blip resolve a key to the other face, and because the result is
 * cached under the same filename, that face then stuck for every later render on the machine.
 * Consumers saw it as a whole-text-layer sub-pixel shift appearing and disappearing between CI runs
 * on unrelated commits. Failing instead routes through the usual unresolved-font path, which is
 * fatal per-preview by default (see [FontResolutionDiagnostics.failOnFallback]) and says so.
 *
 * The CSS2 endpoint serves WOFF2 by default (Android doesn't parse WOFF2 natively), so we send an
 * Android-2.3 User-Agent — one of the few UAs for which the API still returns TrueType. Same
 * mechanism `google-webfonts-helper` and similar offline caches rely on.
 */
internal fun downloadFromGoogleFonts(
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
        pickClosestTruetypeUrl(rangeCss, key.weight.weight) ?: return false
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

internal fun buildCssUrl(key: GoogleFontKey): String =
  buildCssUrlForAxis(
    key,
    if (key.italic) "ital,wght@1,${key.weight.weight}" else "wght@${key.weight.weight}",
  )

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
internal fun buildRangeCssUrl(key: GoogleFontKey): String =
  buildCssUrlForAxis(key, if (key.italic) "ital,wght@1,100..1000" else "wght@100..1000")

private fun buildCssUrlForAxis(key: GoogleFontKey, axis: String): String {
  // `URLEncoder.encode(s, Charset)` is API 33+. The renderer runs inside
  // Robolectric on JDK 17 where both overloads exist, but the library's
  // `minSdk = 24` trips `lint`. The legacy `encode(s, charsetName)`
  // overload is unchanged and the round-trip is identical.
  @Suppress("DEPRECATION") val family = URLEncoder.encode(key.name, "UTF-8").replace("+", "%20")
  return "https://fonts.googleapis.com/css2?family=$family:$axis&display=swap"
}

internal fun extractFirstTruetypeUrl(css: String): String? {
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
internal fun pickClosestTruetypeUrl(css: String, requestedWeight: Int): String? {
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

/**
 * Parses the `FontRequest.query` wire format that Compose's `GoogleFont.kt` builds into a
 * [GoogleFontKey].
 *
 * Expected shape (from `androidx.compose.ui.text.googlefonts.GoogleFont`):
 * ```
 * name=<urlencoded>&weight=<int>&width=<float>&italic=<0.0|1.0>&besteffort=<bool>
 * ```
 *
 * Any missing field falls back to sensible defaults so a slightly different query shape (older or
 * newer Compose, non-Compose callers) still resolves.
 */
internal fun parseFontRequestQuery(query: String?): GoogleFontKey? {
  query ?: return null
  val pairs =
    query
      .split('&')
      .mapNotNull { pair ->
        val idx = pair.indexOf('=').takeIf { i -> i > 0 } ?: return@mapNotNull null
        val key = pair.substring(0, idx)
        val raw = pair.substring(idx + 1)
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        key to value
      }
      .toMap()
  val name = pairs["name"]?.takeIf { it.isNotBlank() } ?: return null
  val weight = pairs["weight"]?.toIntOrNull() ?: 400
  val italic = pairs["italic"]?.toFloatOrNull()?.let { it >= 0.5f } ?: false
  return GoogleFontKey(name, FontWeight(weight), italic)
}
