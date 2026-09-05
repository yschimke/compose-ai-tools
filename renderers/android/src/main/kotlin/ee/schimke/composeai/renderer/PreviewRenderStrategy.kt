package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.asComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

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
    PreviewKind.WEAR_THEME_CATALOG to WearThemeCatalogStrategy,
  )

internal fun strategyFor(kind: PreviewKind): PreviewRenderStrategy =
  STRATEGIES[kind] ?: error("No render strategy registered for PreviewKind.$kind")

/**
 * Renders a synthetic `@ThemeCatalog` / `@WearThemeCatalog` entry outside the parameterized
 * standalone renderer.
 *
 * The preview daemon receives the same discovery manifest, but owns its own render loop. Exposing
 * this narrow bridge lets it reuse the canonical theme strategies instead of treating the synthetic
 * display [themeName] as a consumer composable method.
 */
@Composable
fun ThemeCatalogPreview(
  previewId: String,
  themeName: String,
  wrapperClassName: String,
  wear: Boolean,
  widthDp: Int,
  heightDp: Int,
) {
  val kind = if (wear) PreviewKind.WEAR_THEME_CATALOG else PreviewKind.THEME_CATALOG
  strategyFor(kind)
    .Render(
      preview =
        RenderPreviewEntry(
          id = previewId,
          className = wrapperClassName,
          functionName = themeName,
          params =
            RenderPreviewParams(
              name = themeName,
              wrapperClassName = wrapperClassName,
              kind = kind,
            ),
        ),
      widthDp = widthDp,
      heightDp = heightDp,
      previewArgs = emptyList(),
    )
}

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
        resolveNoArgComposableMethod(clazz, preview.functionName)
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
      // A parameter-knob seed leaves a null at every position it did not name, and
      // `ComposableMethod.invoke` forwards those nulls verbatim — straight into an
      // `IllegalArgumentException` when one lands on a primitive. `invokeWithDefaultMask` drives
      // the defaults-mask overload itself for exactly that shape and returns false for every other,
      // where the ordinary invoke below is already correct.
      if (
        !PreviewParameterSupport.invokeWithDefaultMask(
          composableMethod,
          receiver,
          previewArgs,
          currentComposer,
        )
      ) {
        composableMethod.invoke(currentComposer, receiver, *previewArgs.toTypedArray())
      }
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
 * Resolves the `ComposableMethod` for a preview invoked with no arguments.
 *
 * `getDeclaredComposableMethod(name)` builds the JVM signature it looks for out of the argument
 * types the caller passes, so with none it can only ever match `name(Composer, changed[, default])`
 * — a preview whose own parameter list is empty. That misses the shape Studio renders happily and
 * that samples reach for constantly: a component that IS its own preview, every parameter defaulted
 * (`@Preview @Composable fun AverageTimeInBedCard(modifier: Modifier = Modifier)`). Its compiled
 * signature carries the real parameters ahead of the synthetic tail, the exact-signature lookup
 * misses, and the preview fails with a bare `NoSuchMethodException` naming a function that is
 * plainly there (issue: 11 JetLagged renders).
 *
 * So: try the officially supported lookup first, and only when it misses fall back to scanning
 * `declaredMethods` for the name. `ComposableMethod.invoke` already knows how to call a defaulted
 * composable — it fills every unsupplied parameter with the type's zero value and sets that
 * parameter's bit in the defaults mask, so the callee substitutes its own default expression. The
 * fallback therefore only has to find a *defaulted* overload; one without a defaults mask would
 * have the zero values passed through as real arguments (a null `Modifier`, straight into an NPE),
 * which is worse than reporting the miss.
 */
internal fun resolveNoArgComposableMethod(clazz: Class<*>, functionName: String): ComposableMethod {
  runCatching { clazz.getDeclaredComposableMethod(functionName) }
    .getOrNull()
    ?.let {
      return it
    }
  findDefaultedComposableMethod(clazz, functionName)?.let {
    return it
  }
  // Name the ambiguous case rather than reporting a bare miss: "there are two of these and I can't
  // tell which you meant" sends the reader somewhere very different from "I can't find it".
  val candidates =
    clazz.declaredMethods
      .filter { it.name == functionName }
      .mapNotNull { method ->
        method.asComposableMethod()?.takeIf { method.hasComposableDefaults() }
      }
  throw NoSuchMethodException(
    if (candidates.size > 1) {
      val signatures = candidates.joinToString { m ->
        m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
      }
      "${clazz.name}.$functionName is ambiguous — ${candidates.size} fully-defaulted composable " +
        "overloads of that name are all invocable with no arguments: $signatures. The preview " +
        "manifest records the function name only, so the renderer cannot tell which one carries " +
        "the @Preview. Give the preview a distinct name, or annotate a zero-parameter wrapper."
    } else {
      "${clazz.name}.$functionName"
    }
  )
}

/**
 * The `declaredMethods` scan behind [resolveNoArgComposableMethod]: the named composable overload
 * that can be invoked with no arguments because every real parameter is defaulted.
 *
 * Deliberately refuses to guess between several. A manifest entry carries only the class and the
 * function NAME — discovery records no JVM descriptor — so when a class declares two composable
 * overloads of one name that are both fully defaulted, nothing here can tell which one carried the
 * `@Preview`. Picking by arity would render a real component, plausibly, and the wrong one; a
 * catalog quietly showing the wrong card is worse than one showing an error, so this returns null
 * and lets the caller report the miss.
 *
 * Reading the annotation off the method would settle it, and can't: `@Preview` is declared
 * `AnnotationRetention.BINARY`, so it lands in `RuntimeInvisibleAnnotations` and reflection cannot
 * see it. Settling it properly means threading the descriptor from discovery through the manifest.
 * That is worth doing if this is ever hit in practice — the shape is rare enough to be nearly
 * unusable in Kotlin source (with both overloads fully defaulted, every call site is ambiguous),
 * which is why it stays a diagnosed refusal rather than a wire-format change.
 */
internal fun findDefaultedComposableMethod(
  clazz: Class<*>,
  functionName: String,
): ComposableMethod? =
  clazz.declaredMethods
    .asSequence()
    .filter { it.name == functionName }
    .mapNotNull { method -> method.asComposableMethod()?.takeIf { method.hasComposableDefaults() } }
    .toList()
    .singleOrNull()

/**
 * Whether this compiled composable carries a defaults mask — i.e. the source function declared
 * default arguments.
 *
 * The Compose calling convention appends `(Composer, changed…[, default…])` to the real parameters:
 * one `changed` int per [SLOTS_PER_COMPOSABLE_INT] parameters (counting the dispatch receiver of a
 * non-static method) and, only when the function has defaults, one `default` int per 31 real
 * parameters. Both tails are `int`, so the shape — not the types — is what distinguishes them.
 * Callers must have established that the method IS composable (`asComposableMethod() != null`)
 * before asking; the arithmetic assumes the synthetic tail is well-formed.
 */
internal fun java.lang.reflect.Method.hasComposableDefaults(): Boolean {
  val realParams = parameterTypes.indexOfLast {
    it == androidx.compose.runtime.Composer::class.java
  }
  if (realParams <= 0) return false
  val thisParams = if (java.lang.reflect.Modifier.isStatic(modifiers)) 0 else 1
  val changedParams =
    ceil((realParams + thisParams).toDouble() / SLOTS_PER_COMPOSABLE_INT).toInt().coerceAtLeast(1)
  return parameterTypes.size > realParams + 1 + changedParams
}

/**
 * Real parameters encoded per synthetic `changed` int — `androidx.compose.runtime.internal
 * .SLOTS_PER_INT`, which is `internal` to the runtime and so restated here. It is part of the
 * compiled calling convention (every composable ever compiled encodes it), which is what makes
 * restating it safe: `getDeclaredComposableMethod` depends on the same constant.
 */
internal const val SLOTS_PER_COMPOSABLE_INT = 10

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
  // A defaults mask changes what a null MEANS. Without one it is a value being passed, so it can't
  // land on a primitive; with one it is a **parameter knob** left unseeded — "leave this parameter
  // alone" — which is exactly how a partial seed sets one knob of a preview without disturbing its
  // primitive siblings. See [hasComposableDefaults] and [invokeWithDefaultMask].
  val carriesDefaultMask = method.hasComposableDefaults()
  for ((i, arg) in previewArgs.withIndex()) {
    val expected = method.parameterTypes[i]
    if (arg == null) {
      // A null argument can satisfy any reference parameter; a primitive
      // JVM parameter can't accept null unless the defaults mask can carry
      // the intent instead.
      if (expected.isPrimitive && !carriesDefaultMask) return false
      continue
    }
    val actual = arg.javaClass
    if (expected.isAssignableFrom(actual)) continue
    // Auto-boxing: `int` vs `Integer`, etc. `expected.kotlin.javaObjectType`
    // is the box class for primitives; for reference types it's itself.
    if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
    // An enum knob's seed is still the constant's declared TEXT here: nothing before method
    // resolution holds the enum `Class`, so the conversion cannot have happened yet. Matching it
    // against the parameter's own constants is what lets resolution reach the overload whose
    // types the invoke seam then coerces to. A string naming no constant is not a match.
    if (expected.isEnum && arg is String) {
      if (PreviewKnobBake.matchesEnumConstant(expected, arg)) continue
    }
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
    CatalogSpecimenSheet(
      colours =
        rows.filterIsInstance<CatalogRow.Swatch>().map { row ->
          SpecimenCell(CATALOG_SWATCH_ROW_HEIGHT) {
            CatalogSwatchRow(label = row.label, color = row.color)
          }
        },
      types = rows.filterIsInstance<CatalogRow.Type>().map { it.label to it.style },
      shapes = rows.filterIsInstance<CatalogRow.ShapeSpec>().map { it.label to it.shape },
    )
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
 * The Wear counterpart of [ThemeCatalogStrategy]. Identical mechanism — resolve the provider,
 * compose its `Wrap` around a canned specimen — but the specimen reads
 * `androidx.wear.compose.material3.MaterialTheme`, which is what a Wear provider actually installs.
 * Composing the mobile [ThemeSpecimen] in here instead would read the baseline M3 palette and
 * report the same defaults for every declared theme.
 */
private object WearThemeCatalogStrategy : PreviewRenderStrategy {
  @Composable
  override fun Render(
    preview: RenderPreviewEntry,
    widthDp: Int,
    heightDp: Int,
    previewArgs: List<Any?>,
  ) {
    val wrapperFqn = preview.params.wrapperClassName ?: return
    val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
    val themeName = preview.params.name ?: preview.id
    // The provider was loaded by the app's classloader; reading the Wear theme through that same
    // loader is what makes the read see the CompositionLocal the provider actually provided.
    val loader = resolved.second?.javaClass?.classLoader
    val specimen: @Composable () -> Unit = { WearThemeSpecimen(preview.id, themeName, loader) }
    resolved.first.invoke(currentComposer, resolved.second, specimen)
  }
}

/**
 * The Wear specimen: the Wear Material 3 colour roles as swatches and a few type-scale styles as
 * samples, read reflectively (see [WearMaterialTheme]) from the theme in the current composition.
 *
 * The role list is Wear's, not the phone's: `primaryDim` / `secondaryDim` and the
 * `surfaceContainer*` ramp exist here, while `surfaceVariant` and the light/dark-neutral
 * `surfaceContainerLowest/Highest` pair don't. Roles absent from the consumer's wear-compose
 * version resolve to null and are simply skipped, so an older or newer library degrades to a
 * shorter sheet rather than failing the render. Laid out on the same neutral catalog sheet as the
 * mobile specimen so the fixed dark labels stay legible against a dark-first Wear palette.
 */
@Composable
private fun WearThemeSpecimen(previewId: String, themeName: String, loader: ClassLoader?) {
  val scheme = WearMaterialTheme.colorSchemeOrNull(loader)
  val typography = WearMaterialTheme.typographyOrNull(loader)
  val roles =
    listOf(
        "primary",
        "primaryDim",
        "primaryContainer",
        "onPrimary",
        "onPrimaryContainer",
        "secondary",
        "secondaryDim",
        "secondaryContainer",
        "onSecondary",
        "onSecondaryContainer",
        "tertiary",
        "tertiaryContainer",
        "onTertiary",
        "onTertiaryContainer",
        "background",
        "onBackground",
        "surfaceContainerLow",
        "surfaceContainer",
        "surfaceContainerHigh",
        "onSurface",
        "onSurfaceVariant",
        "outline",
        "outlineVariant",
        "error",
        "errorContainer",
        "onError",
        "onErrorContainer",
      )
      .mapNotNull { role -> WearMaterialTheme.role(scheme, role)?.let { role to it } }
  val types =
    listOf("displaySmall", "titleLarge", "titleMedium", "bodyLarge", "bodyMedium", "labelSmall")
      .mapNotNull { name -> WearMaterialTheme.style(typography, name)?.let { name to it } }
  // Same resolved-token sidecar as the mobile sheet, so design-parity's catalog-export maps a Wear
  // theme onto a Figma variable mode exactly like a phone one. Wear M3 has no `Shapes` analogue on
  // `MaterialTheme`, so this carries colours + type only.
  remember(previewId) {
    CatalogTokenSidecar.writeResolved(
      previewId,
      themeName,
      roles.map { (label, color) -> CatalogTokenSidecar.ResolvedToken.Colour(label, color) } +
        types.map { (label, style) -> CatalogTokenSidecar.ResolvedToken.Type(label, style) },
    )
  }
  // Chips are grouped so each family reads as a block: the base role on its own row, then its
  // variants sharing the next one. Wear's `*Dim` roles only mean anything next to the base they
  // dim, and a container only next to the role it contains.
  val resolved = roles.toMap()
  fun chip(role: String, on: String?): ColourChip? =
    resolved[role]?.let { ColourChip(role, it, on?.let(resolved::get)) }
  val chipRows =
    listOf(
        listOfNotNull(chip("primary", "onPrimary")),
        listOfNotNull(
          chip("primaryDim", "onPrimary"),
          chip("primaryContainer", "onPrimaryContainer"),
        ),
        listOfNotNull(chip("secondary", "onSecondary")),
        listOfNotNull(
          chip("secondaryDim", "onSecondary"),
          chip("secondaryContainer", "onSecondaryContainer"),
        ),
        listOfNotNull(
          chip("tertiary", "onTertiary"),
          chip("tertiaryContainer", "onTertiaryContainer"),
        ),
        listOfNotNull(chip("error", "onError"), chip("errorContainer", "onErrorContainer")),
        listOfNotNull(chip("background", "onBackground")),
        listOfNotNull(
          chip("surfaceContainerLow", "onSurface"),
          chip("surfaceContainer", "onSurface"),
        ),
        listOfNotNull(chip("surfaceContainerHigh", "onSurface"), chip("onSurfaceVariant", null)),
        listOfNotNull(chip("outline", null), chip("outlineVariant", null)),
      )
      .filter { it.isNotEmpty() }
  CatalogSpecimenSheet(
    colours = chipRows.map { chipRowCell(it) },
    types = types,
    shapes = emptyList(),
  )
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
      "onPrimaryContainer" to scheme.onPrimaryContainer,
      "secondary" to scheme.secondary,
      "onSecondary" to scheme.onSecondary,
      "secondaryContainer" to scheme.secondaryContainer,
      "onSecondaryContainer" to scheme.onSecondaryContainer,
      "tertiary" to scheme.tertiary,
      "onTertiary" to scheme.onTertiary,
      "tertiaryContainer" to scheme.tertiaryContainer,
      "onTertiaryContainer" to scheme.onTertiaryContainer,
      "error" to scheme.error,
      "onError" to scheme.onError,
      "errorContainer" to scheme.errorContainer,
      "onErrorContainer" to scheme.onErrorContainer,
      "surface" to scheme.surface,
      "onSurface" to scheme.onSurface,
      "surfaceVariant" to scheme.surfaceVariant,
      "onSurfaceVariant" to scheme.onSurfaceVariant,
      "outline" to scheme.outline,
      "outlineVariant" to scheme.outlineVariant,
    )
  // Same family grouping as the Wear sheet: base role on its own row, its container beside it.
  val byName = roles.toMap()
  fun chip(role: String, on: String?) =
    byName[role]?.let { ColourChip(role, it, on?.let(byName::get)) }
  val chipRows =
    listOf(
        listOfNotNull(chip("primary", "onPrimary"), chip("primaryContainer", "onPrimaryContainer")),
        listOfNotNull(
          chip("secondary", "onSecondary"),
          chip("secondaryContainer", "onSecondaryContainer"),
        ),
        listOfNotNull(
          chip("tertiary", "onTertiary"),
          chip("tertiaryContainer", "onTertiaryContainer"),
        ),
        listOfNotNull(chip("error", "onError"), chip("errorContainer", "onErrorContainer")),
        listOfNotNull(chip("surface", "onSurface"), chip("surfaceVariant", "onSurfaceVariant")),
        listOfNotNull(chip("outline", null), chip("outlineVariant", null)),
      )
      .filter { it.isNotEmpty() }
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
        shapeRoles.map { (label, shape) ->
          CatalogTokenSidecar.ResolvedToken.ShapeToken(label, shape)
        },
    )
  }
  CatalogSpecimenSheet(
    colours = chipRows.map { chipRowCell(it) },
    types = types,
    shapes = shapeRoles,
  )
}

/** A resolved catalog row — a colour swatch, a type specimen, or a shape — ready to lay out. */
private sealed interface CatalogRow {
  data class Swatch(val label: String, val color: Color) : CatalogRow

  data class Type(val label: String, val style: TextStyle) : CatalogRow

  data class ShapeSpec(val label: String, val shape: Shape) : CatalogRow
}

/**
 * Resolves one [CatalogToken] to the row(s) it contributes. A single-token kind (`COLOR` /
 * `TEXT_STYLE` / `SHAPE`) yields one row; a whole-object kind (`COLOR_SCHEME` / `TYPOGRAPHY` /
 * `SHAPES`) reflects the object off the consumer class and expands it into the Material 3 role rows
 * for that scale (each row labelled `<token> · <role>`), so a declared whole `ColorScheme` /
 * `Typography` / `Shapes` — the "entire object" catalog the theme-override surface offers — renders
 * as a full specimen sheet.
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
      val scheme = CatalogValueReflection.reflectAs<Any>(token.className, token.member)
      catalogColorSchemeRoles(scheme).map { (role, color) ->
        CatalogRow.Swatch("${token.label} · $role", color)
      }
    }
    CatalogTokenKind.TYPOGRAPHY -> {
      val typography = CatalogValueReflection.reflectAs<Any>(token.className, token.member)
      catalogTypographyRoles(typography).map { (role, style) ->
        CatalogRow.Type("${token.label} · $role", style)
      }
    }
    CatalogTokenKind.SHAPES -> {
      val shapes = CatalogValueReflection.reflectAs<Any>(token.className, token.member)
      catalogShapesRoles(shapes).map { (role, shape) ->
        CatalogRow.ShapeSpec("${token.label} · $role", shape)
      }
    }
  }

internal fun catalogColorSchemeRoles(scheme: Any): List<Pair<String, Color>> =
  when (scheme) {
    is ColorScheme -> colorSchemeRoles(scheme)
    else -> RemoteCatalogValues.colorSchemeRoles(scheme)
  }

internal fun catalogTypographyRoles(typography: Any): List<Pair<String, TextStyle>> =
  when (typography) {
    is Typography -> typographyRoles(typography)
    else -> RemoteCatalogValues.typographyRoles(typography)
  }

internal fun catalogShapesRoles(shapes: Any): List<Pair<String, Shape>> =
  when (shapes) {
    is Shapes -> shapesRoles(shapes)
    else -> RemoteCatalogValues.shapesRoles(shapes)
  }

/**
 * Converts Wear Remote Material 3 theme objects into ordinary Compose values for the synthetic
 * catalog raster and its structured sidecar. Kept reflection-only so the renderer does not add a
 * production dependency on the fast-moving Remote Compose alpha; the consumer supplies the concrete
 * classes, and absent or dynamic roles simply shorten the sheet.
 *
 * The result deliberately stays a renderer-drawn catalog raster, not a recorded `RemoteDocument`: a
 * token sheet is inventory rather than a component replay, and its complete resolved values also
 * travel in [CatalogTokenSidecar]. Live named-value editing continues to apply to actual Remote
 * previews instead of giving this synthetic sheet a second output contract.
 */
internal object RemoteCatalogValues {
  private const val REMOTE_COLOR_SCHEME = "androidx.wear.compose.remote.material3.RemoteColorScheme"
  private const val REMOTE_TYPOGRAPHY = "androidx.wear.compose.remote.material3.RemoteTypography"
  private const val REMOTE_SHAPES = "androidx.wear.compose.remote.material3.RemoteShapes"

  private val colorRoleNames =
    listOf(
      "primary",
      "primaryDim",
      "primaryContainer",
      "onPrimary",
      "onPrimaryContainer",
      "secondary",
      "secondaryDim",
      "secondaryContainer",
      "onSecondary",
      "onSecondaryContainer",
      "tertiary",
      "tertiaryDim",
      "tertiaryContainer",
      "onTertiary",
      "onTertiaryContainer",
      "surfaceContainerLow",
      "surfaceContainer",
      "surfaceContainerHigh",
      "onSurface",
      "onSurfaceVariant",
      "outline",
      "outlineVariant",
      "background",
      "onBackground",
      "error",
      "errorDim",
      "errorContainer",
      "onError",
      "onErrorContainer",
    )

  private val typographyRoleNames =
    listOf(
      "displayLarge",
      "displayMedium",
      "displaySmall",
      "titleLarge",
      "titleMedium",
      "titleSmall",
      "labelLarge",
      "labelMedium",
      "labelSmall",
      "bodyLarge",
      "bodyMedium",
      "bodySmall",
      "bodyExtraSmall",
      "numeralExtraLarge",
      "numeralLarge",
      "numeralMedium",
      "numeralSmall",
      "numeralExtraSmall",
    )

  private val shapeRoleNames = listOf("extraSmall", "small", "medium", "large", "extraLarge")

  fun colorSchemeRoles(scheme: Any): List<Pair<String, Color>> {
    requireRemoteType(scheme, REMOTE_COLOR_SCHEME)
    return colorRoleNames.mapNotNull { role ->
      remoteColorOrNull(propertyOrNull(scheme, role))?.let { role to it }
    }
  }

  fun typographyRoles(typography: Any): List<Pair<String, TextStyle>> {
    requireRemoteType(typography, REMOTE_TYPOGRAPHY)
    return typographyRoleNames.mapNotNull { role ->
      propertyOrNull(typography, role)?.let(::textStyleOrNull)?.let { role to it }
    }
  }

  fun shapesRoles(shapes: Any): List<Pair<String, Shape>> {
    requireRemoteType(shapes, REMOTE_SHAPES)
    return shapeRoleNames.mapNotNull { role ->
      propertyOrNull(shapes, role)?.let(::shapeOrNull)?.let { role to it }
    }
  }

  private fun textStyleOrNull(remote: Any): TextStyle? = runCatching {
    TextStyle(
      color = remoteColorOrNull(propertyOrNull(remote, "color")) ?: Color.Unspecified,
      fontSize = remoteTextUnitOrNull(propertyOrNull(remote, "fontSize")) ?: TextUnit.Unspecified,
      fontWeight = propertyOrNull(remote, "fontWeight") as? FontWeight,
      fontStyle = propertyOrNull(remote, "fontStyle") as? FontStyle,
      fontFamily = remoteFontFamilyOrNull(propertyOrNull(remote, "fontFamily")),
      letterSpacing =
        remoteTextUnitOrNull(propertyOrNull(remote, "letterSpacing")) ?: TextUnit.Unspecified,
      background = remoteColorOrNull(propertyOrNull(remote, "background")) ?: Color.Unspecified,
      lineHeight =
        remoteTextUnitOrNull(propertyOrNull(remote, "lineHeight")) ?: TextUnit.Unspecified,
      textDecoration = propertyOrNull(remote, "textDecoration") as? TextDecoration,
    )
  }
    .getOrNull()

  private fun shapeOrNull(remote: Any): Shape? = runCatching {
    RoundedCornerShape(
      topStart = cornerSize(property(remote, "topStart")),
      topEnd = cornerSize(property(remote, "topEnd")),
      bottomEnd = cornerSize(property(remote, "bottomEnd")),
      bottomStart = cornerSize(property(remote, "bottomStart")),
    )
  }
    .getOrNull()

  private fun cornerSize(remote: Any): CornerSize =
    when {
      remote.javaClass.name.endsWith(".RemotePercentCornerSize") ->
        CornerSize(property(remote, "percent") as Int)
      remote.javaClass.name.endsWith(".RemoteDpCornerSize") -> {
        val dp = constantOrNull(property(remote, "size")) as? Dp
        CornerSize(requireNotNull(dp) { "Remote dp corner is not constant" })
      }
      else -> error("Unsupported Remote corner size ${remote.javaClass.name}")
    }

  private fun remoteColorOrNull(remote: Any?): Color? {
    if (remote == null) return null
    (constantOrNull(remote) as? Color)?.let {
      return it
    }
    // RemoteColorScheme deliberately exposes every role as a named value so a replay can override
    // it. The public constant is therefore null even though createNamedRemoteColor captured a
    // concrete fallback. Its id-provider lambda retains that packed Color long; read the fallback
    // without invoking the provider (which would require constructing a RemoteDocument writer).
    return runCatching {
      val provider = property(remote, "idProvider")
      // `Class.getDeclaredFields()` returns its elements in no specified order — the same
      // unspecified order that made every prefix-colliding role read a sibling's colour (see
      // `isGetterFor`). One `long` capture is what `remote-creation-compose:1.0.0-alpha18`'s
      // lambda holds, so today the set has one element and any pick is the same pick; a future
      // capture of two would otherwise reintroduce a colour that changes between runs of identical
      // bytecode. Sorting makes the answer a property of the class rather than of the run, which is
      // what the method lookup above already does for the same reason.
      val bitsField =
        requireNotNull(
          provider.javaClass.declaredFields
            .filter { it.type == Long::class.javaPrimitiveType }
            .minByOrNull { it.name }
        )
      bitsField.isAccessible = true
      Color(bitsField.getLong(provider).toULong())
    }
      .getOrNull()
  }

  private fun remoteTextUnitOrNull(remote: Any?): TextUnit? =
    remote?.let(::constantOrNull) as? TextUnit

  private fun remoteFontFamilyOrNull(remote: Any?): FontFamily? {
    val name = remote?.let { propertyOrNull(it, "name") as? String }?.lowercase() ?: return null
    return when {
      "mono" in name -> FontFamily.Monospace
      "serif" in name -> FontFamily.Serif
      "cursive" in name -> FontFamily.Cursive
      "sans" in name -> FontFamily.SansSerif
      else -> FontFamily.Default
    }
  }

  private fun constantOrNull(remote: Any): Any? =
    remote.javaClass.methods
      .firstOrNull { it.name == "getConstantValueOrNull" && it.parameterCount == 0 }
      ?.invoke(remote)

  private fun property(receiver: Any, name: String): Any =
    requireNotNull(propertyOrNull(receiver, name)) { "Missing ${receiver.javaClass.name}.$name" }

  private fun propertyOrNull(receiver: Any, name: String): Any? {
    val getter = "get" + name.replaceFirstChar { it.uppercase() }
    return receiver.javaClass.methods
      .filter { it.parameterCount == 0 && isGetterFor(it.name, getter) }
      // `Class.getMethods()` returns its elements in no specified order, so a receiver that somehow
      // offers two accepted spellings must not resolve by whichever the JVM listed first. Sorting
      // makes the pick a property of the class rather than of the run.
      .minByOrNull { it.name }
      ?.invoke(receiver)
  }

  /**
   * Whether [methodName] is the accessor for [getter] itself — and NOT for a longer role that
   * merely begins with it.
   *
   * This was a `startsWith` test, and `getPrimary` is a prefix of both `getPrimaryDim` and
   * `getPrimaryContainer`. Eleven of `RemoteColorScheme`'s twenty-nine roles collide that way
   * (`primary`, `onPrimary`, `secondary`, `onSecondary`, `tertiary`, `onTertiary`,
   * `surfaceContainer`, `onSurface`, `outline`, `error`, `onError`), so each of them resolved to
   * whichever sibling [Class.getMethods] happened to list first — an order the JVM explicitly does
   * not specify. The `Remote theme colours` catalog raster therefore changed colours between runs
   * with no code change at all: `main` committed four different PNGs for it as its own baseline
   * across sixteen consecutive updates, `primary` reading `primaryDim`'s value in some of them.
   *
   * A decorated name still has to be tolerated, which is why the loose test was there, and it
   * carries **two** decorations rather than one:
   * * `-<hash>` for a getter whose return type is an inline class, so `fontStyle` is really
   *   `getFontStyle-4Lr2A7w` on `RemoteTextStyle`; and
   * * `$<module>` for an `internal` one, so `idProvider` — the fallback every catalog colour
   *   actually resolves through, since a named `RemoteColor` has no public constant — is really
   *   `getIdProvider$remote_creation_compose` on `RemoteColor`.
   *
   * Neither `-` nor `$` can occur in a Kotlin property name, so the character straight after the
   * getter is exactly what separates a decorated accessor from a longer role.
   */
  private fun isGetterFor(methodName: String, getter: String): Boolean =
    methodName == getter ||
      (methodName.length > getter.length &&
        methodName.startsWith(getter) &&
        methodName[getter.length].let { it == '-' || it == '$' })

  private fun requireRemoteType(value: Any, expected: String) {
    require(value.javaClass.name == expected) {
      "Expected $expected catalog value, got ${value.javaClass.name}"
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
 * reflected [shape] (filled with the sheet's neutral swatch tint and outlined so the corner
 * geometry reads). The shape counterpart to [CatalogSwatchRow] / [CatalogTypeRow].
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
    Box(modifier = Modifier.padding(start = 12.dp)) {
      BasicText(text = label, style = CATALOG_LABEL_STYLE)
    }
  }
}

/** Sample text for type specimens — the canonical pangram, so ascenders/descenders/kerning show. */
private const val CATALOG_TYPE_SAMPLE = "The quick brown fox"

/**
 * One row of a specimen sheet, carrying the height [CatalogSpecimenSheet] packs it by.
 *
 * The height is an *estimate*, not a measurement — the sheet has to decide column breaks before it
 * can measure, and a row's real height only settles once its text has laid out. Estimating high is
 * the safe direction: it starts a new column early and leaves slack at the bottom, where being
 * wrong the other way would clip, which is the bug this whole type exists to remove.
 */
internal class SpecimenCell(
  val height: Dp,
  /** True for a section heading, which must never be the last row in a column. */
  val keepWithNext: Boolean = false,
  val content: @Composable () -> Unit,
)

/**
 * Lays specimen rows out in **as many columns as the canvas needs**, left to right.
 *
 * A single `Column` is what these sheets used to be, and it silently truncated: the canvas is
 * fixed, so every row past the bottom edge was simply not drawn. That cost the Wear sheet all six
 * of its type rows (21 colour swatches alone overflow a 768dp content box) and the mobile sheet
 * four of its five shape rows — in both cases the *last* section, which is the one a reader is
 * least likely to notice is missing. Neither sheet reported anything; the PNG just ended.
 *
 * Packing is greedy and order-preserving, so a sheet still reads colours → type → shapes, top to
 * bottom then left to right. Columns share the width evenly via `weight`, which keeps the swatch
 * labels and hex codes on one line at the canvas sizes discovery assigns these sheets.
 */
@Composable
private fun CatalogSpecimenSheet(
  colours: List<SpecimenCell>,
  types: List<Pair<String, TextStyle>>,
  shapes: List<Pair<String, Shape>>,
) {
  BoxWithConstraints(
    Modifier.fillMaxSize().background(CATALOG_SHEET_BACKGROUND).padding(CATALOG_SHEET_PADDING)
  ) {
    // The block layout is the *designed* sheet, but it is only reachable when the content fits the
    // canvas — a theme is free to declare type roles tall enough that colour + type + shape exceeds
    // it, and a fixed-height `Column` answers that by clipping, which is the bug this file exists
    // to
    // remove. So the fit is checked against the same estimates the packer uses, and the fallback is
    // the generic multi-column packing, which cannot clip because it adds columns instead.
    if (blockLayoutFits(colours, types, shapes, maxHeight.value)) {
      BlockSpecimenLayout(colours, types, shapes)
    } else {
      PackedSpecimenLayout(specimenCells(colours, types, shapes), maxHeight.value)
    }
  }
}

/** Every row of the sheet as a packable cell, in reading order — the fallback layout's input. */
private fun specimenCells(
  colours: List<SpecimenCell>,
  types: List<Pair<String, TextStyle>>,
  shapes: List<Pair<String, Shape>>,
): List<SpecimenCell> = buildList {
  if (colours.isNotEmpty()) {
    add(sectionCell("Colour"))
    addAll(colours)
  }
  if (types.isNotEmpty()) {
    add(sectionCell("Type"))
    types.forEach { (label, style) -> add(typeCell(label, style)) }
  }
  if (shapes.isNotEmpty()) {
    add(sectionCell("Shape"))
    shapes.forEach { (label, shape) -> add(shapeCell(label, shape)) }
  }
}

/**
 * Whether the designed block layout fits [available] dp of content height.
 *
 * The colour block costs its *tallest* column (they run side by side); type costs the sum of its
 * rows, because they stack at full width; shapes cost one row whatever their count, because they
 * run across. Section headings are counted per block that exists.
 */
private fun blockLayoutFits(
  colours: List<SpecimenCell>,
  types: List<Pair<String, TextStyle>>,
  shapes: List<Pair<String, Shape>>,
  available: Float,
): Boolean {
  val colourHeight =
    balanceColumns(colours, CATALOG_COLOUR_COLUMNS).maxOfOrNull { column ->
      column.fold(0f) { sum, cell -> sum + cell.height.value }
    } ?: 0f
  val typeHeight = types.fold(0f) { sum, (_, style) -> sum + catalogTypeRowHeight(style).value }
  val shapeHeight = if (shapes.isEmpty()) 0f else CATALOG_SWATCH_ROW_HEIGHT.value
  val headings =
    listOf(colours.isNotEmpty(), types.isNotEmpty(), shapes.isNotEmpty()).count { it } *
      CATALOG_SECTION_HEIGHT.value
  return colourHeight + typeHeight + shapeHeight + headings <= available
}

/** The designed sheet: colour in two columns, type at full width, shapes across one row. */
@Composable
private fun BlockSpecimenLayout(
  colours: List<SpecimenCell>,
  types: List<Pair<String, TextStyle>>,
  shapes: List<Pair<String, Shape>>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(CATALOG_ROW_GAP)) {
    if (colours.isNotEmpty()) {
      CatalogSectionHeader("Colour")
      Row(horizontalArrangement = Arrangement.spacedBy(CATALOG_COLUMN_GAP)) {
        for (column in balanceColumns(colours, CATALOG_COLOUR_COLUMNS)) {
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CATALOG_ROW_GAP),
          ) {
            for (cell in column) cell.content()
          }
        }
      }
    }
    if (types.isNotEmpty()) {
      CatalogSectionHeader("Type")
      // Full sheet width, deliberately: a type specimen is judged on a real line of text, and a
      // half-width column wraps the pangram mid-phrase.
      for ((label, style) in types) CatalogTypeRow(label = label, style = style)
    }
    if (shapes.isNotEmpty()) {
      CatalogSectionHeader("Shape")
      // One row across the sheet — the corner scale reads as a progression, which a vertical list
      // of five separate rows hides.
      Row(horizontalArrangement = Arrangement.spacedBy(CATALOG_COLUMN_GAP)) {
        for ((label, shape) in shapes) {
          Column(modifier = Modifier.weight(1f)) { CatalogShapeChip(label = label, shape = shape) }
        }
      }
    }
  }
}

/**
 * Fallback for content the block layout can't hold: every row packed into as many columns as fit.
 */
@Composable
private fun PackedSpecimenLayout(cells: List<SpecimenCell>, available: Float) {
  Row(horizontalArrangement = Arrangement.spacedBy(CATALOG_COLUMN_GAP)) {
    for (column in packColumns(cells, available)) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(CATALOG_ROW_GAP),
      ) {
        for (cell in column) cell.content()
      }
    }
  }
}

/**
 * Packs [cells] into the fewest balanced columns that keep every column within [available].
 *
 * Balanced rather than filled — packing to capacity leaves whatever spills over alone in the last
 * column — but capacity still wins: a column may exceed the balanced share rather than let a row
 * fall off the canvas. A heading reserves room for the row it introduces, so a column never ends on
 * a heading whose section starts in the next one.
 */
internal fun packColumns(cells: List<SpecimenCell>, available: Float): List<List<SpecimenCell>> {
  if (cells.isEmpty()) return emptyList()
  val total = cells.fold(0f) { sum, cell -> sum + cell.height.value }
  val count = max(1, ceil(total / available).toInt())
  val target = total / count
  val columns = mutableListOf<List<SpecimenCell>>()
  var current = mutableListOf<SpecimenCell>()
  var used = 0f
  for ((index, cell) in cells.withIndex()) {
    val needed =
      cell.height.value +
        if (cell.keepWithNext) cells.getOrNull(index + 1)?.height?.value ?: 0f else 0f
    val overCanvas = used + needed > available
    val overShare = used + cell.height.value > target && columns.size < count - 1
    if (current.isNotEmpty() && (overCanvas || overShare)) {
      columns += current
      current = mutableListOf()
      used = 0f
    }
    current += cell
    used += cell.height.value
  }
  if (current.isNotEmpty()) columns += current
  return columns
}

/**
 * Splits [cells] into [preferred] balanced columns, in order, by estimated height.
 *
 * Balanced rather than filled: packing each column to capacity leaves whatever spills over alone in
 * the last one, and one orphan row beside a full column reads as a mistake rather than a layout.
 */
private fun balanceColumns(cells: List<SpecimenCell>, preferred: Int): List<List<SpecimenCell>> {
  val count = max(1, preferred)
  if (count == 1 || cells.size <= 1) return listOf(cells)
  val total = cells.fold(0f) { sum, cell -> sum + cell.height.value }
  val target = total / count
  val columns = mutableListOf<List<SpecimenCell>>()
  var current = mutableListOf<SpecimenCell>()
  var used = 0f
  for (cell in cells) {
    if (current.isNotEmpty() && used + cell.height.value > target && columns.size < count - 1) {
      columns += current
      current = mutableListOf()
      used = 0f
    }
    current += cell
    used += cell.height.value
  }
  if (current.isNotEmpty()) columns += current
  return columns
}

/** A shape token drawn as a chip: the clipped box with its name beside it. */
@Composable
private fun CatalogShapeChip(label: String, shape: Shape) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier =
        Modifier.size(40.dp)
          .clip(shape)
          .background(CATALOG_SHAPE_FILL)
          .border(1.dp, CATALOG_SWATCH_BORDER, shape)
    )
    Box(modifier = Modifier.padding(start = 8.dp)) {
      BasicText(text = label, style = CATALOG_LABEL_STYLE)
    }
  }
}

/** A section heading, so a multi-column sheet still says which scale each block belongs to. */
@Composable
private fun CatalogSectionHeader(label: String) {
  BasicText(text = label.uppercase(Locale.ROOT), style = CATALOG_SECTION_STYLE)
}

/**
 * A colour role drawn as a chip that **sets its own name in the role it pairs with** — `primary`
 * lettered in `onPrimary`, `surfaceContainer` in `onSurface`.
 *
 * This is the difference between a sheet that lists a palette and one that demonstrates it. A pair
 * of hex codes on separate rows asserts that `onPrimary` goes on `primary`; a chip that draws the
 * name in it *shows* whether the pair is actually legible, which is the question a reviewer has
 * about a theme. A role with no counterpart (`outline`) falls back to black or white by luminance,
 * so it still reads rather than being a blank tile.
 */
private class ColourChip(val label: String, val base: Color, val on: Color?)

/** Cell for a row of colour chips, sharing the column width evenly. */
private fun chipRowCell(chips: List<ColourChip>) =
  SpecimenCell(CATALOG_CHIP_ROW_HEIGHT) { CatalogChipRow(chips) }

/**
 * Black or white, whichever contrasts with [base] — the fallback for a role that pairs with
 * nothing. Uses relative luminance rather than a naive average so mid-tone yellows and cyans land
 * correctly.
 */
private fun readableOn(base: Color): Color =
  if (base.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White

/** One row of colour chips. Each chip fills its share of the row and letters its own name. */
@Composable
private fun CatalogChipRow(chips: List<ColourChip>) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (chip in chips) {
      val ink = chip.on ?: readableOn(chip.base)
      Column(
        modifier =
          Modifier.weight(1f)
            .height(CATALOG_CHIP_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(chip.base)
            .border(1.dp, CATALOG_SWATCH_BORDER, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
      ) {
        BasicText(text = chip.label, style = CATALOG_CHIP_LABEL_STYLE.copy(color = ink))
        BasicText(
          text = catalogHex(chip.base),
          style = CATALOG_CHIP_HEX_STYLE.copy(color = ink.copy(alpha = 0.75f)),
        )
      }
    }
  }
}

/** Cell for a shape token — same fixed geometry as a swatch row. */
private fun shapeCell(label: String, shape: Shape) =
  SpecimenCell(CATALOG_SWATCH_ROW_HEIGHT) { CatalogShapeRow(label = label, shape = shape) }

/**
 * Cell for a type role. Height scales with the style's own size, because a `displaySmall` sample is
 * several times the height of a `labelSmall` one and packing by a single average would either waste
 * a column or overflow it.
 */
private fun typeCell(label: String, style: TextStyle) =
  SpecimenCell(catalogTypeRowHeight(style)) { CatalogTypeRow(label = label, style = style) }

private fun sectionCell(label: String) =
  SpecimenCell(CATALOG_SECTION_HEIGHT, keepWithNext = true) { CatalogSectionHeader(label) }

/**
 * Estimated height of a [CatalogTypeRow]: the caption, the sample, and the row's padding plus the
 * sheet's row gap.
 *
 * The line box comes from the style's **declared `lineHeight`** where it has one, because
 * [CatalogTypeRow] draws the style unchanged and a design system is free to declare a line height
 * far larger than its font size — `bodyLarge` at 16sp with `lineHeight = 100.sp` is a legal theme,
 * and budgeting `1.5 x fontSize` for it would under-count by 76dp and clip the row, which is the
 * failure this layout exists to remove. `sp` is taken as-is, `em` is multiplied by the font size,
 * and an unspecified line height falls back to `1.5x`. That `1.5x` is also a *floor*, so a style
 * declaring an unusually tight line height still gets descender slack.
 *
 * A display-sized sample **wraps** in a column — the pangram at 36sp does not fit ~300dp — so
 * anything above [CATALOG_TYPE_WRAP_SP] is budgeted at two lines. Guessing high is deliberate
 * throughout: the cost is slack at the bottom of a column, where guessing low is a clipped row.
 */
internal fun catalogTypeRowHeight(style: TextStyle): Dp {
  val fontSize = style.fontSize
  val size = if (fontSize.isSpecified && fontSize.value > 0f) fontSize.value else 16f
  val declared = style.lineHeight
  val lineBox =
    when {
      !declared.isSpecified || declared.value <= 0f -> size * CATALOG_TYPE_LINE_FACTOR
      declared.isSp -> declared.value
      declared.isEm -> size * declared.value
      else -> size * CATALOG_TYPE_LINE_FACTOR
    }
  val line = maxOf(lineBox, size * CATALOG_TYPE_LINE_FACTOR)
  val lines = if (size > CATALOG_TYPE_WRAP_SP) 2 else 1
  return (CATALOG_TYPE_CAPTION_DP + line * lines + CATALOG_ROW_PADDING_DP + CATALOG_ROW_GAP_DP).dp
}

/** Sheet padding, column gap and row gap — shared by the layout and by the height estimates. */
private val CATALOG_SHEET_PADDING: Dp = 16.dp
private val CATALOG_COLUMN_GAP: Dp = 20.dp
private const val CATALOG_ROW_GAP_DP: Float = 4f
private val CATALOG_ROW_GAP: Dp = CATALOG_ROW_GAP_DP.dp
/**
 * `CatalogSwatchRow` / `CatalogShapeRow`: a 40dp box, 2dp padding either side, plus the row gap.
 */
private val CATALOG_SWATCH_ROW_HEIGHT: Dp = (40f + 4f + CATALOG_ROW_GAP_DP).dp
private const val CATALOG_ROW_PADDING_DP: Float = 4f
private const val CATALOG_TYPE_CAPTION_DP: Float = 16f
/** Above this sample size the pangram wraps to a second line in a sheet column. */
private const val CATALOG_TYPE_WRAP_SP: Float = 24f
/**
 * Fallback (and floor) line box as a multiple of font size, covering default leading + descenders.
 */
private const val CATALOG_TYPE_LINE_FACTOR: Float = 1.5f
private val CATALOG_SECTION_HEIGHT: Dp = 24.dp

/** A colour chip: two lines of text with 6dp padding above and below. */
/** Colour roles run in two columns; type and shape get the full sheet width below them. */
private const val CATALOG_COLOUR_COLUMNS: Int = 2
private val CATALOG_CHIP_HEIGHT: Dp = 44.dp
private val CATALOG_CHIP_ROW_HEIGHT: Dp = CATALOG_CHIP_HEIGHT + 4.dp + CATALOG_ROW_GAP

private val CATALOG_SHEET_BACKGROUND: Color = Color(0xFFFFFFFF)
private val CATALOG_SWATCH_BORDER: Color = Color(0xFF9E9E9E)
private val CATALOG_SHAPE_FILL: Color = Color(0xFFE3E1EC)
private val CATALOG_LABEL_STYLE: TextStyle = TextStyle(color = Color(0xFF1B1B1F), fontSize = 13.sp)
private val CATALOG_HEX_STYLE: TextStyle =
  TextStyle(color = Color(0xFF5F5F66), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
private val CATALOG_CHIP_LABEL_STYLE: TextStyle =
  TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
private val CATALOG_CHIP_HEX_STYLE: TextStyle =
  TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
private val CATALOG_SECTION_STYLE: TextStyle =
  TextStyle(color = Color(0xFF5F5F66), fontSize = 11.sp, letterSpacing = 1.sp)

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
  fun reflectTextStyle(className: String, member: String): TextStyle = reflectAs(className, member)

  /**
   * Reads any ordinary (non-value-class) design-token property value off the consumer's loaded
   * class and casts it to [T] — the generic sibling of [reflectTextStyle], used for a single
   * `Shape` and for the whole-object `ColorScheme` / `Typography` / `Shapes` scales. These are all
   * plain object references (unlike `Color`, which erases to a `long` and needs [reflectColor]'s
   * reboxing), so a plain reflective get suffices (off the `INSTANCE` singleton for a property
   * declared inside a Kotlin `object`).
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
