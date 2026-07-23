package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into being captured in **both** light and dark, so it publishes a
 * paired `…__light` / `…__dark` sticker instead of a single-theme one.
 *
 * ## Why
 *
 * The preview server serves a component or screen's baked PNG instantly and only wakes the (slow,
 * cold-startable) render daemon when an override can't be replayed from a baked sticker. A `uiMode`
 * override is a no-op — served from baked pixels — **only when the requested theme matches a baked
 * variant** (`CatalogLiveRouting.overridesAffectRender`). A preview that bakes just its declared
 * theme therefore has no dark sticker, so switching the viewer to night mode (or a dark-first
 * catalog's sticky theme) is a real override that bumps the request onto the daemon — the "slow to
 * show a screen in dark" path. Screens, baked once, are the usual victims.
 *
 * `@LightDarkPreview` closes that gap **at bake time**: discovery fans the function out into two
 * captures — one forced light (`UI_MODE_NIGHT_NO`) and one forced dark (`UI_MODE_NIGHT_YES`) —
 * regardless of the theme the `@Preview` itself declares. Both land as ordinary uiMode variants
 * (`…__light` / `…__dark`), which the catalog join already folds onto one component
 * (`mergeByFunction`) and the serve grid already presents as a single Light/Dark swap card. So
 * night mode has a baked sticker to serve and navigation stays instant and daemon-free; the daemon
 * is left for overrides a static PNG genuinely can't represent (device, font scale, locale,
 * orientation, author knobs).
 *
 * ## Use
 *
 * Put it on a `@Preview` whose body renders through a theme that honours the system night bit
 * (`isSystemInDarkTheme()` / the `uiMode` night qualifier) — an app screen wrapped in the app
 * theme, a themed component. A hard-coded single-palette body renders identically in both passes
 * (two equal stickers), so annotate only previews whose theme actually reacts to night mode.
 *
 * ```
 * @Preview
 * @LightDarkPreview
 * @Composable
 * fun ChatScreenPreview() {
 *   MeshCoreTheme { ChatScreen(...) }   // MeshCoreTheme reads isSystemInDarkTheme()
 * }
 * ```
 *
 * The Gradle plugin's discovery task picks this up by FQN — independently of whether the annotation
 * artifact is on the consumer's compile classpath at plugin-apply time — so a project can carry the
 * annotation purely as a bake-time marker. Consumers that want to reference it in their own code
 * depend on `ee.schimke.composeai:preview-annotations`. Sibling capture-shaping annotation to
 * `@ScrollingPreview` / `@LauncherWidgetResize`: same BINARY-retention, function-targeted,
 * FQN-matched shape.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class LightDarkPreview
