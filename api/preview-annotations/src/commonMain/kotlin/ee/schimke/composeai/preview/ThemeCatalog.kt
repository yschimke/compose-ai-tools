package ee.schimke.composeai.preview

/**
 * Marks an `androidx.compose.ui.tooling.preview.PreviewWrapperProvider` as one of an app's
 * **alternative themes**, to surface in an auto-generated theme catalog — the theme-scoped sibling
 * of [ColorCatalog] / [TypographyCatalog]. Where those catalog *static* token `val`s, this catalogs
 * a whole resolved theme: the compose-preview plugin renders a specimen sheet per annotated theme
 * by composing the provider's `Wrap(content)` around a canned Material 3 role + type-scale grid, so
 * the sheet shows the **live** `MaterialTheme.colorScheme` / `typography` the theme resolves to
 * (unlike the reflection-only colour/type catalogs, which never enter composition).
 *
 * This is the N-ary generalization of `@Preview(uiMode = …)`: an app declares each palette / brand
 * as its own theme instead of the single built-in light/dark axis. A `ThemePalette.Meshcore` vs
 * `ThemePalette.Dynamic` matrix, say, becomes one annotated provider each.
 *
 * ```kotlin
 * @ThemeCatalog(name = "Meshcore", group = "Brand")
 * class MeshcoreThemeCatalog : PreviewWrapperProvider {
 *   @Composable override fun Wrap(content: @Composable () -> Unit) =
 *     MeshcoreTheme(palette = ThemePalette.Meshcore) { content() }
 * }
 * ```
 *
 * Discovered by FQN on the **provider class** — hence `@Target(CLASS)` plus `BINARY` retention, so
 * the annotation survives into the compiled `.class` files the plugin scans with ClassGraph (no
 * KSP/KAPT). [name] defaults to the provider's simple class name and [group] to the enclosing
 * package; themes sharing a [group] are catalogued together. The provider must implement
 * `PreviewWrapperProvider` (the same interface `@PreviewWrapper` uses) — that's how the renderer
 * invokes it. Consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class ThemeCatalog(val name: String = "", val group: String = "")
