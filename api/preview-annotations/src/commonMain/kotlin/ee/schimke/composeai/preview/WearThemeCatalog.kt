package ee.schimke.composeai.preview

/**
 * The **Wear** counterpart of [ThemeCatalog]: marks an
 * `androidx.compose.ui.tooling.preview.PreviewWrapperProvider` as one of a Wear app's alternative
 * themes, so the compose-preview plugin renders a specimen sheet per annotated theme.
 *
 * Same contract as [ThemeCatalog] — discovered by FQN on the provider **class**, `BINARY` retention
 * so it survives into the `.class` files ClassGraph scans, [name] defaulting to the simple class
 * name and [group] to the enclosing package — and it feeds the preview server's theme switcher
 * identically, so a Wear preview can be re-rendered live under any declared theme.
 *
 * **Why a separate annotation rather than one that adapts.** The two platforms don't share a
 * `MaterialTheme`: [ThemeCatalog]'s specimen reads `androidx.compose.material3.MaterialTheme`,
 * which a Wear provider never installs — it sets `androidx.wear.compose.material3.MaterialTheme`
 * instead. A provider annotated with the wrong one therefore renders the *other* platform's
 * defaults rather than the theme it declares, silently and with no error. Sniffing which one a
 * provider installed would mean guessing (an app can legitimately have both libraries on the
 * classpath), so the platform is declared instead of inferred. The role sets differ too — Wear's
 * `ColorScheme` carries `primaryDim`/`surfaceContainer*` and no `surfaceVariant`-style ramp — so
 * the two specimens are genuinely different sheets, not one sheet with a branch in it.
 *
 * ```kotlin
 * @WearThemeCatalog(name = "KotlinConf", group = "Conference")
 * class KotlinConfThemeCatalog : PreviewWrapperProvider {
 *   @Composable override fun Wrap(content: @Composable () -> Unit) =
 *     ConfettiConferenceTheme(conferenceId = "kotlinconf2025", content = content)
 * }
 * ```
 *
 * Consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class WearThemeCatalog(val name: String = "", val group: String = "")
