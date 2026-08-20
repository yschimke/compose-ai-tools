package ee.schimke.composeai.renderer

import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import java.net.URLDecoder

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
    cache?.load(GoogleFontKey(name, weight, italic))
}

/**
 * Read-only view of the downloadable-font cache for consumers that need the *file the render
 * actually drew with* rather than a fresh resolution.
 *
 * The `compose/figma-svg` export used to embed a downloadable face by re-fetching a WOFF2 from
 * Google by family name — a second network round-trip, independent of the TTF the render had
 * already resolved. It failed exactly where it mattered: a catalog render whose font cache was warm
 * (so the PNG is correct) but whose egress is closed, or which runs `composeai.fonts.offline`,
 * produced an SVG with no `@font-face` at all, so browsers fell back to sans-serif and every glyph
 * width, line break and ellipsis drifted from the PNG (issue #2906).
 *
 * Looking the already-resolved file up instead makes the embedded face the same bytes the raster
 * used, by construction, and removes the network from the export path entirely.
 */
object GoogleFontFiles {
  /**
   * The cached TTF for `(family, weight, italic)`, or null when nothing has resolved it. Never
   * downloads — a miss means the render didn't draw with this face either, and the export should
   * degrade rather than fetch a face the raster never saw.
   */
  fun cached(family: String, weight: Int, italic: Boolean): File? {
    val dir =
      System.getProperty("composeai.fonts.cacheDir")?.takeIf { it.isNotBlank() } ?: return null
    val file = File(dir, GoogleFontKey(family, weight, italic).fileName())
    return file.takeIf { it.isFile && it.length() > 0 }
  }
}

/**
 * Render-time surfacing for downloadable-font resolution failures.
 *
 * A `Font(GoogleFont(...))` that can't be resolved — offline, no cache dir, a failed download, or a
 * family/weight Google serves no TTF for — is reported to Compose by [ShadowFontsContractCompat],
 * which then *silently* substitutes the platform default (Roboto). That's the "my branded fonts
 * aren't applied in screenshots" symptom: a preview that asks for Orbitron renders in Roboto with
 * no trace in the render log to say which face fell back or why. That output is *wrong* — a branded
 * sticker rendered in the wrong typeface — so by default the renderer treats a fallback as a
 * **fatal** per-preview error (the render loop drops the PNG and writes the usual `.error.json`).
 *
 * Opt out with `-Dcomposeai.fonts.failOnFallback=false` to downgrade a fallback to a non-fatal
 * warning: the PNG is kept and the fell-back faces are recorded in a `<png>.warnings.json` sidecar
 * instead. Use that for a deliberately-offline render, or a catalog that genuinely tolerates the
 * substitute face.
 *
 * Collection is per-preview: the render loop calls [beginPreview] before a render and
 * [drainPreview] after, so each preview only owns the fonts *it* asked for. A one-line stderr note
 * is emitted once per distinct `(family, weight, italic)` per process (a catalog render asks for
 * the same face hundreds of times) — matching the daemon's other self-diagnostics, surfaced in the
 * VS Code extension as `[daemon stderr] …`.
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
    get() = System.getProperty("composeai.fonts.failOnFallback")?.toBooleanStrictOrNull() ?: true

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
    val fallback = FontFallback(key.name, key.weight, key.italic, reason)
    currentPreview.add(fallback)
    if (warnedThisProcess.add(key.fileName())) System.err.println(describe(fallback))
  }

  /**
   * Record that a preview used `Font(DeviceFontFamilyName([slug]))` for a family this process could
   * not seed, so the text drew in the platform fallback rather than [displayName].
   *
   * The **use** is the trigger, not the seeding miss: seeding attempts every known family on every
   * render process, so a cold cache misses all of them at once, and a preview that never mentions a
   * family must not be failed for it. See the call site in `PixelSystemFontAliases`.
   *
   * Reported through the same [FontFallback] channel as an unresolved downloadable font because it
   * is the same defect from a consumer's side — text in a face nobody asked for — and so that
   * `composeai.fonts.failOnFallback` governs both without a second switch. [FontFallback.weight] is
   * 400 because that is the weight seeding asks for; off-400 weights are synthesised from it, so a
   * miss there is a miss at every weight.
   */
  fun recordDeviceFamilyFallback(slug: String, displayName: String?) {
    val named = displayName ?: slug
    recordFallback(
      GoogleFontKey(named, 400, false),
      "system font family \"$slug\" was not seeded — ${currentFailureReason()}",
    )
  }

  /**
   * Best-effort explanation for *why* a resolution just failed, from the process's font config. The
   * shadow doesn't get a reason back from the null [GoogleFontCacheAccess.load] result, so we infer
   * it from the same knobs the cache reads: an unset cache dir, offline mode, else a live fetch
   * that failed (network, or Google serves no TTF for the family/weight).
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
 * Thrown by the render loop when a preview asked for one or more downloadable fonts that couldn't
 * be resolved and `composeai.fonts.failOnFallback` is on (the default). Routes through the
 * renderer's existing per-preview `catch (Throwable)` so the failure lands in the `.error.json`
 * sidecar and the (wrong-typeface) PNG is dropped — the same surface a preview that threw uses.
 */
class FontFallbackException(fallbacks: List<FontResolutionDiagnostics.FontFallback>) :
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
  return GoogleFontKey(name, weight, italic)
}
