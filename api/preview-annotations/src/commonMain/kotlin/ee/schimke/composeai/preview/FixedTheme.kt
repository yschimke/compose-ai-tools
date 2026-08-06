package ee.schimke.composeai.preview

/**
 * Marks a `@Preview` whose **subject is a theme**, so a preview host must never re-render it under
 * a theme override.
 *
 * A theme specimen — a card captioned *"MeshCore · Light · Orbitron / Space Grotesk / JetBrains
 * Mono"*, a colour-role sheet, a typography scale — documents one named theme. Re-rendering it
 * under a different `themeProvider` destroys the very thing it documents: the card still says
 * "Light" and still names Orbitron while drawing dark in the default sans, pixels contradicting
 * their own label.
 *
 * `serve` already exempts a card whose catalog **section** is `"Themes"`, because that section is
 * the author's statement of what the tab *is*. This annotation is the per-preview override for
 * everything else: a specimen that lives outside such a tab (an ungrouped bundle, a `Foundation`
 * section that mixes swatches with components, a plain `compose-preview serve` of one module) has
 * no section to speak for it and says so on the function instead.
 *
 * Discovery picks this up by FQN, like [ScrollingPreview] / [FocusedPreview] — a module that
 * doesn't depend on `ee.schimke.composeai:preview-annotations` simply never surfaces it. The flag
 * rides `previews.json` into the bundle and the published catalog, so the browse surface honours it
 * before any daemon is opened.
 *
 * ```
 * @Preview
 * @FixedTheme
 * @Composable
 * fun MeshcoreLightSpecimen() {
 *     MeshcoreTheme(darkTheme = false) { ThemeSpecimenSheet() }
 * }
 * ```
 *
 * It suppresses only the **theme** override. Every other control — state, locale, font scale,
 * device — still applies, and the card keeps its baked pixels for the theme axis exactly as a
 * preview with no live daemon twin already does.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class FixedTheme
