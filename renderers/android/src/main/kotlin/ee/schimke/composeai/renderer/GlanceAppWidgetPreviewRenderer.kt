@file:OptIn(androidx.glance.ExperimentalGlanceApi::class)

package ee.schimke.composeai.renderer

import android.appwidget.AppWidgetProviderInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.composeForPreview
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.runBlocking

/**
 * Renders a Glance preview function — `@Composable @GlanceComposable () -> Unit` annotated with
 * `androidx.glance.preview.Preview` — into the surrounding Compose tree via an [AndroidView]
 * hosting the inflated `RemoteViews`. Mirrors the structure of [NotificationPreviewComposable]:
 * reflect the user's function, hand it to a platform inflater, host the resulting View.
 *
 * Inflation path:
 * 1. Build a [SyntheticGlanceAppWidget] whose `providePreview(...)` invokes the user's
 *    `@Composable` via [getDeclaredComposableMethod] inside `provideContent { ... }` — the Glance
 *    composition block where `@GlanceComposable` calls resolve.
 * 2. `composeForPreview(context, widgetCategory, info)` runs the composition and materialises a
 *    `RemoteViews` tree (Glance 1.2.0+).
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
      val remoteViews = runBlocking {
        widget.composeForPreview(
          context = context,
          widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
          info = info,
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
 * @GlanceComposable` function and invokes it. The function signature the Compose compiler emits for
 *   any `@Composable () -> Unit` is `(Composer, Int) -> Unit` at the JVM level, so the standard
 *   `getDeclaredComposableMethod` + `ComposableMethod.invoke(currentComposer, receiver)` path used
 *   by [ComposePreviewStrategy] applies unchanged — Glance's composition uses the same
 *   `androidx.compose.runtime.Composer` infrastructure.
 *
 * Receiver resolution mirrors [resolvePreviewReceiver] so top-level Glance previews (compiled into
 * static methods on the file's synthetic `FooKt` class) and class-hosted previews (e.g. inside a
 * `class ScreenshotTest`) both work.
 *
 * `provideGlance(...)` is a no-op — the production runtime path is invoked when the launcher binds
 * a real widget, not when rendering an off-device preview.
 */
private class SyntheticGlanceAppWidget(
  private val className: String,
  private val functionName: String,
  private val classLoader: ClassLoader?,
) : GlanceAppWidget() {
  override suspend fun providePreview(context: android.content.Context, widgetCategory: Int) {
    provideContent {
      val cls =
        Class.forName(className, true, classLoader ?: Thread.currentThread().contextClassLoader)
      val method = cls.getDeclaredComposableMethod(functionName)
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
    // Production runtime not exercised by previews.
  }
}
