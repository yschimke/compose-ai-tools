package com.example.sampleandroid

import android.app.Notification
import android.content.Context
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
