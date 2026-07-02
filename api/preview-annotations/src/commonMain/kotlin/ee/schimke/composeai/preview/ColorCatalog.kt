package ee.schimke.composeai.preview

/**
 * Marks an `androidx.compose.ui.graphics.Color` property as a design token to surface in an
 * auto-generated colour catalog sheet — the compose-ai-tools analogue of Airbnb Showkase's
 * `@ShowkaseColor`. Call-site behaviour mirrors Showkase: annotate the `val` itself, and the
 * compose-preview Gradle plugin aggregates every annotated colour into a rendered swatch sheet with
 * **no `@Preview` wrapper**. [name] defaults to the property name and [group] to the enclosing
 * file/class; tokens sharing a [group] render together, and a module-wide sheet aggregates them
 * all.
 *
 * ```kotlin
 * @ColorCatalog(group = "Brand")
 * val Coral: Color = Color(0xFFFF6F61)
 * ```
 *
 * Discovered by FQN via the property's **backing field** — hence `@Target(FIELD)` plus `BINARY`
 * retention, so the annotation survives into the compiled `.class` files the plugin scans with
 * ClassGraph. This is the key difference from Showkase's own `@ShowkaseColor`, which is
 * `SOURCE`-retained and therefore visible only to Showkase's KSP/KAPT processor, never in bytecode.
 * Consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
annotation class ColorCatalog(val name: String = "", val group: String = "")
