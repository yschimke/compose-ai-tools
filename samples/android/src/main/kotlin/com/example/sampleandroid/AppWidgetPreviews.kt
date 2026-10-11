@file:OptIn(androidx.glance.ExperimentalGlanceApi::class)

package com.example.sampleandroid

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview as GlancePreview
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceFixedColorProvider
import ee.schimke.composeai.preview.LauncherWidgetPreview
import ee.schimke.composeai.preview.LauncherWidgetResize
import ee.schimke.composeai.preview.LauncherWidgetResizeOrder
import ee.schimke.composeai.preview.appwidget.AppWidgetContent
import ee.schimke.composeai.preview.glance.GlanceAppWidgetContent

/**
 * Builds a `RemoteViews` from `widget_weather.xml` and renders it via `AppWidgetContent` from
 * `:appwidget-preview-runtime`. The helper matches the layout id against
 * `AppWidgetManager.installedProviders` (the manifest registers `WeatherAppWidgetReceiver`) and
 * offers its `supportedCells` / `resizeAxes` to the launcher-widget data product.
 *
 * `312×152` dp is a `4×2` cell on the default launcher grid (`cellSize = 72.dp`,
 * `cellSpacing = 8.dp`); the `@LauncherWidgetPreview` samples below derive the same footprint from
 * the annotation instead.
 *
 * The `RemoteViews` comes from [weatherRemoteViews], the same factory the receiver's `onUpdate`
 * calls, so this previews the production widget rather than a look-alike.
 */
@Preview(name = "RemoteViews widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@Composable
fun RemoteViewsWeatherWidgetPreview() {
  AppWidgetContent { context -> weatherRemoteViews(context) }
}

// Glance widget content: one composable serving the widget's `provideGlance(...)`, its
// `providePreview(...)` and the native `@androidx.glance.preview.Preview` below, so the preview
// shows exactly what ships on-device.

/**
 * The weather widget's Glance tree, mirroring what [weatherRemoteViews] paints so the two authoring
 * styles can be compared side by side. [title] and [condition] let the native-`@Preview` sample
 * label itself; the defaults are the real content.
 */
@Composable
private fun WeatherGlanceContent(
  title: String = "San Francisco",
  temperature: String = "67°",
  condition: String = "Partly cloudy · H 70° / L 55°",
) {
  Column(
    modifier =
      GlanceModifier.fillMaxSize()
        .background(GlanceFixedColorProvider(ComposeColor(0xFF1A237E)))
        .padding(12.dp)
  ) {
    Text(
      text = title,
      style =
        TextStyle(
          color = GlanceFixedColorProvider(ComposeColor.White),
          fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(GlanceModifier.height(8.dp))
    Text(
      text = temperature,
      style = TextStyle(color = GlanceFixedColorProvider(ComposeColor.White)),
    )
    Text(
      text = condition,
      style = TextStyle(color = GlanceFixedColorProvider(ComposeColor(0xB3FFFFFF))),
    )
  }
}

/**
 * Minimal `GlanceAppWidget`. Both `provideGlance(...)` (what the launcher binds) and
 * `providePreview(...)` serve [WeatherGlanceContent], so the preview is evidence about production —
 * overriding only `providePreview` would preview fine and install as a blank widget.
 */
private class WeatherGlanceAppWidget : GlanceAppWidget() {
  override suspend fun providePreview(context: Context, widgetCategory: Int) {
    provideContent { WeatherGlanceContent() }
  }

  override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
    provideContent { WeatherGlanceContent() }
  }
}

/**
 * Demonstrates the `GlanceAppWidgetContent` helper API — **not** the recommended way to preview a
 * Glance widget; prefer [NativeGlanceWidgetPreview] below.
 *
 * Kept so the helper path (driving `GlanceAppWidget.composeForPreview(...)` to `RemoteViews`, then
 * inflating into Compose) stays exercised and comparable. Use the helper only when you need a
 * preview of a configured `GlanceAppWidget` instance rather than a bare composable. Same `4×2`
 * footprint as the RemoteViews preview.
 */
@Preview(
  name = "Glance widget via helper API — 4×2",
  widthDp = 312,
  heightDp = 152,
  showBackground = true,
)
@Composable
fun GlanceWeatherWidgetPreview() {
  GlanceAppWidgetContent(
    widget = WeatherGlanceAppWidget(),
    size = DpSize(width = 312.dp, height = 152.dp),
  )
}

// `@LauncherWidgetPreview` samples: the same widget, with the cell footprint driven by the
// annotation. Discovery stamps a `LauncherWidgetCapture` and the renderer wraps the composition in
// `LauncherWidgetExtension`. `@Preview(widthDp, heightDp)` still sets the sandbox window; the wrap
// constrains the cell-shaped region inside it.

/**
 * Smallest cell shape (`1×1`, 96dp square). The [weatherRemoteViews] arguments abbreviate the title
 * and drop the condition line, as a real widget does at its `minResizeWidth`.
 */
@Preview(name = "Launcher widget — 1×1", widthDp = 96, heightDp = 96, showBackground = true)
@LauncherWidgetPreview(width = 1, height = 1)
@Composable
fun LauncherWidget1x1Preview() {
  AppWidgetContent { context -> weatherRemoteViews(context, title = "SF", condition = "") }
}

/** The full `4×2` footprint, driven by the annotation rather than hand-tuned `@Preview` sizes. */
@Preview(name = "Launcher widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@LauncherWidgetPreview(width = 4, height = 2)
@Composable
fun LauncherWidget4x2Preview() {
  AppWidgetContent { context -> weatherRemoteViews(context) }
}

/**
 * Demonstrates clamping: a requested `7×7` is pegged into the configured `1×3`..`4×5` bounds, like
 * a launcher's `minResizeWidth` / `minResizeHeight`. Only the title differs from production.
 */
@Preview(
  name = "Launcher widget — clamped to 4×5",
  widthDp = 312,
  heightDp = 392,
  showBackground = true,
)
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
  AppWidgetContent { context -> weatherRemoteViews(context, title = "Clamped → 4×5") }
}

/**
 * A `1×1 → 4×2` resize walk. `@LauncherWidgetResize` fans out one capture per whole-cell stop
 * (`1×1, 2×1, 3×1, 4×1, 4×2` under `WidthFirst`), written to `renders/<id>_RESIZE_<w>x<h>.png`.
 * Only the title differs from production, so the text clips at `1×1` exactly as the real widget
 * would.
 */
@Preview(
  name = "Launcher widget — resize 1×1 → 4×2",
  widthDp = 312,
  heightDp = 152,
  showBackground = true,
)
@LauncherWidgetResize(
  fromWidth = 1,
  fromHeight = 1,
  toWidth = 4,
  toHeight = 2,
  resizeOrder = LauncherWidgetResizeOrder.WidthFirst,
)
@Composable
fun LauncherWidgetResize1x1To4x2Preview() {
  AppWidgetContent { context -> weatherRemoteViews(context, title = "Resize walk") }
}

// Launcher-mode samples: `launcherMode = true` places the widget on a simulated launcher home
// screen (wallpaper, status bar, app grid, dock) at its resolved footprint; the phone-shaped
// `@Preview` gives it a canvas. Nothing else about the widget preview changes.

/** The 4×2 weather widget on a simulated launcher home screen. */
@Preview(
  name = "Launcher mode — 4×2 on home screen",
  widthDp = 411,
  heightDp = 914,
  showBackground = true,
)
@LauncherWidgetPreview(width = 4, height = 2, launcherMode = true)
@Composable
fun LauncherModeHomeScreenPreview() {
  AppWidgetContent { context -> weatherRemoteViews(context) }
}

/**
 * The `1×1 → 4×2` resize walk on the launcher home screen; identical to
 * [LauncherWidgetResize1x1To4x2Preview] except for `launcherMode = true`.
 */
@Preview(
  name = "Launcher mode — resize on home screen",
  widthDp = 411,
  heightDp = 914,
  showBackground = true,
)
@LauncherWidgetResize(
  fromWidth = 1,
  fromHeight = 1,
  toWidth = 4,
  toHeight = 2,
  resizeOrder = LauncherWidgetResizeOrder.WidthFirst,
  launcherMode = true,
)
@Composable
fun LauncherModeResizePreview() {
  AppWidgetContent { context -> weatherRemoteViews(context, title = "Resize walk") }
}

// Native `@androidx.glance.preview.Preview` — the canonical way to preview a Glance surface; copy
// this one. Discovery marks it `PreviewKind.GLANCE_APPWIDGET` and the renderer wraps it in a
// synthetic `GlanceAppWidget.providePreview(...)`, with no helper call or widget instance needed.

/**
 * Glance preview using Glance's own `@Preview`. The body is [WeatherGlanceContent], the same tree
 * production serves; the two overridden strings label which path rendered the PNG.
 */
@OptIn(ExperimentalGlancePreviewApi::class)
@GlancePreview(widthDp = 312, heightDp = 152)
@Composable
fun NativeGlanceWidgetPreview() {
  WeatherGlanceContent(title = "Native @glance.preview.Preview", condition = "Discovered by FQN")
}

/**
 * The same surface with defaulted value parameters — the shape most widget composables have. It
 * compiles to `(params…, Composer, int, int)`, so it needs `resolveNoArgComposableMethod` rather
 * than an exact-signature lookup; keeping both this and [NativeGlanceWidgetPreview] proves the
 * Glance lane handles both shapes.
 */
@OptIn(ExperimentalGlancePreviewApi::class)
@GlancePreview(widthDp = 312, heightDp = 152)
@Composable
fun DefaultedGlanceWidgetPreview(
  title: String = "Defaulted @Composable params",
  condition: String = "Resolved via the defaults mask",
) {
  WeatherGlanceContent(title = title, condition = condition)
}
