package ee.schimke.composeai.preview

/**
 * Marks an `androidx.compose.ui.text.TextStyle` property as a design token to surface in an
 * auto-generated typography catalog sheet — the type-scale sibling of [ColorCatalog], and the
 * compose-ai-tools analogue of Airbnb Showkase's `@ShowkaseTypography`. Call-site behaviour mirrors
 * Showkase: annotate the `val` itself, and the compose-preview Gradle plugin aggregates every
 * annotated style into a rendered specimen sheet with **no `@Preview` wrapper** — each row shows
 * the token name plus a sample line set in that style, so size / weight / family regressions
 * surface as a pixel diff. [name] defaults to the property name and [group] to the enclosing
 * file/class; tokens sharing a [group] render together, and a module-wide sheet aggregates them
 * all.
 *
 * Two field types are recognised, dispatched by the annotated property's declared type:
 * * a single `androidx.compose.ui.text.TextStyle` → one sample-text row in that style.
 * * a whole `androidx.compose.material3.Typography` → the Material 3 type scale (display … label)
 *   expanded into one sample row each. This is the "entire Typography" catalog the theme-override
 *   surface offers as a selectable type scale — how a catalog declares, say, a Roboto Flex vs a
 *   Google Sans Flex face for the whole scale — the type counterpart to a whole `ColorScheme` under
 *   [ColorCatalog] or a whole `Shapes` under [ShapeCatalog].
 *
 * ```kotlin
 * @TypographyCatalog(group = "Display")
 * val DisplayLarge: TextStyle = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal)
 *
 * @TypographyCatalog(name = "Roboto Flex")
 * val RobotoFlexType: Typography = catalogTypography(RobotoFlex)
 * ```
 *
 * Discovered by FQN via the property's **backing field** — hence `@Target(FIELD)` plus `BINARY`
 * retention, so the annotation survives into the compiled `.class` files the plugin scans with
 * ClassGraph. This is the key difference from Showkase's own `@ShowkaseTypography`, which is
 * `SOURCE`-retained and therefore visible only to Showkase's KSP/KAPT processor, never in bytecode.
 * Consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
annotation class TypographyCatalog(val name: String = "", val group: String = "")
