package ee.schimke.composeai.renderer

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget

/**
 * Compiled stand-ins for `androidx.glance.appwidget.AppWidgetComposerKt` as it is shaped in each
 * Glance release [GlanceComposeForPreview] has to cope with. Reflection over *compiled* signatures
 * is the point — a hand-written `Method` stub could not reproduce Kotlin's suspend calling
 * convention or the value-class name mangling that turns `compose` into `compose-DR8WL-M` — and
 * putting six Glance versions on one test classpath is not possible at all.
 *
 * The shapes below are transcribed from `javap` over the published AARs:
 * ```
 * 1.0.0/1.1.x  compose-DR8WL-M(GlanceAppWidget, Context, GlanceId, Bundle, DpSize, Object)
 * 1.2.0+       …the same, plus
 *              composeForPreview(GlanceAppWidget, Context, int, AppWidgetProviderInfo)
 * ```
 *
 * Each fixture records what it was handed so the invocation tests can assert the renderer filled
 * the parameters by *type*, not by position.
 */
object GlanceComposerCalls {
  /** `"composeForPreview(widgetCategory=1, info=…)"`-ish trace of the last call, per fixture. */
  val recorded: MutableList<String> = mutableListOf()

  fun reset() = recorded.clear()
}

/** Glance 1.2.0 and 1.3.0-alpha: `composeForPreview` beside the older `compose` extension. */
object Glance120ComposerFixture {

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.composeForPreview(
    context: Context,
    widgetCategory: Int,
    info: AppWidgetProviderInfo? = null,
  ): RemoteViews {
    GlanceComposerCalls.recorded +=
      "composeForPreview(widget=${javaClass.simpleName}, widgetCategory=$widgetCategory, " +
        "info=${info?.minWidth}x${info?.minHeight})"
    return RemoteViews(context.packageName, 0)
  }

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.compose(
    context: Context,
    id: GlanceId? = null,
    options: Bundle? = null,
    size: DpSize? = null,
    state: Any? = null,
  ): RemoteViews {
    GlanceComposerCalls.recorded += "compose(id=$id, options=$options, size=$size, state=$state)"
    return RemoteViews(context.packageName, 0)
  }
}

/**
 * Glance 1.0.0 through 1.1.1: no `composeForPreview` at all. `AppWidgetComposerKt` still resolves,
 * which is why the old compiled call site failed with `NoSuchMethodError` rather than
 * `ClassNotFoundException` (compose-ai-tools#5056).
 */
object Glance11xComposerFixture {

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.compose(
    context: Context,
    id: GlanceId? = null,
    options: Bundle? = null,
    size: DpSize? = null,
    state: Any? = null,
  ): RemoteViews {
    GlanceComposerCalls.recorded += "compose(id=$id, options=$options, size=$size, state=$state)"
    return RemoteViews(context.packageName, 0)
  }

  /** 1.1.0's other public entry point. Not suspend, returns a stream — never a candidate. */
  @JvmStatic
  @Suppress("unused")
  fun GlanceAppWidget.runComposition(context: Context, id: GlanceId? = null): List<RemoteViews> {
    GlanceComposerCalls.recorded += "runComposition($context, $id)"
    return emptyList()
  }
}

/** A hypothetical later release that grows a parameter whose type we already know how to fill. */
object FutureComposerFixture {

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.composeForPreview(
    context: Context,
    widgetCategory: Int,
    info: AppWidgetProviderInfo? = null,
    size: DpSize? = null,
  ): RemoteViews {
    GlanceComposerCalls.recorded += "composeForPreview(widgetCategory=$widgetCategory, size=$size)"
    return RemoteViews(context.packageName, 0)
  }
}

/** A release whose composer needs something the renderer has no value for. */
object UnsupportedComposerFixture {

  /** Stands in for a type this renderer cannot synthesise — a session, a host, a state store. */
  class SomethingWeCannotSupply

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.composeForPreview(
    context: Context,
    host: SomethingWeCannotSupply,
  ): RemoteViews = RemoteViews(context.packageName, 0)
}

/** The composer throws from inside the user's composition — the ordinary preview-bug path. */
object ThrowingComposerFixture {

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.composeForPreview(
    context: Context,
    widgetCategory: Int,
    info: AppWidgetProviderInfo? = null,
  ): RemoteViews = throw IllegalStateException("preview threw at $widgetCategory")
}

/** A composer that hands back something that is not `RemoteViews`. */
object WrongReturnComposerFixture {

  @JvmStatic
  @Suppress("unused")
  suspend fun GlanceAppWidget.composeForPreview(
    context: Context,
    widgetCategory: Int,
    info: AppWidgetProviderInfo? = null,
  ): String = "not a RemoteViews"
}
