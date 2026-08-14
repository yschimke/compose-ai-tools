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

  /**
   * The **variable** file for [family] — the one carrying an `fvar` table, so a caller can instance
   * it at arbitrary axis values — or null when the family has none (a static family) or it could
   * not be fetched.
   *
   * Separate from [load] because it is a different file from a different place, and a caller that
   * doesn't need axes shouldn't pay for it. [load] resolves through the CSS API, which serves a
   * *static instance* even for a purely variable family: the range query (`wght@100..1000`) answers
   * with a single `@font-face` whose TTF has no `fvar` at all — 88 KB of one frozen instance where
   * the family's real variable file is 1.7 MB. Nothing downstream can apply `wdth` to that, which
   * is why a `wdth` ramp rendered flat in every lane resolving fonts here while the browser lane
   * (fed a genuine variable file from a host manifest) drew it correctly.
   *
   * Default implementation returns null so a source that only serves static faces — and every
   * existing test fake — keeps compiling and behaving as before.
   */
  fun loadVariable(family: String, italic: Boolean = false): File? = null
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

  /**
   * Not a fourth primary-constructor parameter, deliberately: this class ships in a published
   * artifact, and adding one would change the primary constructor's JVM descriptor (and its
   * defaults-synthetic), so a consumer's *already-compiled* `GoogleFontCache(dir, false,
   * downloader)` would fail with `NoSuchMethodError` on a dependency-only upgrade. A `var` set
   * through the secondary constructor below leaves every existing descriptor exactly where it was.
   */
  private var variableDownloader: (String, Boolean, File) -> Boolean =
    ::downloadVariableFromGoogleFontsRepo

  /** Injects the variable-font downloader as well, for tests. */
  constructor(
    cacheDir: File,
    offline: Boolean,
    downloader: (GoogleFontKey, File) -> Boolean,
    variableDownloader: (String, Boolean, File) -> Boolean,
  ) : this(cacheDir, offline, downloader) {
    this.variableDownloader = variableDownloader
  }

  override fun load(key: GoogleFontKey): File? = fetch(key.fileName()) { downloader(key, it) }

  override fun loadVariable(family: String, italic: Boolean): File? =
    fetch(variableFileName(family, italic)) { variableDownloader(family, italic, it) }

  /**
   * The cached file named [fileName], downloading it through [download] on a miss.
   *
   * A download writes to a sibling `.tmp` and renames, so a crashed or half-finished fetch can
   * never leave a truncated file that later runs would happily serve as the real face.
   */
  private inline fun fetch(fileName: String, download: (File) -> Boolean): File? {
    val file = File(cacheDir, fileName)
    if (file.exists() && file.length() > 0) return file
    if (offline) return null
    cacheDir.mkdirs()
    val tmp = File(cacheDir, "$fileName.tmp")
    val ok = runCatching { download(tmp) }.getOrDefault(false)
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
 * Cache filename for a family's variable file. Deliberately *not* [GoogleFontKey.fileName]'s shape:
 * a variable file serves every weight, so keying it by one would download the same 1.7 MB file once
 * per weight the document happens to ask for.
 */
fun variableFileName(family: String, italic: Boolean): String =
  "${GoogleFontKey.slugify(family)}-variable${if (italic) "-italic" else ""}.ttf"

/**
 * Fetches a TTF for [key] into [destination]. Returns `true` on success.
 *
 * Two-stage lookup:
 * 1. Try `wght@<exact>` — works for static families (Roboto, Lobster Two) and for the default
 *    weight of variable families.
 * 2. If that request *succeeded but carried no TTF URL* (purely-variable fonts like Roboto Flex
 *    reject single-weight requests at non-default weights), retry with `wght@<min>..<max>` covering
 *    the full 1–1000 range. For variable fonts the response is a single `@font-face`; for static
 *    fonts it's multiple blocks and we pick the closest to the requested weight.
 *
 * Either way what arrives is a **static** TTF — the CSS API bakes an instance and serves that, so
 * even the range query's answer has no `fvar` table. A caller that needs to *vary* axes wants
 * [GoogleFontSource.loadVariable] instead; this function is the right one for drawing a family at a
 * fixed weight, which is what the downloadable-font lanes ask for.
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

/**
 * Fetches the **variable** TTF for [family] into [destination]. Returns `true` on success.
 *
 * Source is the `google/fonts` repository rather than the CSS API, because the CSS API has no way
 * to ask for one: every `format('truetype')` URL it serves is a static instance, including the
 * range query that reads like it should be the variable file (see [GoogleFontSource.loadVariable]).
 * The repository is where those instances are *built from*, so it is the only key-free source of
 * the file with the `fvar` table still in it.
 *
 * Three steps, all plain unauthenticated GETs of raw files:
 * 1. the family's directory is its name lowercased with everything non-alphanumeric removed
 *    (`Roboto Flex` → `robotoflex`), under one of the three licence roots — try each, first hit
 *    wins;
 * 2. `METADATA.pb` in that directory names the font files, and a variable one is recognisable by
 *    the axis list in its filename (`RobotoFlex[GRAD,…,wdth,wght].ttf`). A static-only family
 *    (Lobster Two) simply has none, which is a null rather than an error;
 * 3. the file is fetched and **verified to actually carry an `fvar` table** before it is accepted.
 *
 * That last check is the one worth keeping. Everything downstream treats "the variable file" as the
 * thing it can instance at arbitrary axes; caching a static file under that name would make every
 * later render draw a `wdth` ramp flat with no error anywhere to explain it. Verifying the table is
 * cheap (a table-directory scan) and turns a bad upstream answer into a clean miss.
 */
fun downloadVariableFromGoogleFontsRepo(
  family: String,
  italic: Boolean,
  destination: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val slug = googleFontsRepoSlug(family)
  if (slug.isEmpty()) return false
  val metadata =
    GOOGLE_FONTS_LICENCE_DIRS.firstNotNullOfOrNull { licence ->
      httpGet(googleFontsRepoMetadataUrl(licence, slug), REPO_USER_AGENT)?.let { licence to it }
    } ?: return false
  val (licence, body) = metadata
  val fileName = pickVariableFileName(body, italic) ?: return false
  val bytes =
    httpGetBytes(googleFontsRepoFileUrl(licence, slug, fileName), REPO_USER_AGENT) ?: return false
  if (!hasFvarTable(bytes)) return false
  destination.parentFile?.mkdirs()
  fileSystem.write(destination.path.toPath()) { write(bytes) }
  return true
}

/** Licence roots in `google/fonts`, in the order they are probed. */
private val GOOGLE_FONTS_LICENCE_DIRS = listOf("ofl", "apache", "ufl")

/**
 * The family's directory name in `google/fonts`: lowercase, alphanumerics only. Note this is *not*
 * [GoogleFontKey.slugify] — that one hyphenates (`roboto-flex`) for readable cache filenames, and
 * the repository uses the unseparated form (`robotoflex`).
 */
fun googleFontsRepoSlug(family: String): String = family.filter { it.isLetterOrDigit() }.lowercase()

fun googleFontsRepoMetadataUrl(licence: String, slug: String): String =
  "$GOOGLE_FONTS_REPO_BASE/$licence/$slug/METADATA.pb"

fun googleFontsRepoFileUrl(licence: String, slug: String, fileName: String): String {
  // A variable filename carries its axis list in square brackets, which are not legal in a URL
  // path.
  val encoded = fileName.replace("[", "%5B").replace("]", "%5D")
  return "$GOOGLE_FONTS_REPO_BASE/$licence/$slug/$encoded"
}

private const val GOOGLE_FONTS_REPO_BASE = "https://raw.githubusercontent.com/google/fonts/main"

// raw.githubusercontent.com serves the file the same to anyone; the UA only keeps the request
// identifiable in a proxy log rather than arriving as a bare default.
private const val REPO_USER_AGENT = "composeai-fonts"

/**
 * The variable font filename in a `METADATA.pb` body, matching [italic], or null when the family
 * ships none.
 *
 * A variable file is the one whose name carries an axis list — `RobotoFlex[…,wght].ttf`. Families
 * that vary in both uprights and italics ship two, distinguished by an `-Italic` in the name before
 * the bracket, so the upright request must not accept the italic file (or a roman specimen would
 * silently render slanted).
 */
fun pickVariableFileName(metadata: String, italic: Boolean): String? {
  val names =
    Regex("""filename:\s*"([^"]*\[[^"]*][^"]*)"""").findAll(metadata).map { it.groupValues[1] }
  val (italics, uprights) = names.partition { it.substringBefore('[').endsWith("-Italic") }
  return if (italic) italics.firstOrNull() ?: uprights.firstOrNull() else uprights.firstOrNull()
}

/**
 * Whether [bytes] is an sfnt font carrying an `fvar` table — i.e. a real variable font.
 *
 * Reads only the 12-byte header and the table directory: a `numTables` count followed by 16-byte
 * records whose first four bytes are the tag. Anything shorter, or with a table count the buffer
 * can't hold, is not a font we can use and reads as false rather than throwing.
 */
fun hasFvarTable(bytes: ByteArray): Boolean {
  if (bytes.size < 12) return false
  val numTables = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
  if (bytes.size < 12 + numTables * 16) return false
  for (i in 0 until numTables) {
    val at = 12 + i * 16
    if (
      bytes[at] == 'f'.code.toByte() &&
        bytes[at + 1] == 'v'.code.toByte() &&
        bytes[at + 2] == 'a'.code.toByte() &&
        bytes[at + 3] == 'r'.code.toByte()
    ) {
      return true
    }
  }
  return false
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
 * - Variable families answer with a single block at `font-weight: 400`; return that URL. Note what
 *   comes back is a *static instance* of the family, not its variable file — the axes are already
 *   baked out of it. [GoogleFontSource.loadVariable] is the way to the file that still has them.
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

private fun httpGetBytes(url: String, userAgent: String): ByteArray? = runCatching {
  val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
  fontHttpClient.newCall(request).execute().use { response ->
    if (response.isSuccessful) response.body?.bytes() else null
  }
}
  .getOrNull()
