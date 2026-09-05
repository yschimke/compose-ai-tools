package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * The light/dark mode a published catalog render was **baked in**, from the catalog's own naming.
 *
 * The whole "browsing is instant" property of a trusted catalog rests on this one question: a
 * `?uiMode=` that names the theme the sticker already shows is a no-op, so the request replays the
 * baked PNG (`CatalogLiveRouting.withoutBakedNoOps`); one that names the other theme has to reach a
 * daemon. Answer it too narrowly and every browse of a render whose theme cannot be named pays for
 * a live render of pixels that are already on disk.
 *
 * Which is exactly what an **untagged** id used to cost. The catalog export names a sticker
 * `<variant>__<state>[__theme][__size][…]` and omits the theme segment for the mode it draws by
 * default — so `button-filled__ideal__l-square` (published alongside
 * `button-filled__ideal__l-square__dark`) carries no token at all even though it is the light half
 * of that pair, and its `previewId` says so outright:
 * `…ButtonsKt.FilledButton_Light_VARIANT_l-square`. On `m3-catalog` that is 1977 of 4120 published
 * renders whose ordinary light browse — the viewer writes `uiMode` into every render URL — routed
 * to the daemon instead of the sticker, at seconds per image against a sub-second baked replay, and
 * under `Cache-Control: no-store` so the next visit paid again (compose-ai-tools#4997).
 *
 * The pairing is the evidence, not a guess about light-first catalogs: the export refuses to emit
 * two images of one component that share `{variant, state, theme, size, props}`, so an untagged id
 * whose `__dark` twin is published differs from that twin in the theme axis alone. It is therefore
 * the non-dark member of a folded pair. An untagged id with no such twin stays unnamed and keeps
 * routing to the daemon — a catalog that bakes only one theme has said nothing about which. On
 * `m3-catalog` all but 4 of those 1977 are paired, and the 4 are `color-role-grid` contrast
 * specimens whose *state* is named `light-medium-contrast` / `dark-high-contrast`: no theme axis to
 * pair across, and correctly left on the daemon.
 *
 * Deliberately **light-only** in the derived direction, and that asymmetry is not an oversight: a
 * dark-first system never reaches this question, because `ServeWeb.SystemDisplay`'s
 * `normalizeOverrideParams` strips `uiMode` from the raw parameter map before it is ever parsed. A
 * dark-first catalog that bakes a light pair still names its light renders with an explicit
 * `__light` token, which [token] reads.
 */
object ServeBakedTheme {

  /**
   * The explicit `light` / `dark` token a flattened catalog id carries, or null when it carries
   * none.
   *
   * The **last** such segment past the component slug wins, matching `ServeUrls.wasmAppSrc` and
   * `ServeWeb.cardTheme`. Scanning for `dark` first would misread a non-theme segment named `dark`
   * in an otherwise-light variant, wrongly treating `uiMode=dark` as a no-op and dropping the
   * override.
   */
  fun token(previewId: String): UiMode? =
    when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
      "dark" -> UiMode.DARK
      "light" -> UiMode.LIGHT
      else -> null
    }

  /**
   * The theme [previewId]'s baked pixels are drawn in, or null when the catalog names none.
   *
   * Three rungs, narrowing: the catalog's own [declaredTheme] for the record (`image.theme` in
   * `catalog.json`), the id's explicit [token], then the folded-pair rule above — an untagged id is
   * light when [publishesId] reports its dark twin published.
   *
   * Null is the honest answer, not a default: it leaves a `uiMode` request routed to a real render,
   * which is what a session that cannot name the sticker's theme owes the caller.
   */
  fun resolve(
    previewId: String,
    declaredTheme: String? = null,
    publishesId: (String) -> Boolean = { false },
  ): UiMode? =
    when (declaredTheme?.trim()?.lowercase()) {
      "dark" -> UiMode.DARK
      "light" -> UiMode.LIGHT
      else ->
        token(previewId)
          ?: UiMode.LIGHT.takeIf { twinIn(previewId, UiMode.DARK, publishesId) != null }
    }

  /**
   * The id of the sticker [previewId] is paired with in [theme] — the same component, variant,
   * state, size and props, drawn in the other mode — or null when the catalog publishes none.
   *
   * **The theme segment is not a suffix**, which is the whole reason this is a function rather than
   * string concatenation. The export names a sticker
   * `<component>__<variant>__<state>[__theme][__size][__<k>-<v>…]`, so the theme sits in the middle
   * of every id that carries a breakpoint or a prop: the dark twin of
   * `bottomappbar-standard__ideal__four-actions__compact` is
   * `bottomappbar-standard__ideal__four-actions__dark__compact`, not `…__compact__dark`. Appending
   * instead of inserting silently missed 99 of `m3-catalog`'s 1973 paired renders — every one that
   * names a size or a prop — which is exactly the population a catalog drawn across breakpoints is
   * made of.
   *
   * Both spellings of the light half are tried, because a catalog may name it either way: the
   * export omits the segment for the mode it draws by default, but nothing stops a record declaring
   * `theme: "light"` explicitly, and 44 of `m3-catalog`'s do. Untagged is tried first — it is what
   * the default mode actually produces.
   */
  fun twinIn(previewId: String, theme: UiMode, publishesId: (String) -> Boolean): String? {
    val segments = previewId.split(THEME_SEPARATOR)
    // Everything but the theme axis: the identity the pair shares. Drops the token [token] found,
    // scanning from the same end so the two cannot disagree about which segment is the theme.
    val themeAt = segments.indexOfLast { it == "light" || it == "dark" }.takeIf { it > 0 }
    val base = if (themeAt == null) segments else segments.filterIndexed { i, _ -> i != themeAt }
    // Where a theme segment belongs: after the component slug, variant and state, before the size
    // and props. A shorter id (one the export would not itself emit) appends rather than throws.
    val at = minOf(THEME_SEGMENT_INDEX, base.size)
    val spellings = buildList {
      // The default mode's own spelling — no segment at all — is only ever light's.
      if (theme == UiMode.LIGHT) add(base)
      add(base.subList(0, at) + theme.name.lowercase() + base.subList(at, base.size))
    }
    return spellings
      .map { it.joinToString(THEME_SEPARATOR) }
      .firstOrNull { it != previewId && publishesId(it) }
  }

  /** Separates the segments of a flattened catalog id. */
  private const val THEME_SEPARATOR = "__"

  /** `<component>__<variant>__<state>` precede the theme; the size and props follow it. */
  private const val THEME_SEGMENT_INDEX = 3
}
