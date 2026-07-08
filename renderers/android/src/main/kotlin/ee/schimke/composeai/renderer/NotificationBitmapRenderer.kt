package ee.schimke.composeai.renderer

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Compose-free primitive that turns a posted-style [Notification] into a [Bitmap].
 *
 * Mirrors the inflation path used by [NotificationPreviewComposable] —
 * `Notification.Builder.recoverBuilder(context, notification)` → `createBigContentView()` (with
 * `createContentView()` as the collapsed fallback) → `RemoteViews.apply(...)` — but stops before
 * touching Compose. The inflated `View` is measured, laid out, and drawn into an ARGB_8888 [Bitmap]
 * via raw platform `View` + `Canvas` APIs.
 *
 * Intended for callers that need to render `@NotificationPreview` entries from build environments
 * that cannot (or do not want to) pull Jetpack Compose onto the test classpath — e.g. Bazel modules
 * consuming the discovery + render pipeline without the Compose UI test rule.
 *
 * No `androidx.compose.*` and no `roborazzi` imports live in this file by design; the
 * `:renderer-android` test for it exercises the same surface from a Robolectric-only test.
 */
object NotificationBitmapRenderer {
  /**
   * Inflate [notification]'s expanded RemoteViews (falling back to collapsed) and draw the
   * resulting View into a [Bitmap] at the given [widthPx] (height derived from measured content).
   *
   * Returns `null` when the notification has no inflatable RemoteViews — e.g. a custom-view-only
   * notification produced via `setCustomBigContentView` that returns a non-applicable tree, or when
   * measurement collapses to a zero-size view.
   */
  @Suppress("DEPRECATION")
  fun render(context: Context, notification: Notification, widthPx: Int): Bitmap? {
    require(widthPx > 0) { "widthPx must be > 0, got $widthPx" }
    val view = inflate(context, notification) ?: return null

    view.measure(
      MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
    )
    val measuredWidth = view.measuredWidth
    val measuredHeight = view.measuredHeight
    if (measuredWidth <= 0 || measuredHeight <= 0) return null
    view.layout(0, 0, measuredWidth, measuredHeight)

    val bitmap = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
  }

  /**
   * Inflate the notification's expanded or collapsed RemoteViews into a detached parent. The
   * parent's only job is to satisfy `RemoteViews.apply(context, parent)`'s contract that it receive
   * a `ViewGroup` for `LayoutInflater.inflate(..., parent, false)` — the inflated view is returned
   * standalone, never attached.
   *
   * `createBigContentView` / `createContentView` are marked deprecated for production posting
   * (where SystemUI inflates them for you) but there's no non-deprecated alternative when you
   * specifically want the `RemoteViews` tree for offline rendering. Same suppression rationale as
   * [NotificationPreviewComposable].
   */
  @Suppress("DEPRECATION")
  private fun inflate(context: Context, notification: Notification): View? {
    val parent: ViewGroup = FrameLayout(context)
    val builder = Notification.Builder.recoverBuilder(context, notification)
    val remoteViews = builder.createBigContentView() ?: builder.createContentView() ?: return null
    return remoteViews.apply(context, parent)
  }
}
