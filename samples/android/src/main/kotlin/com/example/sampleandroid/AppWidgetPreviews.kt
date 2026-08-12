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
 * Sample @Preview that builds a `RemoteViews` from `widget_weather.xml` and renders it via
 * `AppWidgetContent` from `:appwidget-preview-runtime`. The runtime helper auto-discovers
 * `<appwidget-provider>` metadata for the inflated layout id (matched against
 * `AppWidgetManager.installedProviders`) and offers the resulting `supportedCells` / `resizeAxes`
 * into the launcher-widget data product. The sample's manifest registers a
 * `WeatherAppWidgetReceiver` for `R.layout.widget_weather` so the discovery has a target to match.
 * Same shape an `AppWidgetProvider.onUpdate` would push to the launcher.
 *
 * `widthDp = 312` / `heightDp = 152` matches the dp footprint a `4×2` cell on the default launcher
 * grid (`cellSize = 72.dp`, `cellSpacing = 8.dp`) — the same arithmetic the
 * `LauncherWidgetExtension` daemon-side override uses when a client sends
 * `renderNow.overrides.launcherWidget = LauncherWidgetOverride(cells = (4, 2))`. The hard-coded
 * dimensions here let the preview render through the gradle plugin path; the
 * `@LauncherWidgetPreview`-annotated samples below drive the same cell footprint from discovery via
 * the annotation in `:preview-annotations`.
 *
 * The `RemoteViews` itself comes from [weatherRemoteViews], the same factory
 * `WeatherAppWidgetReceiver.onUpdate` calls — so this preview renders the production widget rather
 * than a look-alike that can drift away from it (issue #3671).
 */
@Preview(name = "RemoteViews widget — 4×2", widthDp = 312, heightDp = 152, showBackground = true)
@Composable
fun RemoteViewsWeatherWidgetPreview() {
  AppWidgetContent { context -> weatherRemoteViews(context) }
}

// ---------------------------------------------------------------------------
// Glance widget content
//
// One composable, three consumers: the widget's production `provideGlance(...)` (what the launcher
// binds), its `providePreview(...)` (what `composeForPreview(...)` reads), and the native
// `@androidx.glance.preview.Preview` at the bottom of this file. Keeping the tree in a single
// function is the whole lesson of the Glance samples here — a sample that previews one tree and
// serves a different (or empty) one on-device teaches the defect (issue #3671).
// ---------------------------------------------------------------------------

/**
 * The weather widget's Glance tree — `Column`, `Text`, padding, background colour. Mirrors what
 * [weatherRemoteViews] paints through `widget_weather.xml`, so the side-by-side render comparison
 * between the two authoring styles is honest about what each path produces.
 *
 * [title] and [condition] are parameters purely so the native-`@Preview` sample at the bottom of
 * this file can label itself without cloning the tree; the defaults are the widget's real content.
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
 * Minimal `GlanceAppWidget` exercising the Glance composable surface.
 *
 * Both entry points serve [WeatherGlanceContent]: `provideGlance(...)` is the production runtime
 * one the launcher invokes when it binds the widget, `providePreview(...)` is what
 * `composeForPreview(...)` reads. This used to override `providePreview` with the weather UI and
 * leave `provideGlance` an empty no-op — which previews perfectly and renders a blank widget the
 * moment it is installed on a launcher. Delegating both to one content composable is what real
 * consumers do, and it is the only arrangement in which the preview is evidence about production
 * (issue #3671).
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
 * **Demonstration of the `GlanceAppWidgetContent` helper API — not the recommended way to preview a
 * Glance widget.** Prefer [NativeGlanceWidgetPreview] at the bottom of this file: annotating with
 * Glance's own `@androidx.glance.preview.Preview` is discovered by FQN and needs no helper, and is
 * the canonical sample (issue #3671).
 *
 * This one is kept because it still earns its place: it materialises [WeatherGlanceAppWidget]
 * through `GlanceAppWidgetContent` from `:glance-preview-runtime` (which drives
 * `GlanceAppWidget.composeForPreview(...)` to `RemoteViews`, then inflates that into the Compose
 * tree), so the helper path stays exercised by the sample renders and its output can be compared
 * pixel-for-pixel against both the native-annotation path and the hand-built `RemoteViews` one.
 * Reach for the helper only when you need a preview of a *`GlanceAppWidget` instance* — one
 * configured a particular way, or fanned out across sizes — rather than of a bare composable.
 *
 * Same `widthDp = 312 / heightDp = 152` (a `4×2` cell on the default launcher grid) as the sibling
 * RemoteViews preview so the renders sit next to each other in the gallery.
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
 *
 * The two arguments to [weatherRemoteViews] are the deliberate part: a `1×1` cell is 96dp square,
 * which fits neither the full city name nor the condition line, so this variant abbreviates the
 * title and drops the condition entirely — the same content-shedding a real widget does at its
 * `minResizeWidth`. Everything else (layout id, temperature, view ids) comes from the shared
 * production factory.
 */
@Preview(name = "Launcher widget — 1×1", widthDp = 96, heightDp = 96, showBackground = true)
@LauncherWidgetPreview(width = 1, height = 1)
@Composable
fun LauncherWidget1x1Preview() {
  AppWidgetContent { context -> weatherRemoteViews(context, title = "SF", condition = "") }
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
  AppWidgetContent { context -> weatherRemoteViews(context) }
}

/**
 * Demonstrates clamping: requested `7×7` is pegged into the configured `1×3`..`4×5` bounds, so the
 * rendered footprint is `4×5`. Mirrors a real Android launcher's `minResizeWidth` /
 * `minResizeHeight` behaviour.
 *
 * Only the title differs from production — it names the behaviour so the render is self-describing
 * in the gallery. The condition line used to read a bare `"Partly cloudy"` here; that was drift
 * from a copied `RemoteViews` block, not a variant, so it now comes from [weatherRemoteViews]'s
 * default like every other `4×n` sample (issue #3671).
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
 * The original spec's `1×1 → 4×2` resize walk. `@LauncherWidgetResize` fans the function out into
 * one capture per whole-cell stop (`1×1, 2×1, 3×1, 4×1, 4×2` under the default `WidthFirst` order);
 * PNGs land at `renders/<id>_RESIZE_<w>x<h>.png` and can be flipped through like a flipbook. A
 * future Phase-B stitch will encode them into an animated GIF.
 *
 * The `"Resize walk"` title is the only deliberate difference from production — it labels the
 * flipbook. The content below it comes from [weatherRemoteViews] so the same body is walked
 * through every stop, including at `1×1` where the text clips exactly as the real widget would.
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

// ---------------------------------------------------------------------------
// Launcher-mode samples
//
// `launcherMode = true` swaps the bare cell-sized box for a simulated full-device launcher home
// screen (wallpaper, status bar, weather header, app-icon grid + dock) with the widget placed on
// the home screen at its resolved cell footprint. The surrounding `@Preview(widthDp, heightDp)`
// gives the home screen a phone-shaped canvas to fill; the same weather-widget body as the samples
// above is reused unchanged — turning the mode on is all it takes for an existing widget preview to
// render on a real-looking home screen.
// ---------------------------------------------------------------------------

/**
 * The 4×2 weather widget shown on a simulated launcher home screen. Same widget body as
 * [LauncherWidget4x2Preview]; `launcherMode = true` wraps it in the launcher chrome and the
 * phone-shaped `@Preview` window gives that chrome a full device to fill.
 */
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
 * The `1×1 → 4×2` resize walk, each stop rendered on the launcher home screen — a flipbook of the
 * widget being resized on a real-looking device. PNGs land at `renders/<id>_RESIZE_<w>x<h>.png`.
 *
 * Same body and same `"Resize walk"` label as [LauncherWidgetResize1x1To4x2Preview]; the only
 * difference between the two is `launcherMode = true` on the annotation, which is the point the
 * pair is making.
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

// ---------------------------------------------------------------------------
// Native `@androidx.glance.preview.Preview` discovery — the canonical Glance sample
//
// This is the way to preview a Glance surface: annotate the composable with Glance's own
// `@Preview`, nothing else. The gradle plugin's discovery picks the FQN up, marks the entry as
// `PreviewKind.GLANCE_APPWIDGET`, and the renderer wraps the function in a synthetic
// `GlanceAppWidget.providePreview(...)` driven by `composeForPreview(...)` — same end-state as
// the `GlanceAppWidgetContent` helper path above, with no helper call and no widget instance to
// construct. `GlanceWeatherWidgetPreview` is retained above only as an explicitly-labelled
// demonstration of that helper API and of the side-by-side render comparison; copy this one.
// ---------------------------------------------------------------------------

/**
 * Glance preview annotated with Glance's own `@androidx.glance.preview.Preview`. Discovery
 * recognises the FQN and treats the function as `PreviewKind.GLANCE_APPWIDGET`; the renderer
 * reflects the body into a synthetic `GlanceAppWidget` and materialises via
 * `composeForPreview(...)`.
 *
 * The body is [WeatherGlanceContent] — the same tree [WeatherGlanceAppWidget] serves from
 * `provideGlance(...)`, so this preview is evidence about production rather than a look-alike. The
 * two overridden strings are the deliberate difference: they name the path that rendered the PNG,
 * which is what makes the three widget renders distinguishable in the gallery.
 */
@OptIn(ExperimentalGlancePreviewApi::class)
@GlancePreview(widthDp = 312, heightDp = 152)
@Composable
fun NativeGlanceWidgetPreview() {
  WeatherGlanceContent(title = "Native @glance.preview.Preview", condition = "Discovered by FQN")
}
