package ee.schimke.composeai.cli.serve

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Projects a published catalog's **own design tokens onto the serve web chrome** — so browsing
 * `/wear-m3/` paints the page in Wear M3's colours, `/jetnews/` in JetNews's crimson, and so on,
 * instead of every design system being framed by the same fixed indigo-on-white shell.
 *
 * The input is the `tokens.dtcg.json` each `design-artifacts/<system>` branch already publishes
 * beside `catalog.json` (declared there as `tokensFile`): the W3C DTCG projection of the resolved
 * `MaterialTheme.colorScheme` the catalog was rendered with, lifted from the render's
 * `compose/theme` data product by the export driver. That makes this a genuine *sync* rather than a
 * second, hand-maintained palette — re-publishing a catalog with a new brand colour re-themes its
 * pages on the next catalog refresh, with nothing to edit here.
 *
 * The output is an inline `:root` override for the custom properties `serve.css` paints the chrome
 * from ([ServeWebAssets] `serve.css`), emitted into the page `<head>` *after* the stylesheet so it
 * wins at equal specificity. Only the neutral ramp and the accent family are themed; semantic
 * colours (the trust badges, good/warn/bad scores) stay literal in the sheet, because they mean the
 * same thing in every system.
 *
 * ## Two modes from one palette
 *
 * A catalog bakes **one** mode — `wear-m3` is dark, `jetnews` is light — but a visitor arrives with
 * their own `prefers-color-scheme`. Rather than forcing the catalog's mode onto the browser, the
 * emitted CSS declares both:
 * - the **matching** mode gets the full sync: surfaces, text and borders derived from the catalog's
 *   `surface` / `onSurface` (plus `surfaceContainer*` when it publishes them), and its accent
 *   family;
 * - the **opposite** mode keeps the built-in neutrals for that mode and takes only the accent
 *   family, re-contrasted against that mode's background.
 *
 * So a dark-mode visitor browsing a light-first catalog gets a dark page in the catalog's brand
 * colour, not a light page — and never an unreadable one: every colour that ends up as text is
 * pushed to a minimum contrast ratio against what it sits on ([ensureContrast]).
 *
 * The neutral ramp (muted/faint text, borders) is *derived* from the `(background, text)` pair by
 * mixing, rather than read from `outline` / `onSurfaceVariant`: those roles are published
 * inconsistently across catalogs (some carry alpha, some are absent), while the mix reproduces the
 * built-in ramp almost exactly and behaves the same for every system.
 */
internal object ServeThemeCss {

  /** Mix ratios (share of the text colour over the background) for the derived neutral ramp. */
  private const val FG_SOFT = 0.83
  private const val FG_MUTED = 0.65
  private const val FG_FAINT = 0.49
  private const val BORDER = 0.12
  private const val BORDER_STRONG = 0.22

  /** Minimum contrast a themed colour must reach against what it is read on. */
  private const val MIN_ACCENT_CONTRAST = 4.0
  private const val MIN_ON_ACCENT_CONTRAST = 4.0

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * The chrome palette of `serve.css`'s built-in light mode, used verbatim for a non-matching mode.
   */
  private val builtInLight =
    Neutrals(
      bg = rgb("#fafafb"),
      surface = rgb("#ffffff"),
      surface2 = rgb("#f0f0f3"),
      fg = rgb("#1b1b1f"),
    )

  /** …and of its dark mode. */
  private val builtInDark =
    Neutrals(
      bg = rgb("#161618"),
      surface = rgb("#1d1d20"),
      surface2 = rgb("#26262b"),
      fg = rgb("#e6e6e9"),
    )

  /**
   * Build the inline stylesheet for a catalog's `tokens.dtcg.json`, or null when the file is
   * unparseable or carries too little to theme from (it must at least name a surface and a
   * primary). Fail-soft by design: a catalog with no usable tokens simply serves the built-in
   * chrome.
   */
  fun fromDtcg(tokensJson: String): String? = stylesheet(parseColors(tokensJson))

  /**
   * The `color` group of a DTCG token file as `role -> value`, keeping only entries that actually
   * parse as a colour. Values are `#rrggbb` / `#rrggbbaa` as written by the export driver.
   */
  fun parseColors(tokensJson: String): Map<String, String> {
    val root =
      runCatching { json.parseToJsonElement(tokensJson).jsonObject }.getOrNull()
        ?: return emptyMap()
    val colors = runCatching { root["color"]?.jsonObject }.getOrNull() ?: return emptyMap()
    return colors.entries.mapNotNull { (role, node) -> value(node)?.let { role to it } }.toMap()
  }

  private fun value(node: kotlinx.serialization.json.JsonElement): String? {
    val obj = node as? JsonObject ?: return null
    val raw = runCatching { obj["\$value"]?.jsonPrimitive?.content }.getOrNull() ?: return null
    return raw.takeIf { parse(it) != null }
  }

  /**
   * The `:root` override for [colors], or null when it names no `surface`/`background` or no
   * `primary` — the two roles the whole projection is anchored on.
   */
  fun stylesheet(colors: Map<String, String>): String? {
    val surfaceToken = colors["surface"] ?: colors["background"] ?: return null
    val primaryToken = colors["primary"] ?: return null
    // Composite any alpha away against white first: a token like `#000000de` (an alpha-carrying
    // `onSurface`, which several app catalogs publish) has to become a concrete colour before it
    // can be reasoned about, and the surface it is read on is the only sensible backdrop.
    val surface = flatten(surfaceToken, Rgb(255, 255, 255)) ?: return null
    val primary = flatten(primaryToken, surface) ?: return null
    val text =
      colors["onSurface"]?.let { flatten(it, surface) }
        ?: colors["onBackground"]?.let { flatten(it, surface) }
        ?: readableOn(surface)
    // Which mode the catalog itself was rendered in — the mode that gets the full surface sync.
    val catalogIsDark = luminance(surface) < 0.45

    val light = mode(colors, surface, text, primary, dark = false, matches = !catalogIsDark)
    val dark = mode(colors, surface, text, primary, dark = true, matches = catalogIsDark)
    return buildString {
      append(":root {\n")
      light.forEach { (name, value) -> append("  $name: $value;\n") }
      append("}\n@media (prefers-color-scheme: dark) {\n  :root {\n")
      dark.forEach { (name, value) -> append("    $name: $value;\n") }
      append("  }\n}\n")
    }
  }

  /** The complete variable set for one `prefers-color-scheme` mode. */
  private fun mode(
    colors: Map<String, String>,
    surface: Rgb,
    text: Rgb,
    primary: Rgb,
    dark: Boolean,
    matches: Boolean,
  ): List<Pair<String, String>> {
    val n = if (matches) catalogNeutrals(colors, surface, text, dark) else builtIn(dark)
    val fg = n.fg
    val bg = n.bg

    // Links and accented text sit on the page background, so the accent is pushed to a readable
    // contrast there before anything else is derived from it.
    val accent = ensureContrast(primary, bg, MIN_ACCENT_CONTRAST, toward = fg)
    // "Strong" means *further from the background*: mixing toward the text colour darkens the
    // accent in light mode and lightens it in dark, which is exactly the built-in pair's relation.
    val accentStrong = mix(accent, fg, 0.45)
    val accentSoft = accentSoft(colors, accent, bg, dark, matches)
    val accentRing = mix(accent, bg, 0.35)
    val onAccent =
      (colors["onPrimary"]?.takeIf { matches }?.let { flatten(it, accent) } ?: readableOn(accent))
        .let { ensureContrast(it, accent, MIN_ON_ACCENT_CONTRAST, toward = readableOn(accent)) }
    val onAccentSoft =
      (colors["onPrimaryContainer"]?.takeIf { matches }?.let { flatten(it, accentSoft) }
          ?: accentStrong)
        .let {
          ensureContrast(it, accentSoft, MIN_ON_ACCENT_CONTRAST, toward = readableOn(accentSoft))
        }

    return listOf(
      "--cp-bg" to hex(bg),
      "--cp-surface" to hex(n.surface),
      "--cp-surface-2" to hex(n.surface2),
      "--cp-fg" to hex(fg),
      "--cp-fg-soft" to hex(mix(fg, bg, FG_SOFT)),
      "--cp-fg-muted" to hex(mix(fg, bg, FG_MUTED)),
      "--cp-fg-faint" to hex(mix(fg, bg, FG_FAINT)),
      "--cp-border" to hex(mix(fg, bg, BORDER)),
      "--cp-border-strong" to hex(mix(fg, bg, BORDER_STRONG)),
      "--cp-accent" to hex(accent),
      "--cp-accent-strong" to hex(accentStrong),
      "--cp-accent-soft" to hex(accentSoft),
      "--cp-accent-ring" to hex(accentRing),
      "--cp-on-accent" to hex(onAccent),
      "--cp-on-accent-soft" to hex(onAccentSoft),
    )
  }

  private data class Neutrals(val bg: Rgb, val surface: Rgb, val surface2: Rgb, val fg: Rgb)

  private fun builtIn(dark: Boolean) = if (dark) builtInDark else builtInLight

  /**
   * The catalog's own surfaces. M3's `surfaceContainerLow` is "one step of elevation from
   * `surface`" — *darker* in a light scheme, *lighter* in a dark one — which is exactly the
   * page-vs-card relation the chrome wants, so it becomes the page background in light mode and the
   * card fill in dark mode. Catalogs that publish no container roles get the same relation by
   * mixing the text colour into the surface.
   */
  private fun catalogNeutrals(
    colors: Map<String, String>,
    surface: Rgb,
    text: Rgb,
    dark: Boolean,
  ): Neutrals {
    val low = colors["surfaceContainerLow"]?.let { flatten(it, surface) }
    val container = colors["surfaceContainer"]?.let { flatten(it, surface) }
    return if (dark)
      Neutrals(
        bg = surface,
        surface = low ?: mix(text, surface, 0.06),
        surface2 = container ?: mix(text, surface, 0.12),
        fg = text,
      )
    else
      Neutrals(
        bg = low ?: mix(text, surface, 0.03),
        surface = surface,
        surface2 = container ?: mix(text, surface, 0.07),
        fg = text,
      )
  }

  /**
   * The soft accent chip fill. `primaryContainer` is the faithful choice when the catalog's mode is
   * the one being painted, but only if it lands on the right side of the page — a light-scheme
   * container is a glaring patch on a dark page — so it is checked against the mode and derived
   * from the accent otherwise.
   */
  private fun accentSoft(
    colors: Map<String, String>,
    accent: Rgb,
    bg: Rgb,
    dark: Boolean,
    matches: Boolean,
  ): Rgb {
    val derived = mix(accent, bg, 0.14)
    if (!matches) return derived
    val container = colors["primaryContainer"]?.let { flatten(it, bg) } ?: return derived
    val onRightSide = if (dark) luminance(container) < 0.5 else luminance(container) > 0.5
    return if (onRightSide) container else derived
  }

  // ---------------------------------------------------------------------------------------------
  // Colour maths. sRGB only — the tokens are 8-bit hex and the output is 8-bit hex, so there is
  // nothing to gain from a wider working space here.
  // ---------------------------------------------------------------------------------------------

  data class Rgb(val r: Int, val g: Int, val b: Int)

  private fun rgb(hex: String): Rgb = parse(hex)!!.first

  /** `#rgb`, `#rrggbb` or `#rrggbbaa` → colour + alpha, or null when it isn't a hex colour. */
  internal fun parse(value: String): Pair<Rgb, Double>? {
    val h = value.trim().removePrefix("#")
    if (h.any { it.digitToIntOrNull(16) == null }) return null
    fun byte(at: Int) = h.substring(at, at + 2).toInt(16)
    return when (h.length) {
      3 -> Rgb(h[0].digitToInt(16) * 17, h[1].digitToInt(16) * 17, h[2].digitToInt(16) * 17) to 1.0
      6 -> Rgb(byte(0), byte(2), byte(4)) to 1.0
      8 -> Rgb(byte(0), byte(2), byte(4)) to byte(6) / 255.0
      else -> null
    }
  }

  /** Parse [value] and composite it over [backdrop], so the result is always opaque. */
  private fun flatten(value: String, backdrop: Rgb): Rgb? {
    val (color, alpha) = parse(value) ?: return null
    return if (alpha >= 1.0) color else mix(color, backdrop, alpha)
  }

  /** [a] at [t] over [b]. */
  internal fun mix(a: Rgb, b: Rgb, t: Double): Rgb {
    fun c(x: Int, y: Int) = (x * t + y * (1 - t)).roundToInt().coerceIn(0, 255)
    return Rgb(c(a.r, b.r), c(a.g, b.g), c(a.b, b.b))
  }

  /** WCAG relative luminance. */
  internal fun luminance(c: Rgb): Double {
    fun ch(v: Int): Double {
      val s = v / 255.0
      return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b)
  }

  /** WCAG contrast ratio, 1.0…21.0. */
  internal fun contrast(a: Rgb, b: Rgb): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  /** Black or white, whichever is more readable on [backdrop]. */
  private fun readableOn(backdrop: Rgb): Rgb =
    if (luminance(backdrop) > 0.42) Rgb(17, 17, 20) else Rgb(255, 255, 255)

  /**
   * Nudge [color] toward [toward] until it reaches [target] contrast against [backdrop]. Capped at
   * a 75% mix so a brand colour that can never reach the target stays recognisably itself rather
   * than collapsing into the text colour; the cap is only ever hit by a colour whose contrast with
   * the page is hopeless in that mode.
   */
  internal fun ensureContrast(color: Rgb, backdrop: Rgb, target: Double, toward: Rgb): Rgb {
    if (contrast(color, backdrop) >= target) return color
    var t = 0.05
    var best = color
    while (t <= 0.75 + 1e-9) {
      best = mix(toward, color, t)
      if (contrast(best, backdrop) >= target) return best
      t += 0.05
    }
    return best
  }

  private fun hex(c: Rgb): String = "#%02x%02x%02x".format(c.r, c.g, c.b)

  /** Whether two colours are close enough to be indistinguishable — used by the tests. */
  internal fun near(a: Rgb, b: Rgb, tolerance: Int = 2): Boolean =
    abs(a.r - b.r) <= tolerance && abs(a.g - b.g) <= tolerance && abs(a.b - b.b) <= tolerance
}
