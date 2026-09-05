package ee.schimke.composeai.renderer

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceAppWidget
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Resolves the Glance entry point that turns a [GlanceAppWidget] into `RemoteViews`, reflectively,
 * against whatever `androidx.glance:glance-appwidget` the *rendered project* brought — not the
 * version this module compiled against.
 *
 * Why reflection at all. `GlanceAppWidgetPreviewRenderer` used to call
 * `widget.composeForPreview(context, widgetCategory, info)` as an ordinary compiled call site,
 * linked against the `glance-appwidget = "1.2.0"` pin in `gradle/libs.versions.toml`. Under the
 * import model the render runs against the project's Glance, and `composeForPreview` **did not
 * exist before 1.2.0** — so every Glance app-widget preview in a project on 1.1.x died with
 * `NoSuchMethodError: androidx.glance.appwidget.AppWidgetComposerKt.composeForPreview(...)`, an
 * error naming our renderer rather than anything the project did (compose-ai-tools#5056).
 *
 * What the API actually looks like, read off the published AARs:
 * ```
 * 1.0.0   compose-1pU2XAk(Context, GlanceId, SizeMode, DpSize, Bundle, Any, ConfigManager, content)
 *         compose-DR8WL-M(GlanceAppWidget, Context, GlanceId, Bundle, DpSize, Any)
 * 1.1.0   compose-DR8WL-M(…)                      runComposition(…): Flow<RemoteViews>
 * 1.1.1   compose-DR8WL-M(…)                      runComposition(…): Flow<RemoteViews>
 * 1.2.0   compose-DR8WL-M(…)  composeForPreview(GlanceAppWidget, Context, int, AppWidgetProviderInfo)
 * 1.3.0-α compose-DR8WL-M(…)  composeForPreview(GlanceAppWidget, Context, int, AppWidgetProviderInfo)
 * ```
 *
 * So the resolution order is: `composeForPreview` when the classpath has it (1.2.0+, the preview
 * path proper — it honours `widgetCategory` and `PreviewSizeMode` and drives
 * `GlanceAppWidget.providePreview`), else the `compose` extension present since 1.0.0, which drives
 * `provideGlance` instead — hence [SyntheticGlanceAppWidget] provides the same content from both.
 *
 * Arguments are matched **by parameter type, not position**, so a future release that adds or drops
 * an argument still resolves as long as the added type is one we know how to fill. A method whose
 * middle parameters contain a type this file cannot supply is not a candidate; when nothing is left
 * we throw [GlanceComposerUnavailableException], whose message names the Glance version floor and
 * reaches the panel as the sidecar's `diagnosis` field (see [RenderErrorSidecar]) — a bounded,
 * actionable failure instead of a `NoSuchMethodError` pointing at line 71 of our renderer.
 */
internal object GlanceComposeForPreview {

  /** The Kotlin file class holding both `composeForPreview` and the `compose` extension. */
  const val COMPOSER_CLASS_NAME: String = "androidx.glance.appwidget.AppWidgetComposerKt"

  private const val GLANCE_APP_WIDGET = "androidx.glance.appwidget.GlanceAppWidget"
  private const val CONTEXT = "android.content.Context"
  private const val CONTINUATION = "kotlin.coroutines.Continuation"
  private const val COMPOSE_FOR_PREVIEW = "composeForPreview"
  private const val COMPOSE = "compose"

  /**
   * One parameter of a resolved composer method, identified by what the renderer puts in it rather
   * than by where it sits. [ABSENT] covers the parameters we deliberately pass `null` for — the
   * `GlanceId`, the options `Bundle`, the widget state — which every overload declares as nullable
   * with a `null` default.
   */
  enum class Argument {
    WIDGET,
    CONTEXT,
    WIDGET_CATEGORY,
    PROVIDER_INFO,
    SIZE,
    ABSENT,
  }

  /**
   * A composer method plus how to fill its parameters, and — when some of them are
   * [Argument.ABSENT] — the `$default` bridge that lets Glance fill those itself.
   */
  class Plan(val method: Method, val arguments: List<Argument>, val defaults: Method?) {

    /**
     * True when this plan calls `composeForPreview` — the 1.2.0+ path, which composes through
     * `GlanceAppWidget.providePreview`. False means the `compose` fallback, which composes through
     * `provideGlance`.
     */
    val composesPreview: Boolean
      get() = baseName(method.name) == COMPOSE_FOR_PREVIEW

    /** Parameters the renderer supplies a real value for — the tie-break in [resolveIn]. */
    internal val suppliedArgumentCount: Int
      get() = arguments.count { it != Argument.ABSENT }

    /**
     * Kotlin's defaults bitmask for the [Argument.ABSENT] parameters: bit *i* set asks the bridge
     * to substitute the default of the *i*-th **value** parameter. The receiver is not one, so the
     * numbering starts at the parameter after [Argument.WIDGET].
     */
    internal val defaultsMask: Int =
      arguments.drop(1).foldIndexed(0) { i, mask, argument ->
        if (argument == Argument.ABSENT) mask or (1 shl i) else mask
      }

    /**
     * Whether [compose] goes through [defaults] rather than [method]. `null` is the wrong value for
     * a parameter whose default is not `null` — Glance 1.1.x defaults `compose(id = …)` to
     * `createFakeAppWidgetId()` and then casts it to `AppWidgetId`, so passing our own `null`
     * straight to the real method died with an NPE inside `runComposition` (compose-ai-tools#5056).
     * The bridge is what makes "leave this parameter to the library" expressible at all.
     */
    internal val usesDefaults: Boolean
      get() = defaults != null && defaultsMask != 0

    /** `composeForPreview(WIDGET, CONTEXT, WIDGET_CATEGORY, PROVIDER_INFO)` — for diagnostics. */
    fun describe(): String = "${method.name}(${arguments.joinToString(", ")})"
  }

  /**
   * Resolve against the Glance on [classLoader] — the render classpath's loader, so the imported
   * project's `glance-appwidget` wins over ours.
   *
   * @throws GlanceComposerUnavailableException when Glance is absent, or present with no signature
   *   this renderer can call.
   */
  fun resolve(classLoader: ClassLoader?): Plan {
    val loader = classLoader ?: GlanceComposeForPreview::class.java.classLoader
    val composerClass =
      try {
        Class.forName(COMPOSER_CLASS_NAME, false, loader)
      } catch (e: ClassNotFoundException) {
        throw GlanceComposerUnavailableException(
          "Glance app-widget previews need androidx.glance:glance-appwidget on the render " +
            "classpath, and $COMPOSER_CLASS_NAME did not load. Add the dependency to the module " +
            "under render, or drop the Glance previews from the selection.",
          e,
        )
      }
    return resolveIn(composerClass)
  }

  /**
   * The seam [resolve] is built on: pick a plan out of an already-loaded composer class. Exposed so
   * `GlanceComposeForPreviewTest` can drive the 1.0.0 / 1.1.x / 1.2.0 / future / unsupported shapes
   * without six Glance versions on one test classpath.
   */
  fun resolveIn(composerClass: Class<*>): Plan {
    val plans = composerClass.methods.mapNotNull { planFor(it, composerClass) }
    val best =
      plans
        .sortedWith(
          compareBy(
            { if (it.composesPreview) 0 else 1 },
            { -it.suppliedArgumentCount },
            { it.method.name },
          )
        )
        .firstOrNull()
    if (best != null) return best

    val seen =
      composerClass.methods
        .filter { Modifier.isStatic(it.modifiers) && !it.name.contains("\$default") }
        .map { it.name }
        .distinct()
        .sorted()
    throw GlanceComposerUnavailableException(
      "No usable Glance app-widget composer on the render classpath. " +
        "$COMPOSER_CLASS_NAME${codeSourceSuffix(composerClass)} declares ${seen.joinToString()}, " +
        "and none of them matches a signature this renderer can call. " +
        "composeForPreview(GlanceAppWidget, Context, Int, AppWidgetProviderInfo) arrived in " +
        "androidx.glance:glance-appwidget 1.2.0 — upgrade the rendered project's Glance to 1.2.0 " +
        "or newer to render its app-widget previews."
    )
  }

  /**
   * Compose [widget] through this plan. [widgetCategory] and [info] land only in the parameters the
   * resolved overload actually declares; [size] is used by the pre-1.2.0 `compose` fallback, which
   * takes the composition size directly instead of deriving it from an `AppWidgetProviderInfo`.
   */
  suspend fun Plan.compose(
    widget: GlanceAppWidget,
    context: Context,
    widgetCategory: Int,
    info: AppWidgetProviderInfo,
    size: DpSize,
  ): RemoteViews {
    val args = arguments.map {
      when (it) {
        Argument.WIDGET -> widget
        Argument.CONTEXT -> context
        Argument.WIDGET_CATEGORY -> widgetCategory
        Argument.PROVIDER_INFO -> info
        Argument.SIZE -> size
        Argument.ABSENT -> null
      }
    }
    val result =
      if (usesDefaults) {
        // The bridge takes (…real args…, Continuation, mask, marker); the marker is always null.
        invokeSuspending(defaults!!, args, listOf(defaultsMask, null))
      } else {
        invokeSuspending(method, args)
      }
    return result as? RemoteViews
      ?: throw GlanceComposerUnavailableException(
        "${describe()} returned ${result?.javaClass?.name ?: "null"} rather than RemoteViews. " +
          "The Glance on the render classpath" +
          "${codeSourceSuffix(method.declaringClass)} is not one this renderer understands."
      )
  }

  /**
   * A candidate must be a static `(GlanceAppWidget, Context, …, Continuation)` — the JVM shape of a
   * suspend extension on `GlanceAppWidget` — whose middle parameters are all types [argumentFor]
   * knows how to fill. Kotlin's `$default` bridges are skipped: they take a bitmask and a marker we
   * would have to synthesise, and the real method is always right beside them.
   */
  private fun planFor(method: Method, composerClass: Class<*>): Plan? {
    if (!Modifier.isStatic(method.modifiers)) return null
    if (method.name.contains("\$default")) return null
    val base = baseName(method.name)
    if (base != COMPOSE_FOR_PREVIEW && base != COMPOSE) return null
    val params = method.parameterTypes
    if (params.size < 3) return null
    if (params[0].name != GLANCE_APP_WIDGET) return null
    if (params[1].name != CONTEXT) return null
    if (params[params.size - 1].name != CONTINUATION) return null
    val middle = mutableListOf<Argument>()
    for (i in 2 until params.size - 1) {
      middle += argumentFor(params[i]) ?: return null
    }
    return Plan(
      method,
      listOf(Argument.WIDGET, Argument.CONTEXT) + middle,
      defaultsFor(method, composerClass),
    )
  }

  /**
   * The `$default` bridge beside [method], or null when the overload has no defaulted parameters at
   * all. Kotlin emits it right next to the real method with two extra trailing parameters — the
   * bitmask and an always-null marker — which is the shape [Plan.compose] fills.
   */
  private fun defaultsFor(method: Method, composerClass: Class<*>): Method? =
    composerClass.methods.firstOrNull {
      Modifier.isStatic(it.modifiers) &&
        it.name == method.name + "\$default" &&
        it.parameterCount == method.parameterCount + 2
    }

  /** What the renderer would pass in a parameter of [type], or null when it has nothing to pass. */
  private fun argumentFor(type: Class<*>): Argument? =
    when (type.name) {
      "int",
      "java.lang.Integer" -> Argument.WIDGET_CATEGORY
      "android.appwidget.AppWidgetProviderInfo" -> Argument.PROVIDER_INFO
      "androidx.compose.ui.unit.DpSize" -> Argument.SIZE
      // Nullable-with-default parameters across every published overload: the GlanceId the widget
      // would be bound to, the options Bundle the host would have supplied, and the widget state.
      // A preview has none of them, and Glance's own preview path passes null for all three.
      "androidx.glance.GlanceId",
      "android.os.Bundle",
      "java.lang.Object" -> Argument.ABSENT
      else -> null
    }

  /**
   * Kotlin mangles the JVM name of a function taking a value class — `DpSize` makes `compose`
   * compile to `compose-DR8WL-M`. Match on the part before the hyphen so the mangling, which is a
   * hash of the signature and therefore free to change between releases, is never load-bearing.
   */
  private fun baseName(name: String): String = name.substringBefore('-')

  /** ` (loaded from …/glance-appwidget-1.1.1.jar)`, best effort — empty when the JVM won't say. */
  private fun codeSourceSuffix(cls: Class<*>): String {
    val location =
      try {
        cls.protectionDomain?.codeSource?.location?.toString()
      } catch (_: SecurityException) {
        null
      }
    return location?.let { " (loaded from $it)" }.orEmpty()
  }

  /**
   * Call a suspend function reflectively. A `suspend fun` compiles to a method taking an extra
   * `Continuation` and returning either its result or the `COROUTINE_SUSPENDED` sentinel — which is
   * exactly the contract [suspendCoroutineUninterceptedOrReturn] implements, so handing it our own
   * continuation and returning whatever `invoke` returned bridges the two without a wrapper
   * coroutine. Reflection's `InvocationTargetException` is unwrapped so a throw from inside the
   * user's `@Composable` reaches the sidecar as itself.
   */
  private suspend fun invokeSuspending(
    method: Method,
    args: List<Any?>,
    trailing: List<Any?> = emptyList(),
  ): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
    runCatching { method.isAccessible = true }
    val all = (args + continuation + trailing).toTypedArray()
    try {
      method.invoke(null, *all)
    } catch (e: InvocationTargetException) {
      throw e.cause ?: e
    }
  }
}

/**
 * The render classpath has no Glance app-widget composer this renderer can call — Glance missing
 * entirely, or a version whose API predates `composeForPreview` and whose `compose` overload does
 * not match either. Carries the upgrade instruction; [RenderErrorSidecar] lifts the message into
 * the sidecar's `diagnosis` field so the panel shows it on the card.
 */
internal class GlanceComposerUnavailableException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
