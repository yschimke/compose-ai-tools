package com.example.sampleandroid

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView

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
 * dimensions here let the preview render through the gradle plugin path (which doesn't have a
 * `@LauncherWidgetPreview` annotation yet — that's the next deliverable).
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
