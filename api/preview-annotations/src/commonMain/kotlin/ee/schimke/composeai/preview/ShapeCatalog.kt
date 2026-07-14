package ee.schimke.composeai.preview

/**
 * Marks an `androidx.compose.ui.graphics.Shape` (a single corner/shape token) **or** a whole
 * `androidx.compose.material3.Shapes` scale as a design token to surface in an auto-generated shape
 * catalog sheet — the shape-scoped sibling of [ColorCatalog] / [TypographyCatalog], and the missing
 * third leg of the Material 3 theme triad (colour / type / **shape**). Call-site behaviour mirrors
 * its siblings: annotate the `val` itself, and the compose-preview Gradle plugin aggregates every
 * annotated shape into a rendered specimen sheet with **no `@Preview` wrapper** — each row draws a
 * bounded box clipped to that shape, so a corner-radius / family regression surfaces as a pixel
 * diff. [name] defaults to the property name and [group] to the enclosing file/class; tokens
 * sharing a [group] render together, and a module-wide sheet aggregates them all.
 *
 * Two field types are recognised, dispatched by the annotated property's declared type:
 * * a single `Shape` (e.g. `RoundedCornerShape(12.dp)`) → one shape row.
 * * a whole `Shapes` (e.g. `Shapes(small = …, medium = …)`) → the five M3 shape roles (`extraSmall`
 *   … `extraLarge`) expanded into one row each. This is the "entire Shapes" catalog the
 *   theme-override surface offers as a selectable shape scale, the shape counterpart to a whole
 *   `ColorScheme` under [ColorCatalog] or a whole `Typography` under [TypographyCatalog].
 *
 * ```kotlin
 * @ShapeCatalog(group = "Brand")
 * val Pill: Shape = RoundedCornerShape(50)
 *
 * @ShapeCatalog(name = "Brand shapes")
 * val BrandShapes: Shapes = Shapes(small = RoundedCornerShape(4.dp), large = RoundedCornerShape(24.dp))
 * ```
 *
 * Discovered by FQN via the property's **backing field** — hence `@Target(FIELD)` plus `BINARY`
 * retention, so the annotation survives into the compiled `.class` files the plugin scans with
 * ClassGraph (no KSP/KAPT). Consumers depend on `ee.schimke.composeai:preview-annotations` to use
 * it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
annotation class ShapeCatalog(val name: String = "", val group: String = "")
