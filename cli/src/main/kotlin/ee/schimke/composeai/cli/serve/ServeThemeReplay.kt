package ee.schimke.composeai.cli.serve

/**
 * Applying a declared `@ThemeCatalog` theme to a preview that is **replayed** from a captured
 * Remote Compose document rather than recomposed.
 *
 * A replayed preview has no composition for a `PreviewWrapperProvider` to wrap, so `themeProvider`
 * reaches the render lane as an override nothing can honour and is refused. Its *colours*, though,
 * are named state (`USER:WearM3.<role>`) the player can rewrite in place, so the same request
 * expressed as `rc.<role>=color:…` seeds succeeds. This object is the one place that rewrite
 * happens.
 *
 * It lives here, outside [ServeHttpServer], because **every** lane that turns request overrides
 * into a render has to perform it. The HTTP handlers are the obvious ones; the WebSocket lanes
 * ([ServeLiveSession], [ServeStreamSession]) parse their own overrides from `initial`,
 * `setOverrides` and `switch` messages, and a replay stream is exactly as unable to wrap a provider
 * as a replay snapshot is. A lane that skipped the expansion would push unthemed frames while the
 * viewer showed the theme as selected — unchanged pixels presented as a themed render, which is
 * the #3449 failure with a socket in front of it.
 */
internal object ServeThemeReplay {

  /** The query/message key carrying a declared theme's provider FQN. */
  const val THEME_PROVIDER_PARAM: String = "themeProvider"

  /**
   * [params] after [expand], plus the provider it consumed (null when it expanded nothing).
   *
   * The provider is carried out rather than inferred later because expansion *removes* it from the
   * params: every downstream classification that keys off `themeProvider` — the theme-render lease
   * admission most of all — would otherwise read a plain seeded render and silently skip. A themed
   * thumbnail burst that stops being admitted doesn't fail; it just stops sharing the catalog's
   * allocation, so every page runs the full advertised concurrency straight at the global render
   * semaphore.
   */
  data class Seeding(val params: Map<String, String>, val provider: String? = null)

  /**
   * Whether a refusal for [previewId] is **terminal** — no retry can apply a recomposition-only
   * override, because this preview is replayed from its captured document. Also the axis [expand]
   * turns on.
   */
  fun isReplayed(renderHost: ServeHost, previewId: String): Boolean =
    renderHost.hasRemoteComposeDoc(previewId)

  /**
   * [params] with a `themeProvider` **expanded into the named-value seeds that apply it to a
   * replayed document** ([ServeHost.themeReplayColors]) — or unchanged, when that can't be done.
   *
   * The `themeProvider` key is dropped from the result once expanded: it has been satisfied, and
   * leaving it would have `droppedOverridesFor` report an un-applied override on the very render
   * that applied it.
   *
   * Only for previews that **replay**. A preview whose lane can recompose keeps its `themeProvider`
   * untouched, because re-running the composable applies the whole theme — the typeface included,
   * which no named value can carry. Seeding colours there would silently narrow what a theme means.
   */
  fun expand(renderHost: ServeHost, previewId: String, params: Map<String, String>): Seeding {
    val provider =
      params[THEME_PROVIDER_PARAM]?.takeIf { it.isNotBlank() } ?: return Seeding(params)
    if (!isReplayed(renderHost, previewId)) return Seeding(params)
    val colors = renderHost.themeReplayColors(provider)
    if (colors.isEmpty()) return Seeding(params)
    val seeded = params.toMutableMap()
    seeded.remove(THEME_PROVIDER_PARAM)
    for ((name, value) in colors) {
      // An explicit `rc.` seed on the URL is the caller being more specific than the theme, so it
      // wins — same precedence a per-role override has over a scheme anywhere else.
      //
      // A **blank** one is not that. `ServeOverrides.parse` skips an empty `rc.` value outright, so
      // honouring the bare key here would drop the theme's seed for that role and leave the
      // document on its authored colour — a theme applied in part, reported as applied in full.
      // Blank means absent for this merge, and the theme fills the role.
      val key = "${ServeOverrides.RC_NAMED_PREFIX}$name"
      if (seeded[key].isNullOrBlank()) seeded[key] = "color:$value"
    }
    return Seeding(seeded, provider)
  }
}
