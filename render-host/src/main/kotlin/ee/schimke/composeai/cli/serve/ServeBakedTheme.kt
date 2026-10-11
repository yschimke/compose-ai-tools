package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * The light/dark mode a published catalog render was baked in, from the catalog's naming. A
 * `?uiMode=` naming the baked theme is a no-op and replays the PNG
 * (`CatalogLiveRouting.withoutBakedNoOps`); otherwise it needs a daemon. Too narrow an answer sends
 * browses of already-baked pixels to live renders.
 *
 * The export names stickers `<variant>__<state>[__theme][__size][…]` and omits the theme for its
 * default mode. It never emits two images differing only in theme otherwise, so an untagged id
 * whose `__dark` twin is published is the light half of that pair. Untagged ids without a twin stay
 * unnamed (routed to the daemon).
 *
 * Light-only in the derived direction: dark-first systems strip `uiMode` before parsing
 * (`ServeWeb.SystemDisplay`), and their light renders carry an explicit `__light` token.
 */
public object ServeBakedTheme {

  /**
   * The explicit `light` / `dark` token a flattened catalog id carries, or null. The last such
   * segment after the component slug wins (as in `ServeUrls.wasmAppSrc` and `ServeWeb.cardTheme`),
   * so an earlier non-theme `dark` segment isn't misread.
   */
  public fun token(previewId: String): UiMode? =
    when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
      "dark" -> UiMode.DARK
      "light" -> UiMode.LIGHT
      else -> null
    }

  /**
   * The theme [previewId]'s baked pixels are drawn in, or null when unnamed. In order: the record's
   * [declaredTheme] (`image.theme`), the id's [token], then the folded-pair rule (untagged is light
   * when [publishesId] has its dark twin). Null routes `uiMode` to a real render.
   */
  public fun resolve(
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
   * The id of [previewId]'s twin in [theme] (same component, variant, state, size, props), or null
   * when not published.
   *
   * The theme segment sits mid-id (`<component>__<variant>__<state>[__theme][__size][__<k>-<v>…]`),
   * so it is inserted, not appended: the dark twin of `…__four-actions__compact` is
   * `…__four-actions__dark__compact`. Both spellings of the light half are tried, untagged first.
   */
  public fun twinIn(previewId: String, theme: UiMode, publishesId: (String) -> Boolean): String? {
    val segments = previewId.split(THEME_SEPARATOR)
    // The identity the pair shares; drops the segment [token] found, scanning from the same end.
    val themeAt = segments.indexOfLast { it == "light" || it == "dark" }.takeIf { it > 0 }
    val base = if (themeAt == null) segments else segments.filterIndexed { i, _ -> i != themeAt }
    // Where a theme segment belongs (after slug, variant and state); shorter ids append rather than
    // throw.
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
