package ee.schimke.composeai.data.render

/**
 * The **presentation backdrop**: the ground a consumer should composite a captured preview onto.
 *
 * ### Not the same question as [PreviewBackground]
 *
 * [PreviewBackground] answers *what the renderer fills before composing* — it decides pixels, and
 * once it has decided them they are in the PNG. This answers *what should sit behind those pixels
 * when someone looks at them*, which is a different question precisely because a capture is
 * routinely, deliberately transparent: a design catalog renders its component stickers with
 * `@Preview(showBackground = false)` so a designer can drop one onto any Figma canvas. The alpha is
 * the product, so nothing may bake a ground into it — but every surface that *shows* the sticker
 * still has to put something behind it, and a dark-first system's white-on-transparent sticker on a
 * white (or checkerboard) stage is unreadable.
 *
 * Before this existed, that ground was decided independently in eight places — the two renderers,
 * the daemon's `render/deviceBackground` product, the Figma SVG export, the catalog grid, the
 * viewer, the compare wall, the reference-compare page and the fidelity scorer — from whatever
 * proxy each had locally: a corner pixel, a variant name containing "dark", a hardcoded `#ffffff`.
 * They disagreed, and the disagreements were invisible until a whole catalog scored against the
 * wrong ground. This is the one chain they all call. **When you need a backdrop, come here rather
 * than inferring one from pixels or from a name.**
 *
 * ### Why the resolution is a chain and not a colour
 *
 * Each rung is a different kind of evidence, and they are ordered by how directly they speak for
 * the preview in front of you. An explicit `@Preview(backgroundColor = …)` is the author saying it
 * outright; a captured theme surface is what the render actually composed on; a catalog's declared
 * stage is what the system says about itself in general. [Backdrop.source] records which rung
 * answered, so a consumer that finds a surprising stage can see *why* it got that one instead of
 * guessing again — and so a low-confidence answer can be presented differently from a stated one.
 *
 * Rungs 1–2 are available from `@Preview` metadata alone, so discovery can resolve them without a
 * render; rung 3 needs a render to have happened; rung 4 is catalog-level configuration. A caller
 * supplies whichever it has and the chain degrades honestly through the rest.
 */
public object PreviewBackdrop {

  /**
   * The ground behind a preview, and the evidence it came from.
   *
   * [color] is `#AARRGGBB`, matching every other colour on the data-product wire. It is null only
   * for [Source.NONE] — nothing in the chain could answer, so a consumer should keep whatever
   * default it already uses (a checkerboard, its own page surface) rather than being handed a
   * colour this module invented.
   */
  public data class Backdrop(val color: String?, val source: Source) {

    /** Whether the ground is dark enough that light-on-transparent content will read against it. */
    public val isDark: Boolean
      get() = color?.let(::isDarkArgb) ?: false
  }

  /**
   * Which rung of the chain answered. Ordered most to least direct; see the class comment.
   *
   * Kept as an enum rather than a free string so a consumer can branch on the *kind* of evidence
   * (e.g. present a stated ground differently from an inferred one) without matching on prose that
   * a later refactor may reword.
   */
  public enum class Source(public val wire: String) {
    /** An explicit `@Preview(backgroundColor = …)`. The author said so. */
    PREVIEW_BACKGROUND_COLOR("preview.backgroundColor"),

    /** `@Preview(showBackground = true)`, resolved through the preview's night bit. */
    PREVIEW_SHOW_BACKGROUND("preview.showBackground"),

    /** The Material 3 `background` the render actually composed on, from the theme capture. */
    THEME_BACKGROUND("material3.background"),

    /** The Material 3 `surface` the render composed on, when it declared no `background`. */
    THEME_SURFACE("material3.surface"),

    /**
     * The light/dark **variant this render is**, from the catalog's own metadata or the preview's
     * night `uiMode`.
     *
     * Weaker than a stated colour but stronger than the catalog's stage, and the distinction is the
     * point: "this catalog is light-first" says nothing about a *dark* variant inside it, whose
     * light-on-transparent artwork needs a dark ground exactly as much as a dark-first catalog's
     * does. Without this rung a dark row on the compare wall opened its focused comparison on a
     * light stage.
     */
    PREVIEW_VARIANT("preview.variant"),

    /** The catalog's declared stage (`catalog.json`'s `display.surface`). */
    CATALOG_SURFACE("catalog.surface"),

    /**
     * Material 3's light background, for a caller that must name *some* colour.
     *
     * Only reached when [resolve] is asked for it. A live host has to put something behind a
     * transparent preview the moment it is opened — before any render has produced a theme capture
     * — and a readable guess beats a blank. A publishing lane should not ask for it: publishing
     * this would state a ground the producer does not actually believe in, and a consumer cannot
     * tell it apart from a real answer once it is written down.
     */
    M3_LIGHT_FALLBACK("material3.lightBackgroundFallback"),

    /** Nothing in the chain could answer. [Backdrop.color] is null. */
    NONE("none");

    public companion object {
      /** [Source] for a [wire] string, or null — for reading a published backdrop back. */
      public fun fromWire(wire: String?): Source? = entries.firstOrNull { it.wire == wire }
    }
  }

  /** The catalog-level stage a design system declares for itself. */
  public enum class CatalogSurface {
    LIGHT,
    DARK;

    public companion object {
      /**
       * [CatalogSurface] for `catalog.json`'s `display.surface`, or null when it declares none (or
       * declares something this version doesn't know — an unknown word is not a reason to invent a
       * stage, so the chain simply falls through to the next rung).
       */
      public fun parse(declared: String?): CatalogSurface? =
        when (declared?.trim()?.lowercase()) {
          "light" -> LIGHT
          "dark" -> DARK
          else -> null
        }
    }
  }

  /**
   * The backdrop for one preview.
   *
   * @param showBackground the preview's `@Preview(showBackground = …)`.
   * @param backgroundColor the preview's `@Preview(backgroundColor = …)`; `0` means unset, which is
   *   the annotation's own default.
   * @param night whether this render's ui-mode carries the night bit. Only consulted for the
   *   [Source.PREVIEW_SHOW_BACKGROUND] rung, where it picks between the day and night sheets — the
   *   same pair [PreviewBackground] paints, so the published backdrop and the painted pixels can
   *   never name different colours for the same preview.
   * @param themeBackground the Material 3 `background` this render composed on (`#AARRGGBB`), when
   *   a theme capture is available. Null in any lane that has no captured theme — discovery, and
   *   the static `composePreviewRenderAll` path, which produce no theme payload.
   * @param themeSurface the captured `surface`, consulted when the theme declared no
   *   [themeBackground]. Both are carried rather than pre-collapsed by the caller so
   *   [Backdrop.source] can say which one answered.
   * @param variantSurface the light/dark variant this render **is**, when the catalog says so (its
   *   baked `theme` token) or the preview's `uiMode` carries the night bit. Distinct from
   *   [catalogSurface]: it speaks for this preview, not for the system.
   * @param catalogSurface the catalog's declared stage, when the caller knows which catalog this
   *   preview belongs to.
   * @param fallback whether to answer [Source.M3_LIGHT_FALLBACK] rather than [Source.NONE] when
   *   nothing above could. For live hosts only — see that constant.
   */
  public fun resolve(
    showBackground: Boolean = false,
    backgroundColor: Long = 0L,
    night: Boolean = false,
    themeBackground: String? = null,
    themeSurface: String? = null,
    variantSurface: CatalogSurface? = null,
    catalogSurface: CatalogSurface? = null,
    fallback: Boolean = false,
  ): Backdrop =
    when {
      // An explicit colour SETTLES the annotation's two background knobs, exactly as it does in
      // `PreviewBackground.resolveArgb`: the renderer fills with it and never consults
      // `showBackground`. So it settles this too, in one of two ways.
      //
      // Opaque enough to sit behind something — a real ground, and the highest evidence there is.
      backgroundColor != 0L && !isTransparentArgb(backgroundColor) ->
        Backdrop(hexArgb(backgroundColor.toInt()), Source.PREVIEW_BACKGROUND_COLOR)
      // Stated but fully transparent (`0x00FFFFFF`): the render's pixels ARE transparent, so there
      // is no ground here — and crucially no `showBackground` sheet either, because the renderer
      // never painted one. Falling to that rung would publish a white sheet over transparent
      // pixels and, being marked preview-explicit, would pin a dark-first catalog's render to
      // white for good. Skip to the rungs that can still answer honestly.
      //
      // `0` is the annotation's own "unset" and is NOT this case — it leaves `showBackground` to
      // speak, which is the ordinary path.
      backgroundColor == 0L && showBackground ->
        Backdrop(
          hexArgb(if (night) PreviewBackground.NIGHT_ARGB else PreviewBackground.DAY_ARGB),
          Source.PREVIEW_SHOW_BACKGROUND,
        )
      // A fully transparent captured colour is not an answer — a theme that resolves its surface to
      // nothing leaves the preview needing a ground exactly as much as before, so fall through
      // rather than publishing `#00000000` and letting a consumer composite onto clear black.
      themeBackground != null && !isTransparent(themeBackground) ->
        Backdrop(themeBackground.uppercase(), Source.THEME_BACKGROUND)
      themeSurface != null && !isTransparent(themeSurface) ->
        Backdrop(themeSurface.uppercase(), Source.THEME_SURFACE)
      variantSurface != null -> Backdrop(sheetFor(variantSurface), Source.PREVIEW_VARIANT)
      catalogSurface != null -> Backdrop(sheetFor(catalogSurface), Source.CATALOG_SURFACE)
      fallback -> Backdrop(M3_LIGHT_BACKGROUND, Source.M3_LIGHT_FALLBACK)
      else -> Backdrop(null, Source.NONE)
    }

  /** Material 3's light `background` role — the [Source.M3_LIGHT_FALLBACK] colour. */
  public const val M3_LIGHT_BACKGROUND: String = "#FFFFFBFE"

  /** The sheet a light/dark surface word resolves to — the same pair [PreviewBackground] paints. */
  private fun sheetFor(surface: CatalogSurface): String =
    hexArgb(
      if (surface == CatalogSurface.DARK) PreviewBackground.NIGHT_ARGB
      else PreviewBackground.DAY_ARGB
    )

  /**
   * A backdrop already resolved upstream, re-resolved against a [catalogSurface] the upstream
   * producer did not know about.
   *
   * This is the join between the two halves of the chain, and it exists so the halves never
   * recompute each other's rungs. Discovery resolves rungs 1–2 from `@Preview` metadata and
   * publishes the result; the catalog default is only known where a catalog is mounted, which is
   * further downstream. A published [Source.NONE] means "the preview itself had nothing to say" —
   * exactly the case the catalog's stage is there to answer — while any other source is the preview
   * speaking for itself and **wins over the catalog default**, which is what makes the per-preview
   * answer per-preview: a `showBackground = false` motion canvas in a dark-first catalog still gets
   * the catalog's dark stage, but an explicitly white specimen in that same catalog keeps its
   * white.
   */
  public fun withCatalogDefault(
    published: Backdrop?,
    catalogSurface: CatalogSurface?,
  ): Backdrop =
    when {
      published != null && published.source in SPEAKS_FOR_THE_PREVIEW -> published
      else -> resolve(catalogSurface = catalogSurface)
    }

  /**
   * The sources that are the preview itself answering, and so outrank a catalog default.
   *
   * [Source.M3_LIGHT_FALLBACK] is deliberately absent: it is a live host's placeholder for "no
   * evidence yet", so a catalog that *has* declared a stage knows strictly more than it does.
   */
  private val SPEAKS_FOR_THE_PREVIEW: Set<Source> =
    setOf(
      Source.PREVIEW_BACKGROUND_COLOR,
      Source.PREVIEW_SHOW_BACKGROUND,
      Source.THEME_BACKGROUND,
      Source.THEME_SURFACE,
      Source.PREVIEW_VARIANT,
      Source.CATALOG_SURFACE,
    )

  /**
   * Luminance below this counts as a dark ground.
   *
   * The question this answers is narrow — *will light-on-transparent content read against this?* —
   * so it weights the channels by perceived brightness (Rec. 709) over the raw sRGB values rather
   * than gamma-decoding first. Over raw channels the threshold crosses at about `#737373` for a
   * neutral grey, which puts both Material 3 dark surfaces (`#1C1B1F`, black) comfortably on the
   * dark side and white comfortably on the light side.
   *
   * The published catalog theme `dark` flag asks a related question against its own threshold; that
   * rule lives in the pinned `@design-parity` export, not here, so the two are **not** guaranteed
   * to agree at the margins. They only ever both apply to a near-black or near-white surface in
   * practice, where they do agree. If a mid-grey system ever appears and the stage disagrees with
   * the palette, this is the seam to reconcile — do it by making one call the other, not by nudging
   * a constant until a screenshot looks right.
   */
  private const val DARK_LUMINANCE_THRESHOLD = 0.45

  /** Whether an `#AARRGGBB` (or `#RRGGBB`) colour is a dark ground. */
  public fun isDarkArgb(color: String): Boolean {
    val rgb = color.removePrefix("#").takeLast(6)
    if (rgb.length != 6) return false
    val value = rgb.toLongOrNull(16) ?: return false
    val r = ((value shr 16) and 0xFF) / 255.0
    val g = ((value shr 8) and 0xFF) / 255.0
    val b = (value and 0xFF) / 255.0
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) < DARK_LUMINANCE_THRESHOLD
  }

  /** Whether an ARGB `Long` from `@Preview(backgroundColor = …)` carries no alpha at all. */
  private fun isTransparentArgb(argb: Long): Boolean = ((argb ushr 24) and 0xFF) == 0L

  /**
   * Whether an `#AARRGGBB` colour is fully transparent. A `#RRGGBB` form is opaque by definition.
   */
  private fun isTransparent(color: String): Boolean {
    val hex = color.removePrefix("#")
    if (hex.length != 8) return false
    return hex.take(2).toIntOrNull(16) == 0
  }

  /** `#AARRGGBB` for an ARGB int, upper-case, matching the data-product wire format. */
  private fun hexArgb(argb: Int): String = "#%08X".format(argb)
}
