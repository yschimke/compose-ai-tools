@file:OptIn(androidx.glance.ExperimentalGlanceApi::class)

package com.example.sampleandroid

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview as GlancePreview
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceFixedColorProvider
import ee.schimke.composeai.preview.LauncherWidgetPreview
import ee.schimke.composeai.preview.glance.GlanceAppWidgetContent

// ---------------------------------------------------------------------------
// Composable helper for hosting AppWidget-shaped UI inside a Compose @Preview.
//
//   AppWidgetContent { ctx -> RemoteViews(...) }
//     — accepts an arbitrary `(Context) -> RemoteViews` factory. The same path
//       on-device runs inside `AppWidgetHost.createView(...)`: `RemoteViews.apply`
//       inflates the tree against the host context and returns a `View` the
//       launcher hosts inside its cell. We hand the inflated tree to `AndroidView`
//       so the renderer captures it the same way the launcher would.
//
// A sister Glance-widget preview is intentionally absent here pending native
// `@androidx.glance.preview.Preview` discovery support — see the open task on
// Glance preview integration. Hand-built `RemoteViews` widgets exercise the
// inflate path end-to-end today.
// ---------------------------------------------------------------------------

/**
 * Inflates an arbitrary `RemoteViews` tree into the surrounding Compose tree.
 *
 * Use to author a `@Preview` for a launcher widget whose layout is hand-built (the legacy
 * `AppWidgetProvider` path). The factory takes the `Context` resolved from the surrounding
 * composition so calls like `RemoteViews(context.packageName, R.layout.widget_xxx)` reach the
 * right package id at render time.
 *
 * The inflated view is hosted inside `MATCH_PARENT × MATCH_PARENT` so a `@Preview(widthDp, heightDp)`
 * (or the `LauncherWidgetExtension` daemon-side override) controls the visible footprint.
 */
@Composable
fun AppWidgetContent(factory: (Context) -> RemoteViews) {
  val context = LocalContext.current
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
      val view: View = factory(context).apply(context, parent)
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
 * Sample @Preview that hand-builds a `RemoteViews` from `widget_weather.xml` and renders it via
 * [AppWidgetContent]. Same shape an `AppWidgetProvider.onUpdate` would push to the launcher.
 *
 * `widthDp = 312` / `heightDp = 152` matches the dp footprint a `4×2` cell on the default
 * launcher grid (`cellSize = 72.dp`, `cellSpacing = 8.dp`) — the same arithmetic the
 * `LauncherWidgetExtension` daemon-side override uses when a client sends
 * `renderNow.overrides.launcherWidget = LauncherWidgetOverride(cells = (4, 2))`. The hard-coded
 * dimensions here let the preview render through the gradle plugin path; the
 * `@LauncherWidgetPreview`-annotated samples below drive the same cell footprint from discovery
 * via the annotation in `:preview-annotations`.
 */
@Preview(name = "RemoteViews widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@Composable
fun RemoteViewsWeatherWidgetPreview() {
  AppWidgetContent { context ->
    RemoteViews(context.packageName, R.layout.widget_weather).apply {
      setTextViewText(R.id.widget_title, "San Francisco")
      setTextViewText(R.id.widget_temperature, "67°")
      setTextViewText(R.id.widget_condition, "Partly cloudy · H 70° / L 55°")
    }
  }
}

// ---------------------------------------------------------------------------
// Glance widget preview — Phase A integration
//
// The renderer doesn't yet discover `@androidx.glance.preview.Preview` by FQN (that's the
// follow-up "native Glance preview support" work), so the sample uses a standard
// `@androidx.compose.ui.tooling.preview.Preview` + the `GlanceAppWidgetContent` helper from
// `:glance-preview-runtime`. The helper drives `GlanceAppWidget.composeForPreview(...)` to
// materialise the widget to `RemoteViews`, then inflates that into the Compose tree — same
// translation Glance does on-device when the launcher binds the widget.
// ---------------------------------------------------------------------------

/**
 * Minimal `GlanceAppWidget` exercising the Glance composable surface — `Column`, `Text`, padding,
 * background colour. Mirrors [RemoteViewsWeatherWidgetPreview]'s content so the side-by-side
 * comparison is honest about what each rendering path produces.
 *
 * Overrides `providePreview(...)` rather than `provideGlance(...)`: `composeForPreview(...)`
 * reads from the former, the latter is the production runtime entry-point invoked when the
 * launcher binds the widget. Real consumers typically delegate `provideGlance` to a shared
 * content composable so the same tree runs in both surfaces; here we only need the preview.
 */
private class WeatherGlanceAppWidget : GlanceAppWidget() {
  override suspend fun providePreview(context: Context, widgetCategory: Int) {
    provideContent {
      Column(
        modifier =
          GlanceModifier.fillMaxSize()
            .background(GlanceFixedColorProvider(ComposeColor(0xFF1A237E)))
            .padding(12.dp)
      ) {
        Text(
          text = "San Francisco",
          style =
            TextStyle(
              color = GlanceFixedColorProvider(ComposeColor.White),
              fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
          text = "67°",
          style = TextStyle(color = GlanceFixedColorProvider(ComposeColor.White)),
        )
        Text(
          text = "Partly cloudy · H 70° / L 55°",
          style = TextStyle(color = GlanceFixedColorProvider(ComposeColor(0xB3FFFFFF))),
        )
      }

      // `provideGlance` is abstract — keep it overridden as a no-op so the class compiles. Real
      // consumers share content between `providePreview` and `provideGlance` via a helper.
    }
  }

  override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
    // Production runtime not exercised by this preview-only sample.
  }
}

/**
 * Sample @Preview that materialises [WeatherGlanceAppWidget] via the
 * `GlanceAppWidgetContent` helper. Same `widthDp = 312 / heightDp = 152` (a `4×2` cell on the
 * default launcher grid) as the sibling RemoteViews preview so the two renders sit next to each
 * other in the gallery and any rendering differences between the legacy
 * `RemoteViews(layoutId, ...)` path and the Glance-compose-to-RemoteViews path are visually
 * obvious.
 */
@Preview(name = "Glance widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@Composable
fun GlanceWeatherWidgetPreview() {
  GlanceAppWidgetContent(
    widget = WeatherGlanceAppWidget(),
    size = DpSize(width = 312.dp, height = 152.dp),
  )
}

// ---------------------------------------------------------------------------
// `@LauncherWidgetPreview` annotation samples
//
// Same widget content as `RemoteViewsWeatherWidgetPreview` above, but the cell footprint is
// driven by `@LauncherWidgetPreview(width, height)` instead of `@Preview(widthDp, heightDp)`.
// The gradle plugin's discovery picks the annotation up by FQN, stamps a
// `LauncherWidgetCapture` onto every capture of the function, and the renderer wraps the
// composition with `:data-launcher-widget-connector`'s `LauncherWidgetExtension` — the same
// around-composable a daemon-driven `renderNow.overrides.launcherWidget` would apply. The
// surrounding `@Preview(widthDp, heightDp)` still sets the Robolectric sandbox window; the
// annotation-driven wrap then constrains the visible cell-shaped region inside it.
// ---------------------------------------------------------------------------

/**
 * Smallest cell shape supported by the default `1×1`..`5×5` bounds. Same widget body as
 * [RemoteViewsWeatherWidgetPreview] but the cell footprint is annotation-driven.
 */
@Preview(name = "Launcher widget — 1×1", widthDp = 96, heightDp = 96, showBackground = true)
@LauncherWidgetPreview(width = 1, height = 1)
@Composable
fun LauncherWidget1x1Preview() {
  AppWidgetContent { context ->
    RemoteViews(context.packageName, R.layout.widget_weather).apply {
      setTextViewText(R.id.widget_title, "SF")
      setTextViewText(R.id.widget_temperature, "67°")
      setTextViewText(R.id.widget_condition, "")
    }
  }
}

/**
 * Full `4×2` cell footprint via the annotation — mirror of [RemoteViewsWeatherWidgetPreview]'s
 * `@Preview(widthDp = 312, heightDp = 152)` but driven from the discovery-stamped
 * `LauncherWidgetCapture` instead of hand-tuned `@Preview` dimensions.
 */
@Preview(name = "Launcher widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@LauncherWidgetPreview(width = 4, height = 2)
@Composable
fun LauncherWidget4x2Preview() {
  AppWidgetContent { context ->
    RemoteViews(context.packageName, R.layout.widget_weather).apply {
      setTextViewText(R.id.widget_title, "San Francisco")
      setTextViewText(R.id.widget_temperature, "67°")
      setTextViewText(R.id.widget_condition, "Partly cloudy · H 70° / L 55°")
    }
  }
}

/**
 * Demonstrates clamping: requested `7×7` is pegged into the configured `1×3`..`4×5` bounds, so
 * the rendered footprint is `4×5`. Mirrors a real Android launcher's `minResizeWidth` /
 * `minResizeHeight` behaviour.
 */
@Preview(name = "Launcher widget — clamped to 4×5", widthDp = 312, heightDp = 392, showBackground = true)
@LauncherWidgetPreview(
  width = 7,
  height = 7,
  minWidth = 1,
  minHeight = 3,
  maxWidth = 4,
  maxHeight = 5,
)
@Composable
fun LauncherWidgetClampedPreview() {
  AppWidgetContent { context ->
    RemoteViews(context.packageName, R.layout.widget_weather).apply {
      setTextViewText(R.id.widget_title, "Clamped → 4×5")
      setTextViewText(R.id.widget_temperature, "67°")
      setTextViewText(R.id.widget_condition, "Partly cloudy")
    }
  }
}

// ---------------------------------------------------------------------------
// Native `@androidx.glance.preview.Preview` discovery
//
// Same Glance composable body as the `GlanceWeatherWidgetPreview` above, but the function is
// annotated with Glance's own `@Preview` instead of the standard
// `@androidx.compose.ui.tooling.preview.Preview` + `GlanceAppWidgetContent` helper. The gradle
// plugin's discovery picks the FQN up, marks the entry as `PreviewKind.GLANCE_APPWIDGET`, and
// the renderer wraps the function in a synthetic `GlanceAppWidget.providePreview(...)` driven
// by `composeForPreview(...)` — same end-state as the helper-based path, just authored from a
// single annotation.
// ---------------------------------------------------------------------------

/**
 * Glance preview annotated with Glance's own `@androidx.glance.preview.Preview`. Discovery
 * recognises the FQN and treats the function as `PreviewKind.GLANCE_APPWIDGET`; the renderer
 * reflects the body into a synthetic `GlanceAppWidget` and materialises via
 * `composeForPreview(...)`.
 */
@OptIn(ExperimentalGlancePreviewApi::class)
@GlancePreview(widthDp = 312, heightDp = 152)
@Composable
fun NativeGlanceWidgetPreview() {
  Column(
    modifier =
      GlanceModifier.fillMaxSize()
        .background(GlanceFixedColorProvider(ComposeColor(0xFF1A237E)))
        .padding(12.dp)
  ) {
    Text(
      text = "Native @glance.preview.Preview",
      style =
        TextStyle(
          color = GlanceFixedColorProvider(ComposeColor.White),
          fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(GlanceModifier.height(8.dp))
    Text(
      text = "67°",
      style = TextStyle(color = GlanceFixedColorProvider(ComposeColor.White)),
    )
    Text(
      text = "Discovered by FQN",
      style = TextStyle(color = GlanceFixedColorProvider(ComposeColor(0xB3FFFFFF))),
    )
  }
}
