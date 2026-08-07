package ee.schimke.composeai.data.layoutinspector

/**
 * The identity of a **Material icon** an `Icon`/`Image` painted, recovered from the `ImageVector`'s
 * own name and resolved to its canonical entry on
 * [fonts.google.com/icons](https://fonts.google.com/icons).
 *
 * Every `androidx.compose.material.icons.Icons.*` vector carries a name of the form
 * `"<Style>.<PascalCaseIcon>"` — `Filled.Menu`, `Outlined.AccountCircle`,
 * `AutoMirrored.Filled.ArrowBack` — baked in by the icon generator (`materialIcon(name = …)`) and
 * readable off the live `VectorComponent` at capture time. That name is the *only* thing separating
 * a stock Material icon from an app's own artwork: the geometry alone can't tell them apart.
 *
 * ### Which icon set this points at
 *
 * The **legacy Material Icons** set (`materialicons`, `materialiconsoutlined`, …), not the newer
 * Material *Symbols*. That is not a preference — it is what `Icons.*` actually draws.
 * `Icons.Filled.Menu` draws `M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z` on a 24×24 viewport —
 * the same coordinates `materialicons/menu/…/24px.svg` serves (the export re-serialises them, so
 * the `d` string is equivalent rather than textually equal). The Symbols redraw of the same icon is
 * different geometry on a `0 -960 960 960` viewport. Referencing Symbols would name artwork the
 * render never produced, so a design tool swapping in "the real component" would silently change
 * the picture.
 *
 * ### What the export does with it
 *
 * Annotates, never substitutes. The emitted `<defs>` entry carries the **captured** paths — the
 * geometry Compose actually drew, at the tint it actually drew it — and this reference rides
 * alongside as `data-material-icon` / `data-material-icon-url`. So the SVG's pixels never depend on
 * a network fetch or on this mapping being right: a mis-mapped name is a wrong *label*, not a wrong
 * *drawing*. See `FigmaLayeredSvg` for the emitted shape.
 */
data class MaterialIconRef(
  /** Canonical icon name as fonts.google.com knows it — `account_circle`, `arrow_back`. */
  val name: String,
  /** The style variant the app used. */
  val style: Style,
  /**
   * True for an `Icons.AutoMirrored.*` vector — one Compose flips horizontally under RTL. The
   * canonical asset is the LTR drawing (what the render shows unless the preview is RTL); the flag
   * is carried so a design tool knows the pair exists.
   */
  val autoMirrored: Boolean = false,
) {

  /**
   * The canonical 24dp SVG on Google's icon CDN, e.g.
   * `https://fonts.gstatic.com/s/i/materialiconsoutlined/account_circle/v1/24px.svg`.
   *
   * The `v1` segment is a cache-busting version the CDN accepts for any published revision of an
   * icon (it serves the current drawing regardless), so the reference stays stable without pinning
   * this repo to a per-icon version table that would rot.
   */
  val url: String
    get() = "https://fonts.gstatic.com/s/i/${style.cdnFamily}/$name/v1/24px.svg"

  /** The five drawing styles the Material Icons set (and `Icons.*`) ships. */
  enum class Style(
    /** The `Icons.` sub-object name, as it appears in the `ImageVector` name prefix. */
    val composeName: String,
    /** The CDN path segment for this style's drawing. */
    val cdnFamily: String,
  ) {
    FILLED("Filled", "materialicons"),
    OUTLINED("Outlined", "materialiconsoutlined"),
    ROUNDED("Rounded", "materialiconsround"),
    SHARP("Sharp", "materialiconssharp"),
    TWO_TONE("TwoTone", "materialiconstwotone"),
  }

  companion object {

    /** The `Icons.AutoMirrored` prefix that precedes the style on a mirrored icon's name. */
    private const val AUTO_MIRRORED = "AutoMirrored"

    private val STYLES = Style.entries.associateBy { it.composeName }

    /**
     * Icons whose Compose identifier doesn't fall out of [snakeCase] — the residue of running the
     * rule across the whole `material-icons-extended` set (2083 icons; these nine are the only
     * misses, and `MaterialIconRefTest` pins that claim against the vendored name lists).
     *
     * They are all places the icon generator's identifier-legalisation lost a digit boundary the
     * icon name keeps (`Crop169` for `crop_16_9`) or invented one it doesn't (`Grid3x3`).
     */
    private val EXCEPTIONS =
      mapOf(
        "Co2" to "co2",
        "Crop169" to "crop_16_9",
        "Crop32" to "crop_3_2",
        "Crop54" to "crop_5_4",
        "Crop75" to "crop_7_5",
        "Grid3x3" to "grid_3x3",
        "Grid4x4" to "grid_4x4",
        "StarBorderPurple500" to "star_border_purple500",
        "StarPurple500" to "star_purple500",
      )

    /**
     * Parses an `ImageVector.name` into a [MaterialIconRef], or null when it isn't a stock Material
     * icon.
     *
     * Null — rather than a guess — for anything that doesn't match the generator's exact shape: an
     * app's own `ImageVector` (whose name is whatever the author passed, or Compose's
     * `"VectorRootGroup"`/empty default), a vector built inline, an unknown style segment. The
     * annotation is only worth emitting when it is certainly right, and an unnamed vector still
     * exports its captured paths exactly as before.
     */
    fun parse(vectorName: String?): MaterialIconRef? {
      val raw = vectorName?.trim().orEmpty()
      if (raw.isEmpty()) return null
      val parts = raw.split('.')
      val autoMirrored = parts.size == 3 && parts[0] == AUTO_MIRRORED
      // `Filled.Menu`, or `AutoMirrored.Filled.ArrowBack` — nothing else is generator-shaped.
      if (parts.size != 2 && !autoMirrored) return null
      val style = STYLES[parts[parts.size - 2]] ?: return null
      val identifier = parts.last()
      if (identifier.isEmpty()) return null
      val name = EXCEPTIONS[identifier] ?: snakeCase(identifier)
      if (name.isEmpty()) return null
      return MaterialIconRef(name = name, style = style, autoMirrored = autoMirrored)
    }

    /**
     * The icon generator's identifier convention, run backwards: `AccountCircle` →
     * `account_circle`, `Rotate90DegreesCcw` → `rotate_90_degrees_ccw`, `_3dRotation` →
     * `3d_rotation`.
     *
     * A leading `_` marks a name the icon set starts with a digit (`10k`, `3d_rotation`,
     * `30fps_select`) — Kotlin can't, so the generator prefixes it. Those keep their digits welded
     * to the following letters (`10k`, not `10_k`), which is why the digit↔letter boundary split
     * applies only to the un-prefixed names.
     */
    internal fun snakeCase(identifier: String): String {
      val numericLeading = identifier.startsWith("_")
      val body = identifier.trimStart('_')
      val out = StringBuilder(body.length + 8)
      for ((i, ch) in body.withIndex()) {
        val prev = body.getOrNull(i - 1)
        val next = body.getOrNull(i + 1)
        val boundary =
          when {
            prev == null -> false
            // `accountCircle` → `account_Circle`, and `ARIcon`-style runs break before the last
            // capital that starts a word (`SDCard` → `SD_Card`).
            ch.isUpperCase() && (prev.isLowerCase() || prev.isDigit()) -> true
            ch.isUpperCase() && prev.isUpperCase() && next?.isLowerCase() == true -> true
            // A digit run is its own word — but only in a name that didn't start with one.
            !numericLeading && ch.isDigit() != prev.isDigit() -> true
            else -> false
          }
        if (boundary) out.append('_')
        out.append(ch.lowercaseChar())
      }
      return out.toString()
    }
  }
}
