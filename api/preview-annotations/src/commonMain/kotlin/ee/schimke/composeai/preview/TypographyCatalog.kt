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
 * ```kotlin
 * @TypographyCatalog(group = "Display")
 * val DisplayLarge: TextStyle = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal)
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
