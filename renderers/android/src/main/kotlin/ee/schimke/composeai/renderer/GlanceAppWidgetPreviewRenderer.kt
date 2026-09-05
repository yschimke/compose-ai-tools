@file:OptIn(androidx.glance.ExperimentalGlanceApi::class)

package ee.schimke.composeai.renderer

import android.appwidget.AppWidgetProviderInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import ee.schimke.composeai.renderer.GlanceComposeForPreview.compose
import kotlinx.coroutines.runBlocking

/**
 * Renders a Glance preview function — `@Composable @GlanceComposable () -> Unit` annotated with
 * `androidx.glance.preview.Preview` — into the surrounding Compose tree via an [AndroidView]
 * hosting the inflated `RemoteViews`. Mirrors the structure of [NotificationPreviewComposable]:
 * reflect the user's function, hand it to a platform inflater, host the resulting View.
 *
 * Inflation path:
 * 1. Build a [SyntheticGlanceAppWidget] whose `providePreview(...)` invokes the user's
 *    `@Composable` via [resolveNoArgComposableMethod] inside `provideContent { ... }` — the Glance
 *    composition block where `@GlanceComposable` calls resolve.
 * 2. The composer [GlanceComposeForPreview] resolved against the *project's* Glance runs the
 *    composition and materialises a `RemoteViews` tree — `composeForPreview(context,
 *    widgetCategory, info)` on Glance 1.2.0+, the `compose(context, …, size, …)` extension on
 *    1.0.0-1.1.x, which has no `composeForPreview` at all. Resolution is reflective because the
 *    render classpath is the imported project's, not ours (compose-ai-tools#5056).
 * 3. `RemoteViews.apply(context, parent)` inflates the tree into a `View` we host inside the
 *    `AndroidView` factory — the same path `AppWidgetHost.createView(...)` walks on-device.
 *
 * Sizing flows through a synthesised [AppWidgetProviderInfo]: `minWidth` / `minHeight` are set to
 * `widthDp × density` so Glance's `SizeMode.Single` widgets compose at the right `LocalSize`. The
 * surrounding `@Preview(widthDp, heightDp)` / discovery-resolved sandbox controls the Robolectric
 * window; this helper just plumbs the same size into Glance's composition.
 */
@Composable
fun GlanceAppWidgetPreviewComposable(
  className: String,
  functionName: String,
  widthDp: Int,
  heightDp: Int,
  classLoader: ClassLoader? = null,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      val parent =
        FrameLayout(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
      val widget = SyntheticGlanceAppWidget(className, functionName, classLoader)
      val info =
        AppWidgetProviderInfo().apply {
          minWidth = with(density) { widthDp.dp.toPx() }.toInt()
          minHeight = with(density) { heightDp.dp.toPx() }.toInt()
        }
      val plan = GlanceComposeForPreview.resolve(widget.javaClass.classLoader)
      val remoteViews = runBlocking {
        plan.compose(
          widget = widget,
          context = context,
          widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
          info = info,
          size = DpSize(widthDp.dp, heightDp.dp),
        )
      }
      val view = remoteViews.apply(context, parent)
      parent.addView(
        view,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      parent
    },
  )
}

/**
 * A `GlanceAppWidget` whose `providePreview(...)` body reflects the user's `@Composable
 *
 * @GlanceComposable` function and invokes it. Glance's composition runs on the same
 *   `androidx.compose.runtime.Composer` infrastructure as any other, so the standard resolve +
 *   `ComposableMethod.invoke(currentComposer, receiver)` path used by [ComposePreviewStrategy]
 *   applies unchanged.
 *
 *   Resolution goes through [resolveNoArgComposableMethod] for the same reason it does there. A
 *   parameterless `@Composable` compiles to `(Composer, Int)`, which the exact-signature lookup
 *   matches; one whose parameters all declare defaults compiles to `(realParams…, Composer,
 *   changed…, default…)`, which it cannot. Assuming the first shape is what made a defaulted Glance
 *   preview fail with `NoSuchMethodException` naming a function that is plainly there.
 *
 * Receiver resolution mirrors [resolvePreviewReceiver] so top-level Glance previews (compiled into
 * static methods on the file's synthetic `FooKt` class) and class-hosted previews (e.g. inside a
 * `class ScreenshotTest`) both work.
 *
 * `providePreview(...)` and `provideGlance(...)` share one body. The production runtime path is
 * invoked when the launcher binds a real widget, never when rendering an off-device preview — but
 * `provideGlance` is also what Glance's pre-1.2.0 `compose(…)` extension composes through, and that
 * is the fallback [GlanceComposeForPreview] resolves on a project whose Glance has no
 * `composeForPreview` (compose-ai-tools#5056). Providing the same content from both keeps the
 * preview identical whichever entry point the project's Glance version offers.
 */
private class SyntheticGlanceAppWidget(
  private val className: String,
  private val functionName: String,
  private val classLoader: ClassLoader?,
) : GlanceAppWidget() {
  override suspend fun providePreview(context: android.content.Context, widgetCategory: Int) {
    providePreviewContent()
  }

  /**
   * The user's `@Composable`, provided as this widget's content. Never returns, by Glance's
   * contract for `provideContent`: it suspends until the composition session ends.
   */
  private suspend fun providePreviewContent(): Nothing {
    provideContent {
      val cls =
        Class.forName(className, true, classLoader ?: Thread.currentThread().contextClassLoader)
      // [resolveNoArgComposableMethod], not the bare lookup. A Glance preview whose value
      // parameters all declare defaults compiles to `(realParams…, Composer, changed…, default…)`,
      // which `getDeclaredComposableMethod(name)` cannot match — it builds the exact signature
      // `(Composer, int)` out of the (absent) argument types. The miss lands *inside*
      // `provideContent { … }`, so the preview died with a bare `NoSuchMethodException` naming a
      // function plainly there, before composing anything. Every other lane resolves it this way;
      // this one was the last raw call site.
      val method = resolveNoArgComposableMethod(cls, functionName)
      // Private `@Preview` Glance composables resolve fine but would throw
      // IllegalAccessException on invoke — open them up, same as the
      // COMPOSE strategy and the tile/notification paths. Guarded so a
      // SecurityManager / strong encapsulation can't break public ones.
      runCatching { method.asMethod().isAccessible = true }
      val receiver = resolvePreviewReceiver(cls)
      method.invoke(currentComposer, receiver)
    }
  }

  override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
    // Not the production runtime path — a preview never binds a real widget. It is the entry point
    // Glance's pre-1.2.0 `compose(…)` extension composes through, though, since `providePreview`
    // only exists from 1.2.0 on, so it provides the same content rather than doing nothing
    // (compose-ai-tools#5056). On 1.2.0+ this is dead code and `providePreview` above runs instead.
    providePreviewContent()
  }
}
