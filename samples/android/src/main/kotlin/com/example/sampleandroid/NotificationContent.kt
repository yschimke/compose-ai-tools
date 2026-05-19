package com.example.sampleandroid

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable helper that inflates a notification factory into the surrounding Compose tree.
 *
 * Pairs with stacked `@Preview` (multi-preview meta-annotations) to fan out variants of the same
 * notification across the existing knobs `@Preview` already owns — `uiMode`, `locale`, `widthDp`,
 * `fontScale`. The fan-out is driven by Compose tooling: discovery + the renderer's COMPOSE path
 * pick each `@Preview` up as a separate entry, so no notification-specific plumbing is required.
 *
 * Inflation path mirrors the renderer-side `NotificationPreviewComposable`:
 * `Notification.Builder.recoverBuilder(context, notification)` → `createBigContentView()` (with
 * `createContentView()` as the collapsed fallback) → `RemoteViews.apply(...)`. This is the AOSP
 * visual; OEM chrome (Pixel rounded corners, Samsung tinting) is drawn by SystemUI on-device and
 * isn't reproducible under Robolectric.
 *
 * Sample-local for now — promotion to a published `:notification-preview-runtime` artifact is the
 * next slice after #1249.
 */
@Composable
fun NotificationContent(factory: (Context) -> Notification) {
  val context = LocalContext.current
  AndroidView(
    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
    factory = { ctx ->
      val parent =
        FrameLayout(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            )
          // RemoteViews title rows resolve `?attr/textColorPrimary` against the activity theme,
          // which is near-white under `uiMode = NIGHT_YES`. Without a matching dark surface
          // behind the inflated tree, the title text renders white-on-white. SystemUI on-device
          // paints the dark notification surface for us; here we have to do it ourselves. Read
          // `android.R.attr.colorBackground` from the theme so the colour tracks the active
          // night-mode configuration without us hard-coding light / dark values.
          setBackgroundColor(resolveBackgroundColor(ctx))
        }
      val notification = factory(context)
      val view =
        inflateNotificationView(context, notification, parent)
          ?: error("NotificationContent produced no inflatable RemoteViews")
      parent.addView(
        view,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
      )
      parent
    },
  )
}

/**
 * AOSP-derived notification surface colours, picked off the active `Configuration.uiMode`. We
 * deliberately don't read `?android:attr/colorBackground` from the activity theme: the renderer's
 * sandbox activity uses a generic theme that resolves the same lavender for both day and night
 * modes, so the title row's `?attr/textColorPrimary` (near-white under NIGHT_YES) renders
 * white-on-white. Hard-coding the two surface values keeps each variant's contrast correct.
 *
 * Values approximate `Theme.DeviceDefault.Notification` / `…Notification.Dark` (≈ `#FFFFFF`
 * day, `#1F1F1F` night) — close enough to AOSP that the rendered PNG reads like the shade
 * surface a stock device would draw.
 */
private fun resolveBackgroundColor(context: Context): Int {
  val night =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
      Configuration.UI_MODE_NIGHT_YES
  return if (night) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()
}

@Suppress("DEPRECATION")
private fun inflateNotificationView(
  context: Context,
  notification: Notification,
  parent: ViewGroup,
): android.view.View? {
  // `createBigContentView` / `createContentView` are marked deprecated for production posting
  // paths (where the system inflates them for you) but there's no non-deprecated alternative when
  // you specifically want the RemoteViews tree for offline rendering.
  val builder = Notification.Builder.recoverBuilder(context, notification)
  val remoteViews = builder.createBigContentView() ?: builder.createContentView() ?: return null
  return remoteViews.apply(context, parent)
}
