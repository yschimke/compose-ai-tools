package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Produces the composition body for a single [RenderPreviewEntry]. Selection happens through
 * [strategyFor] — driven by the [PreviewKind] recorded at discovery time.
 *
 * Each strategy owns its own reflection + framing logic so the main Robolectric pipeline stays
 * oblivious to whether it's driving a @Composable or a tile.
 */
internal interface PreviewRenderStrategy {
  @Composable
  fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int, previewArgs: List<Any?>)
}

private val STRATEGIES: Map<PreviewKind, PreviewRenderStrategy> =
  mapOf(
    PreviewKind.COMPOSE to ComposePreviewStrategy,
    PreviewKind.TILE to TilePreviewStrategy,
    PreviewKind.NOTIFICATION to NotificationPreviewStrategy,
    PreviewKind.GLANCE_APPWIDGET to GlanceAppWidgetPreviewStrategy,
    PreviewKind.CATALOG to CatalogPreviewStrategy,
    PreviewKind.THEME_CATALOG to ThemeCatalogStrategy,
  )

internal fun strategyFor(kind: PreviewKind): PreviewRenderStrategy =
  STRATEGIES[kind] ?: error("No render strategy registered for PreviewKind.$kind")

/**
 * Default strategy: reflect the `@Composable` and invoke it through the Composer. Honours
 * `@PreviewWrapper` by looking up the provider's `Wrap(content)` method.
 *
 * No `Box(Modifier.fillMaxSize().background(bgColor)) { ... }` wrapper —
 * [RobolectricRenderTestBase.renderDefault] paints the background on the activity window before
 * `setContent`, so we don't need to emit layout-node bytecode (`ComposeUiNode.setCompositeKeyHash`
 * etc.) here. That's what keeps the renderer runnable against older compose-ui BOMs (see the
 * commentary in `renderDefault` for the full compat story).
 */
private object ComposePreviewStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    val clazz = Class.forName(preview.className)
    // For previews with a `@PreviewParameter` argument, look up the
    // overload whose Composable-visible parameters match the supplied
    // values. The pipeline only injects one value today (Studio-parity:
    // multi-@PreviewParameter functions aren't supported), but passing
    // the full list keeps the lookup shape honest with the invocation.
    val composableMethod =
      if (previewArgs.isEmpty()) {
        clazz.getDeclaredComposableMethod(preview.functionName)
      } else {
        findComposableMethodWithArgs(clazz, preview.functionName, previewArgs)
      }
    // Kotlin `private fun` previews compile to JVM-private methods.
    // `getDeclaredComposableMethod` still resolves them (it scans
    // `declaredMethods`), but the reflective `invoke` below would throw
    // IllegalAccessException, so open the method up first — the same trick
    // `resolvePreviewReceiver` uses for private/internal receiver classes.
    // Guarded with `runCatching`: a SecurityManager or strong module
    // encapsulation can refuse, in which case we still attempt the invoke
    // (which succeeds for public/internal previews) rather than fail
    // resolution outright.
    runCatching { composableMethod.asMethod().isAccessible = true }
    // Top-level `@Preview` functions compile into static methods on the
    // file's synthetic `FooKt` class, so `receiver = null` works. Google's
    // `com.android.compose.screenshot` tool (and Paparazzi-style tests)
    // idiomatically wrap previews in a regular `class ScreenshotTest { ... }`
    // — `SessionDetailsPreview` is then an instance method and invoking
    // with a null receiver throws `NullPointerException: Cannot invoke
    // "Object.getClass()" because "obj" is null` inside
    // `ComposableMethod.invoke`. Mirror how Compose tooling's
    // `ComposeViewAdapter` resolves the receiver: prefer the Kotlin
    // `object` singleton (INSTANCE), else instantiate via the nullary
    // constructor, else fall back to null for static methods.
    val receiver = resolvePreviewReceiver(clazz)
    val body: @Composable () -> Unit = {
      composableMethod.invoke(currentComposer, receiver, *previewArgs.toTypedArray())
    }
    val wrapperFqn = preview.params.wrapperClassName
    if (wrapperFqn != null) {
      val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
      resolved.first.invoke(currentComposer, resolved.second, body)
    } else {
      body()
    }
  }
}

/**
 * Resolves the `ComposableMethod` for a preview function that declares `@PreviewParameter`
 * arguments, where parameter types aren't known statically. Walks `declaredMethods`, picks the
 * overload whose leading JVM parameter types line up with `previewArgs` (receiver types match the
 * runtime class of each value, plus the usual trailing Composer + changed int-bits), then hands
 * that shape to `Class<*>.getDeclaredComposableMethod(name, vararg parameterTypes)` — the only
 * officially supported way to produce a `ComposableMethod`.
 *
 * Null entries in [previewArgs] are matched against the declared parameter's box type (Kotlin
 * nullable types already compile to boxed reference types). Primitive-typed non-null values are
 * auto-boxed in [previewArgs], so we check both box and primitive forms.
 */
internal fun findComposableMethodWithArgs(
  clazz: Class<*>,
  name: String,
  previewArgs: List<Any?>,
): androidx.compose.runtime.reflect.ComposableMethod {
  val argCount = previewArgs.size
  // Compose compiler emits `(…args, Composer, changed[, defaultBits…])`
  // at the JVM level, so a method with N composable-visible params has at
  // least N + 2 JVM params. The default-bits tail is emitted when the
  // preview function declares default arguments we didn't supply.
  val candidate =
    clazz.declaredMethods.firstOrNull { m ->
      m.name == name && m.parameterCount >= argCount + 2 && argsMatch(m, previewArgs)
    }
      ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking ${previewArgs.size} parameter(s); " +
          "check that the @PreviewParameter provider's value type matches the preview's parameter type."
      )
  val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
  return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}

private fun argsMatch(method: java.lang.reflect.Method, previewArgs: List<Any?>): Boolean {
  for ((i, arg) in previewArgs.withIndex()) {
    val expected = method.parameterTypes[i]
    if (arg == null) {
      // A null argument can satisfy any reference parameter; a primitive
      // JVM parameter can't accept null, so it's an immediate mismatch.
      if (expected.isPrimitive) return false
      continue
    }
    val actual = arg.javaClass
    if (expected.isAssignableFrom(actual)) continue
    // Auto-boxing: `int` vs `Integer`, etc. `expected.kotlin.javaObjectType`
    // is the box class for primitives; for reference types it's itself.
    if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
    return false
  }
  return true
}

/**
 * Resolves the JVM receiver instance to pass into `ComposableMethod.invoke(composer, receiver, …)`
 * for a preview function declared on [clazz]. Extracted as a top-level internal function so
 * [PreviewReceiverTest] can exercise it without standing up a Robolectric sandbox. Returns:
 * - the `INSTANCE` field of a Kotlin `object` (singleton receiver);
 * - a fresh nullary-ctor instance for regular classes (Google's `com.android.compose.screenshot`
 *   style: `class ScreenshotTest { @Preview fun …}`);
 * - `null` for top-level functions — those compile into static methods on the file's synthetic
 *   `FooKt` class, and `ComposableMethod.invoke` accepts a null receiver for static methods.
 *
 * Matches how Compose tooling's `ComposeViewAdapter` resolves receivers in the Studio preview pane.
 */
internal fun resolvePreviewReceiver(clazz: Class<*>): Any? {
  runCatching { clazz.getField("INSTANCE").get(null) }
    .getOrNull()
    ?.let {
      return it
    }
  // Regular class: instantiate via nullary ctor. `setAccessible(true)` so
  // private/internal classes work too (Google's screenshotTest classes
  // are typically package-private or internal).
  return runCatching {
      val ctor = clazz.getDeclaredConstructor()
      ctor.isAccessible = true
      ctor.newInstance()
    }
    .getOrNull()
}

/**
 * Notifications strategy: invoke the non-composable `(Context) -> Notification` function and
 * inflate the resulting `RemoteViews` through an `AndroidView`. See [NotificationPreviewComposable]
 * for the heavy lifting.
 */
private object NotificationPreviewStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    NotificationPreviewComposable(
      className = preview.className,
      functionName = preview.functionName,
      previewId = preview.id,
      widthDp = widthDp,
    )
  }
}

/**
 * Glance app-widget strategy: wrap the user's `@Composable @GlanceComposable` function in a
 * synthetic `GlanceAppWidget.providePreview(...)`, materialise it to `RemoteViews` via
 * `composeForPreview(...)`, and host the inflated tree inside an `AndroidView`. See
 * [GlanceAppWidgetPreviewComposable] for the heavy lifting.
 */
private object GlanceAppWidgetPreviewStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    GlanceAppWidgetPreviewComposable(
      className = preview.className,
      functionName = preview.functionName,
      widthDp = widthDp,
      heightDp = heightDp,
    )
  }
}

/**
 * Tiles strategy: invoke the non-composable preview function, drive the returned
 * [androidx.wear.tiles.tooling.preview.TilePreviewData] through `TileRenderer`, and host the
 * inflated View via an `AndroidView`. See [TilePreviewComposable] for the heavy lifting.
 */
private object TilePreviewStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    // `@PreviewParameter` doesn't apply to tile previews — discovery
    // drops the provider FQN for `PreviewKind.TILE`, so this list is
    // always empty here.
    TilePreviewComposable(
      className = preview.className,
      functionName = preview.functionName,
      widthDp = widthDp,
      heightDp = heightDp,
      device = preview.params.device,
    )
  }
}

/**
 * Design-token catalog strategy: there's no consumer composable to invoke. Instead the entry's
 * [RenderPreviewParams.catalogTokens] name properties on the consumer's compiled classes, whose
 * *values* we reflect at render time and lay out as a labelled swatch sheet — the auto-discovered
 * analogue of the hand-written `ColorSpecimen` gallery.
 *
 * The layout is intentionally self-contained (foundation `BasicText` + explicit neutral colours, no
 * `MaterialTheme`) so the sheet reads the same regardless of the consumer's theme, and so the
 * renderer takes no dependency on `:color-preview-runtime` — keeping the load-bearing
 * renderer↔consumer AndroidX alignment untouched. A token whose value can't be reflected is skipped
 * rather than failing the whole sheet.
 */
private object CatalogPreviewStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    val rows =
      remember(preview.id) {
        preview.params.catalogTokens.flatMap { token ->
          runCatching { catalogRowsFor(token) }.getOrDefault(emptyList())
        }
      }
    // Emit the resolved-token sidecar (issue #2167) once per sheet, alongside the PNG. Keyed by
    // `preview.id` so it fires on first composition only — the render composes exactly once.
    remember(preview.id) { CatalogTokenSidecar.write(preview.id, preview.params.catalogTokens) }
    Box(Modifier.fillMaxSize().background(CATALOG_SHEET_BACKGROUND).padding(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
          when (row) {
            is CatalogRow.Swatch -> CatalogSwatchRow(label = row.label, color = row.color)
            is CatalogRow.Type -> CatalogTypeRow(label = row.label, style = row.style)
            is CatalogRow.ShapeSpec -> CatalogShapeRow(label = row.label, shape = row.shape)
          }
        }
      }
    }
  }
}

/**
 * Theme catalog strategy: resolve the `@ThemeCatalog` provider named on the preview's
 * `wrapperClassName` and compose its `Wrap(content)` around [ThemeSpecimen] — the exact wrapper
 * machinery `@PreviewWrapper` uses (see [ComposePreviewStrategy]). Because the specimen renders
 * *inside* the theme, it reads the theme's **live** resolved `MaterialTheme.colorScheme` /
 * `typography` — the composition-scoped counterpart to the reflection-only `@ColorCatalog` /
 * `@TypographyCatalog` sheets, which never enter composition.
 */
private object ThemeCatalogStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    val wrapperFqn = preview.params.wrapperClassName ?: return
    val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
    // Key the emitted per-theme token sidecar by the theme's display name (falls back to the
    // preview id). `params.name` is the clean theme name discovery stamped on the synthetic
    // entry (e.g. "Brand Light").
    val themeName = preview.params.name ?: preview.id
    val specimen: @Composable () -> Unit = { ThemeSpecimen(preview.id, themeName) }
    resolved.first.invoke(currentComposer, resolved.second, specimen)
  }
}

/**
 * The canned specimen composed inside a declared theme: the Material 3 colour roles as swatches and
 * a few type-scale styles as samples, read from `MaterialTheme.colorScheme` / `.typography` in the
 * current composition — i.e. whatever the enclosing `@ThemeCatalog` provider resolved to. Laid out
 * on the neutral catalog sheet (not the theme's own surface) so the fixed dark labels stay legible
 * for a dark theme too; the swatches carry the theme's colours, the samples its type scale.
 */
@Composable
private fun ThemeSpecimen(previewId: String, themeName: String) {
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val shapes = MaterialTheme.shapes
  val roles =
    listOf(
      "primary" to scheme.primary,
      "onPrimary" to scheme.onPrimary,
      "primaryContainer" to scheme.primaryContainer,
      "secondary" to scheme.secondary,
      "secondaryContainer" to scheme.secondaryContainer,
      "tertiary" to scheme.tertiary,
      "error" to scheme.error,
      "surface" to scheme.surface,
      "onSurface" to scheme.onSurface,
      "surfaceVariant" to scheme.surfaceVariant,
      "outline" to scheme.outline,
    )
  val types =
    listOf(
      "displaySmall" to typography.displaySmall,
      "titleLarge" to typography.titleLarge,
      "bodyLarge" to typography.bodyLarge,
      "labelSmall" to typography.labelSmall,
    )
  // The shape scale the theme resolved — the third leg of the M3 triad, so a `@ThemeCatalog` sheet
  // shows colour + type + shape (issue #2179 / shape parity). Read from `MaterialTheme.shapes` in
  // the theme's own composition, same as the colours and type above.
  val shapeRoles =
    listOf(
      "extraSmall" to shapes.extraSmall,
      "small" to shapes.small,
      "medium" to shapes.medium,
      "large" to shapes.large,
      "extraLarge" to shapes.extraLarge,
    )
  // Emit the resolved-token sidecar (issue #2179) once per sheet, alongside the PNG — the live
  // `MaterialTheme` values above, captured *inside* the theme's composition (the differentiator
  // from the reflection-only `@ColorCatalog` / `@TypographyCatalog` sidecars). Keyed by theme so
  // design-parity's `catalog-export` maps each onto a Figma variable mode. `remember(previewId)`
  // fires on first composition only — the render composes exactly once.
  remember(previewId) {
    CatalogTokenSidecar.writeResolved(
      previewId,
      themeName,
      roles.map { (label, color) -> CatalogTokenSidecar.ResolvedToken.Colour(label, color) } +
        types.map { (label, style) -> CatalogTokenSidecar.ResolvedToken.Type(label, style) } +
        shapeRoles.map { (label, shape) -> CatalogTokenSidecar.ResolvedToken.ShapeToken(label, shape) },
    )
  }
  Box(Modifier.fillMaxSize().background(CATALOG_SHEET_BACKGROUND).padding(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      for ((label, color) in roles) CatalogSwatchRow(label = label, color = color)
      for ((label, style) in types) CatalogTypeRow(label = label, style = style)
      for ((label, shape) in shapeRoles) CatalogShapeRow(label = label, shape = shape)
    }
  }
}

/** A resolved catalog row — a colour swatch, a type specimen, or a shape — ready to lay out. */
private sealed interface CatalogRow {
  data class Swatch(val label: String, val color: Color) : CatalogRow

  data class Type(val label: String, val style: TextStyle) : CatalogRow

  data class ShapeSpec(val label: String, val shape: Shape) : CatalogRow
}

/**
 * Resolves one [CatalogToken] to the row(s) it contributes. A single-token kind
 * (`COLOR` / `TEXT_STYLE` / `SHAPE`) yields one row; a whole-object kind
 * (`COLOR_SCHEME` / `TYPOGRAPHY` / `SHAPES`) reflects the object off the consumer class and expands
 * it into the Material 3 role rows for that scale (each row labelled `<token> · <role>`), so a
 * declared whole `ColorScheme` / `Typography` / `Shapes` — the "entire object" catalog the
 * theme-override surface offers — renders as a full specimen sheet.
 */
private fun catalogRowsFor(token: CatalogToken): List<CatalogRow> =
  when (token.tokenKind) {
    CatalogTokenKind.COLOR ->
      listOf(
        CatalogRow.Swatch(
          token.label,
          CatalogValueReflection.reflectColor(token.className, token.member),
        )
      )
    CatalogTokenKind.TEXT_STYLE ->
      listOf(
        CatalogRow.Type(
          token.label,
          CatalogValueReflection.reflectTextStyle(token.className, token.member),
        )
      )
    CatalogTokenKind.SHAPE ->
      listOf(
        CatalogRow.ShapeSpec(
          token.label,
          CatalogValueReflection.reflectAs(token.className, token.member),
        )
      )
    CatalogTokenKind.COLOR_SCHEME -> {
      val scheme = CatalogValueReflection.reflectAs<ColorScheme>(token.className, token.member)
      colorSchemeRoles(scheme).map { (role, color) ->
        CatalogRow.Swatch("${token.label} · $role", color)
      }
    }
    CatalogTokenKind.TYPOGRAPHY -> {
      val typography = CatalogValueReflection.reflectAs<Typography>(token.className, token.member)
      typographyRoles(typography).map { (role, style) ->
        CatalogRow.Type("${token.label} · $role", style)
      }
    }
    CatalogTokenKind.SHAPES -> {
      val shapes = CatalogValueReflection.reflectAs<Shapes>(token.className, token.member)
      shapesRoles(shapes).map { (role, shape) ->
        CatalogRow.ShapeSpec("${token.label} · $role", shape)
      }
    }
  }

/** The Material 3 colour roles, in specimen order, read off a resolved [ColorScheme]. */
internal fun colorSchemeRoles(scheme: ColorScheme): List<Pair<String, Color>> =
  listOf(
    "primary" to scheme.primary,
    "onPrimary" to scheme.onPrimary,
    "primaryContainer" to scheme.primaryContainer,
    "onPrimaryContainer" to scheme.onPrimaryContainer,
    "secondary" to scheme.secondary,
    "secondaryContainer" to scheme.secondaryContainer,
    "tertiary" to scheme.tertiary,
    "tertiaryContainer" to scheme.tertiaryContainer,
    "error" to scheme.error,
    "errorContainer" to scheme.errorContainer,
    "background" to scheme.background,
    "onBackground" to scheme.onBackground,
    "surface" to scheme.surface,
    "onSurface" to scheme.onSurface,
    "surfaceVariant" to scheme.surfaceVariant,
    "onSurfaceVariant" to scheme.onSurfaceVariant,
    "outline" to scheme.outline,
    "outlineVariant" to scheme.outlineVariant,
  )

/** The Material 3 type scale, in specimen order, read off a resolved [Typography]. */
internal fun typographyRoles(typography: Typography): List<Pair<String, TextStyle>> =
  listOf(
    "displayLarge" to typography.displayLarge,
    "displayMedium" to typography.displayMedium,
    "displaySmall" to typography.displaySmall,
    "headlineLarge" to typography.headlineLarge,
    "headlineMedium" to typography.headlineMedium,
    "headlineSmall" to typography.headlineSmall,
    "titleLarge" to typography.titleLarge,
    "titleMedium" to typography.titleMedium,
    "titleSmall" to typography.titleSmall,
    "bodyLarge" to typography.bodyLarge,
    "bodyMedium" to typography.bodyMedium,
    "bodySmall" to typography.bodySmall,
    "labelLarge" to typography.labelLarge,
    "labelMedium" to typography.labelMedium,
    "labelSmall" to typography.labelSmall,
  )

/** The five Material 3 shape roles, in specimen order, read off a resolved [Shapes]. */
internal fun shapesRoles(shapes: Shapes): List<Pair<String, Shape>> =
  listOf(
    "extraSmall" to shapes.extraSmall,
    "small" to shapes.small,
    "medium" to shapes.medium,
    "large" to shapes.large,
    "extraLarge" to shapes.extraLarge,
  )

/** One swatch row: a bounded colour square, the token label, and its `#AARRGGBB` hex. */
@Composable
private fun CatalogSwatchRow(label: String, color: Color) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(40.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(color)
          .border(1.dp, CATALOG_SWATCH_BORDER, RoundedCornerShape(6.dp))
    )
    Column(modifier = Modifier.padding(start = 12.dp)) {
      BasicText(text = label, style = CATALOG_LABEL_STYLE)
      BasicText(text = catalogHex(color), style = CATALOG_HEX_STYLE)
    }
  }
}

/**
 * Formats [color] as an uppercase `#AARRGGBB` string; alpha included so translucent tokens read as
 * such.
 */
private fun catalogHex(color: Color): String = String.format(Locale.ROOT, "#%08X", color.toArgb())

/**
 * One type specimen row: the token name as a small caption, then a sample line set in the reflected
 * [style]. The sample's colour is forced to the sheet's dark neutral (via `copy`) so a
 * design-system style whose own colour is light — or unspecified — still reads on the white sheet;
 * size / weight / family are what the specimen is there to show, not colour (that's the swatch
 * sheet's job).
 */
@Composable
private fun CatalogTypeRow(label: String, style: TextStyle) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    BasicText(text = label, style = CATALOG_HEX_STYLE)
    BasicText(text = CATALOG_TYPE_SAMPLE, style = style.copy(color = CATALOG_LABEL_STYLE.color))
  }
}

/**
 * One shape specimen row: the token name as a small caption, then a bounded box clipped to the
 * reflected [shape] (filled with the sheet's neutral swatch tint and outlined so the corner geometry
 * reads). The shape counterpart to [CatalogSwatchRow] / [CatalogTypeRow].
 */
@Composable
private fun CatalogShapeRow(label: String, shape: Shape) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(40.dp)
          .clip(shape)
          .background(CATALOG_SHAPE_FILL)
          .border(1.dp, CATALOG_SWATCH_BORDER, shape)
    )
    Box(modifier = Modifier.padding(start = 12.dp)) { BasicText(text = label, style = CATALOG_LABEL_STYLE) }
  }
}

/** Sample text for type specimens — the canonical pangram, so ascenders/descenders/kerning show. */
private const val CATALOG_TYPE_SAMPLE = "The quick brown fox"

private val CATALOG_SHEET_BACKGROUND: Color = Color(0xFFFFFFFF)
private val CATALOG_SWATCH_BORDER: Color = Color(0xFF9E9E9E)
private val CATALOG_SHAPE_FILL: Color = Color(0xFFE3E1EC)
private val CATALOG_LABEL_STYLE: TextStyle = TextStyle(color = Color(0xFF1B1B1F), fontSize = 13.sp)
private val CATALOG_HEX_STYLE: TextStyle =
  TextStyle(color = Color(0xFF5F5F66), fontSize = 11.sp, fontFamily = FontFamily.Monospace)

/**
 * Reads design-token property *values* off the consumer's loaded classes at render time. A
 * top-level `Color` property compiles to a `static final long` backing field (the value class is
 * erased through `ULong` to `long`); we read the raw bits and rebox them into a `Color` via the
 * synthetic `Color.box-impl(long)` factory (no public constructor takes the packed representation).
 * A property declared inside a Kotlin `object` is an instance field read off the `INSTANCE`
 * singleton. Pinned by `ColorValueReflectionProbeTest`.
 */
internal object CatalogValueReflection {
  fun reflectColor(className: String, member: String): Color {
    val owner = Class.forName(className)
    val field = owner.getDeclaredField(member).apply { isAccessible = true }
    val receiver =
      if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
        null
      } else {
        runCatching { owner.getField("INSTANCE").get(null) }.getOrNull()
      }
    val rawUlongBits = field.getLong(receiver)
    val boxImpl = Color::class.java.getDeclaredMethod("box-impl", Long::class.javaPrimitiveType)
    return boxImpl.invoke(null, rawUlongBits) as Color
  }

  /**
   * Reads a `TextStyle` property value. Unlike `Color`, `TextStyle` is an ordinary class, so its
   * backing field holds the object directly — no value-class unboxing, just a plain reflective get
   * (off the `INSTANCE` singleton for a property declared inside a Kotlin `object`).
   */
  fun reflectTextStyle(className: String, member: String): TextStyle =
    reflectAs(className, member)

  /**
   * Reads any ordinary (non-value-class) design-token property value off the consumer's loaded
   * class and casts it to [T] — the generic sibling of [reflectTextStyle], used for a single `Shape`
   * and for the whole-object `ColorScheme` / `Typography` / `Shapes` scales. These are all plain
   * object references (unlike `Color`, which erases to a `long` and needs [reflectColor]'s reboxing),
   * so a plain reflective get suffices (off the `INSTANCE` singleton for a property declared inside a
   * Kotlin `object`).
   */
  inline fun <reified T> reflectAs(className: String, member: String): T {
    val owner = Class.forName(className)
    val field = owner.getDeclaredField(member).apply { isAccessible = true }
    val receiver =
      if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
        null
      } else {
        runCatching { owner.getField("INSTANCE").get(null) }.getOrNull()
      }
    return field.get(receiver) as T
  }
}
