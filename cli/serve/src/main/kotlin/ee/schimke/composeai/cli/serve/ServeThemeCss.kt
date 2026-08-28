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
 * wins at equal specificity. It covers **both** families that sheet declares: the
 * `--md-sys-color-*` Material 3 roles and the `--cp-*` chrome aliases written in terms of them.
 * Emitting only the aliases would leave every component styled against a role (an M3 tonal chip, a
 * state layer, an error container) stuck on the baseline scheme while the rest of the page
 * re-themed — so the two are produced together, from the same resolved values, in [m3Roles].
 * Semantic colours (the trust badges, good/warn/bad scores, the parity lanes) stay literal in the
 * sheet, because they mean the same thing in every system.
 *
 * ## Two modes from one palette
 *
 * A catalog bakes **one** mode — `wear-m3` is dark, `jetnews` is light — but the page may be read
 * in either. Rather than forcing the catalog's mode onto the browser, the emitted CSS declares
 * both:
 * - the **matching** mode gets the full sync: surfaces, text and borders derived from the catalog's
 *   `surface` / `onSurface` (plus `surfaceContainer*` when it publishes them), and its accent
 *   family;
 * - the **opposite** mode keeps the built-in neutrals for that mode and takes only the accent
 *   family, re-contrasted against that mode's background.
 *
 * So a dark-mode reader of a light-first catalog gets a dark page in the catalog's brand colour,
 * not a light page — and never an unreadable one: every colour that ends up as text is pushed to a
 * minimum contrast ratio against what it sits on ([ensureContrast]).
 *
 * The two are emitted as **one `:root` block of `light-dark(<light>, <dark>)` pairs**, not as a
 * `:root` block plus a `prefers-color-scheme` media block. That is what lets the chrome be pinned
 * to a mode: `serve.css` resolves every mode-dependent value the same way, and the page-theme
 * setting only has to set `color-scheme` (via `.cp-scheme-light` / `.cp-scheme-dark` on `<html>`)
 * for the catalog's palette to follow the selected preview theme instead of the OS. With neither
 * class set, `color-scheme: light dark` defers to `prefers-color-scheme` exactly as the media query
 * did.
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

  /**
   * …and for body text, which the whole neutral ramp is derived from — so it is held to WCAG AA for
   * normal text rather than the 3:1-ish floor a decorative accent can live at.
   */
  private const val MIN_BODY_CONTRAST = 4.5

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * The chrome palette of `serve.css`'s built-in light mode, used verbatim for a non-matching mode.
   * These are the **M3 baseline scheme**'s surface roles — `surface`, `surfaceContainerLow`,
   * `surfaceContainerHigh`, `onSurface` — and must stay in step with the `:root` block of
   * `serve.css`, which declares the same four.
   */
  private val builtInLight =
    Neutrals(
      bg = rgb("#fef7ff"),
      surface = rgb("#f7f2fa"),
      surface2 = rgb("#ece6f0"),
      fg = rgb("#1d1b20"),
    )

  /** …and of its dark mode (the M3 baseline dark scheme's same four roles). */
  private val builtInDark =
    Neutrals(
      bg = rgb("#141218"),
      surface = rgb("#1d1b20"),
      surface2 = rgb("#2b2930"),
      fg = rgb("#e6e0e9"),
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
    // Body text anchors the entire neutral ramp, so it gets the same treatment the accent does and
    // then some: nudged toward the readable pole, and abandoned for that pole outright if even the
    // nudge can't clear the floor. A catalog whose `onSurface` is (say) white on a white surface
    // would otherwise publish an unreadable page.
    val text =
      (colors["onSurface"] ?: colors["onBackground"])
        ?.let { flatten(it, surface) }
        ?.let { ensureContrast(it, surface, MIN_BODY_CONTRAST, toward = readableOn(surface)) }
        ?.takeIf { contrast(it, surface) >= MIN_BODY_CONTRAST } ?: readableOn(surface)
    // Which mode the catalog itself was rendered in — the mode that gets the full surface sync.
    val catalogIsDark = luminance(surface) < 0.45

    val light = mode(colors, surface, text, primary, dark = false, matches = !catalogIsDark)
    val dark = mode(colors, surface, text, primary, dark = true, matches = catalogIsDark)
    // One declaration per property, carrying both halves. The two lists are produced by the same
    // function over the same roles, so they are the same properties in the same order — zipping
    // them is safe, and asserted by ServeThemeCssTest.
    return buildString {
      append(":root {\n")
      light.zip(dark).forEach { (l, d) ->
        val (name, lightValue) = l
        val (darkName, darkValue) = d
        require(name == darkName) { "mode() must emit the same properties in both modes" }
        append("  $name: light-dark($lightValue, $darkValue);\n")
      }
      append("}\n")
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
    val accentRing = mix(accent, bg, 0.35)
    val onAccent =
      (colors["onPrimary"]?.takeIf { matches }?.let { flatten(it, accent) } ?: readableOn(accent))
        .let { ensureContrast(it, accent, MIN_ON_ACCENT_CONTRAST, toward = readableOn(accent)) }

    // The two tonal containers this chrome actually paints, each with its own checked label.
    //
    // M3 assigns them to *different* jobs, which is why they are resolved separately rather than
    // sharing one "soft accent": `primaryContainer` backs the brand mark, while
    // `secondaryContainer` is the SELECTED state of every chip, segmented-button segment, drawer
    // toggle, navigation row and history stop. A catalog that authors a distinct secondary family
    // (wear-m3's rose `#652936`, say) means it for exactly those, so passing its primary container
    // through to them would ignore half a published scheme.
    val primaryContainer =
      container(colors, "primaryContainer", mix(accent, bg, 0.14), bg, dark, matches)
    val onPrimaryContainer =
      onContainer(colors, "onPrimaryContainer", primaryContainer, accentStrong, matches)
    // A catalog with no secondary family — which is most of them, and every one published before
    // this projection existed — falls back to the PRIMARY container rather than to the bare derived
    // tint, so those pages keep exactly the chip fill they have today. Only a catalog that actually
    // authors a *usable* secondary container moves; one whose secondary lands on the wrong side of
    // the page for this mode is rejected by [container] and lands on the same fallback.
    val secondaryContainer =
      container(colors, "secondaryContainer", primaryContainer, bg, dark, matches)
    val onSecondaryContainer =
      onContainer(colors, "onSecondaryContainer", secondaryContainer, onPrimaryContainer, matches)
    // `--cp-accent-soft` IS the selected-chip fill, so it follows the secondary container — the two
    // families must agree about the same pixel (asserted in ServeThemeCssTest).
    val accentSoft = secondaryContainer
    val onAccentSoft = onSecondaryContainer

    val muted = mix(fg, bg, FG_MUTED)
    val faint = mix(fg, bg, FG_FAINT)
    val border = mix(fg, bg, BORDER)

    return m3Roles(
      colors,
      n,
      fg,
      bg,
      muted,
      faint,
      border,
      accent,
      onAccent,
      primaryContainer,
      onPrimaryContainer,
      secondaryContainer,
      onSecondaryContainer,
      dark,
      matches,
    ) +
      listOf(
        "--cp-bg" to hex(bg),
        "--cp-surface" to hex(n.surface),
        "--cp-surface-2" to hex(n.surface2),
        "--cp-fg" to hex(fg),
        "--cp-fg-soft" to hex(mix(fg, bg, FG_SOFT)),
        "--cp-fg-muted" to hex(muted),
        "--cp-fg-faint" to hex(faint),
        "--cp-border" to hex(border),
        "--cp-border-strong" to hex(mix(fg, bg, BORDER_STRONG)),
        "--cp-accent" to hex(accent),
        "--cp-accent-strong" to hex(accentStrong),
        "--cp-accent-soft" to hex(accentSoft),
        "--cp-accent-ring" to hex(accentRing),
        "--cp-on-accent" to hex(onAccent),
        "--cp-on-accent-soft" to hex(onAccentSoft),
      )
  }

  /**
   * The **M3 role** half of the projection — the `--md-sys-color-*` custom properties `serve.css`'s
   * token layer declares, so a component styled against a role follows a served catalog exactly as
   * one styled against a `--cp-*` alias does.
   *
   * These are deliberately **derived from the values computed above** rather than read straight out
   * of the token file a second time. A catalog's raw `onSurface` may be unreadable on its own
   * surface, its `primary` may not clear contrast against the page, and its light-scheme
   * `primaryContainer` has no business on a dark page — all of which [mode] has already resolved.
   * Deriving here means the two families can never disagree about the same colour, which is the
   * whole reason the aliases exist.
   *
   * The exceptions are the roles the chrome has no alias for — the secondary accent, the tertiary
   * and error families, and the surface-container ladder. Those are taken from the catalog when it
   * publishes them **and** the mode being painted is the one it baked; otherwise they are derived,
   * so a light-first catalog never paints a light error container onto a dark page. The two tonal
   * container pairs are resolved in [mode] instead, because `--cp-accent-soft` is one of them.
   */
  private fun m3Roles(
    colors: Map<String, String>,
    n: Neutrals,
    fg: Rgb,
    bg: Rgb,
    muted: Rgb,
    faint: Rgb,
    border: Rgb,
    accent: Rgb,
    onAccent: Rgb,
    primaryContainer: Rgb,
    onPrimaryContainer: Rgb,
    secondaryContainer: Rgb,
    onSecondaryContainer: Rgb,
    dark: Boolean,
    matches: Boolean,
  ): List<Pair<String, String>> {
    // The pole a surface moves toward to become *less* elevated: white in a light scheme, black in
    // a dark one. M3's `surfaceContainerLowest` sits on that side of `surfaceContainerLow`.
    val awayPole = if (dark) Rgb(0, 0, 0) else Rgb(255, 255, 255)

    /** A catalog role, composited over [bg], but only when this is the mode it was baked for. */
    fun published(role: String): Rgb? = colors[role]?.takeIf { matches }?.let { flatten(it, bg) }

    val secondary = ensureContrast(published("secondary") ?: muted, bg, MIN_ACCENT_CONTRAST, fg)
    val tertiaryBase = published("tertiary") ?: accent
    val tertiary = ensureContrast(tertiaryBase, bg, MIN_ACCENT_CONTRAST, fg)
    val tertiaryContainer =
      container(colors, "tertiaryContainer", mix(tertiary, bg, 0.14), bg, dark, matches)
    // The error family is the one place a *literal* fallback is right: a catalog that publishes no
    // error role still needs a red that reads as an error, not a tint of its own brand colour.
    val error =
      ensureContrast(
        published("error") ?: rgb(if (dark) "#f2b8b5" else "#b3261e"),
        bg,
        MIN_ACCENT_CONTRAST,
        fg,
      )
    val errorContainer =
      container(
        colors,
        "errorContainer",
        rgb(if (dark) "#8c1d18" else "#f9dedc"),
        bg,
        dark,
        matches,
      )

    return listOf(
      "--md-sys-color-primary" to hex(accent),
      "--md-sys-color-on-primary" to hex(onAccent),
      "--md-sys-color-primary-container" to hex(primaryContainer),
      "--md-sys-color-on-primary-container" to hex(onPrimaryContainer),
      "--md-sys-color-secondary" to hex(secondary),
      "--md-sys-color-on-secondary" to hex(readableOn(secondary)),
      "--md-sys-color-secondary-container" to hex(secondaryContainer),
      "--md-sys-color-on-secondary-container" to hex(onSecondaryContainer),
      "--md-sys-color-tertiary" to hex(tertiary),
      "--md-sys-color-on-tertiary" to hex(readableOn(tertiary)),
      "--md-sys-color-tertiary-container" to hex(tertiaryContainer),
      "--md-sys-color-on-tertiary-container" to
        hex(
          onContainer(
            colors,
            "onTertiaryContainer",
            tertiaryContainer,
            readableOn(tertiaryContainer),
            matches,
          )
        ),
      "--md-sys-color-error" to hex(error),
      "--md-sys-color-on-error" to hex(readableOn(error)),
      "--md-sys-color-error-container" to hex(errorContainer),
      "--md-sys-color-on-error-container" to
        hex(
          onContainer(
            colors,
            "onErrorContainer",
            errorContainer,
            readableOn(errorContainer),
            matches,
          )
        ),
      "--md-sys-color-surface" to hex(bg),
      "--md-sys-color-on-surface" to hex(fg),
      "--md-sys-color-surface-variant" to hex(n.surface2),
      "--md-sys-color-on-surface-variant" to hex(muted),
      "--md-sys-color-surface-dim" to hex(if (dark) bg else mix(fg, bg, 0.09)),
      "--md-sys-color-surface-bright" to hex(if (dark) mix(fg, bg, 0.16) else bg),
      "--md-sys-color-surface-container-lowest" to hex(mix(awayPole, n.surface, 0.6)),
      "--md-sys-color-surface-container-low" to hex(n.surface),
      "--md-sys-color-surface-container" to hex(mix(n.surface2, n.surface, 0.5)),
      "--md-sys-color-surface-container-high" to hex(n.surface2),
      "--md-sys-color-surface-container-highest" to hex(mix(fg, n.surface2, 0.05)),
      "--md-sys-color-outline" to hex(faint),
      "--md-sys-color-outline-variant" to hex(border),
      "--md-sys-color-inverse-surface" to hex(fg),
      "--md-sys-color-inverse-on-surface" to hex(bg),
      "--md-sys-color-inverse-primary" to hex(mix(accent, fg, 0.35)),
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
   * A published tonal container fill (`primaryContainer`, `secondaryContainer`, …). The catalog's
   * own value is the faithful choice when the mode being painted is the one it baked, but only if
   * it lands on the right side of the page — a light-scheme container is a glaring patch on a dark
   * page — so it is checked against the mode and derived from the accent otherwise.
   */
  private fun container(
    colors: Map<String, String>,
    role: String,
    derived: Rgb,
    bg: Rgb,
    dark: Boolean,
    matches: Boolean,
  ): Rgb {
    if (!matches) return derived
    val container = colors[role]?.let { flatten(it, bg) } ?: return derived
    val onRightSide = if (dark) luminance(container) < 0.5 else luminance(container) > 0.5
    return if (onRightSide) container else derived
  }

  /**
   * The label that sits **on** [fill] — the catalog's published `on…Container` when this is its
   * mode, else [fallback] — always pushed to a readable contrast against that exact fill.
   *
   * Checking against the fill rather than against the page is the whole point: a container and its
   * label are resolved as a pair (the fill may have been rejected and derived above), so a label
   * validated against anything else can still come out invisible on the fill it actually lands on.
   */
  private fun onContainer(
    colors: Map<String, String>,
    role: String,
    fill: Rgb,
    fallback: Rgb,
    matches: Boolean,
  ): Rgb =
    (colors[role]?.takeIf { matches }?.let { flatten(it, fill) } ?: fallback).let {
      ensureContrast(it, fill, MIN_ON_ACCENT_CONTRAST, toward = readableOn(fill))
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
