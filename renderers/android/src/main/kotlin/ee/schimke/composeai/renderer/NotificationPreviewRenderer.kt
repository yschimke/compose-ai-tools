package ee.schimke.composeai.renderer

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.reflect.Method

/**
 * Fallback notification surface width when a caller doesn't pass [NotificationPreviewComposable]'s
 * `widthDp`. Mirrors the discovery / gallery sandbox width (`DeviceDimensions.SANDBOX_WIDTH_DP` /
 * `DEFAULT_NOTIFICATION_WIDTH_DP`, both 400dp) so a directly-invoked render still gets the wide
 * shade footprint rather than the ~320dp intrinsic square from #1249.
 */
const val NOTIFICATION_PREVIEW_DEFAULT_WIDTH_DP: Int = 400

/**
 * Renders a `@NotificationPreview` function — `(Context) -> Notification` — into the surrounding
 * Compose tree via an [AndroidView] hosting the inflated `RemoteViews`. Mirrors the structure of
 * [TilePreviewComposable]: reflect the user's function, invoke it, hand the result to a platform
 * inflater, host the resulting View.
 *
 * Inflation path uses `Notification.Builder.recoverBuilder(context, notification)` to get back a
 * platform builder, then asks it for `createBigContentView()` (expanded heads-up / shade content)
 * and falls back to `createContentView()` for collapsed-only notifications. This is the AOSP visual
 * — the SystemUI chrome a Pixel / OEM device draws on top is not reproducible inside Robolectric.
 * See issue #1249 for the full design.
 */
@Composable
fun NotificationPreviewComposable(
  className: String,
  functionName: String,
  /**
   * Classloader used to resolve [className]. `null` defers to the caller-thread context classloader
   * (the standalone renderer path's user classes share the test classpath). Same shape as
   * [TilePreviewComposable] for the daemon path's per-render child loader.
   */
  classLoader: ClassLoader? = null,
  /**
   * Preview id used as the filename stem for the structured-fields JSON sidecar
   * (`<outputDir>/../data/notifications/<id>.notification.json`). `null` skips the sidecar write —
   * kept optional so call sites that don't care about the sidecar (a future doc snippet, a quick
   * test) can pass through unchanged. Both call sites the renderer / daemon use today pass the
   * manifest's preview id.
   */
  previewId: String? = null,
  /**
   * Width of the notification surface in dp — the render canvas width the caller laid out for. Set
   * as an exact width rather than `fillMaxWidth()`: under the renderer's wrap-to-content measure
   * path the `AndroidView` is handed `minWidth = 0`, and the inflated RemoteViews tree's ~320dp
   * AOSP intrinsic width is what comes back, cropping the PNG to a square (#1249). Pinning the
   * width gives the inflater the full canvas, matching the `NotificationContent` gallery fix
   * (#1576). Defaults to [NOTIFICATION_PREVIEW_DEFAULT_WIDTH_DP] for direct callers.
   */
  widthDp: Int = NOTIFICATION_PREVIEW_DEFAULT_WIDTH_DP,
) {
  val context = LocalContext.current
  AndroidView(
    modifier = Modifier.width(widthDp.dp).wrapContentHeight(),
    factory = { ctx ->
      val parent =
        FrameLayout(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            )
          // RemoteViews title rows resolve `?attr/textColorPrimary` against the activity
          // theme, which is near-white under `uiMode = NIGHT_YES`. Without a matching dark
          // surface behind the inflated tree, the title text renders white-on-white.
          // SystemUI on-device paints the dark notification surface for us; here we paint
          // it ourselves off `Configuration.uiMode`. Mirrors the same fix the sample-local
          // `NotificationContent` helper carries.
          setBackgroundColor(resolveNotificationBackgroundColor(ctx))
        }
      renderNotificationInto(context, className, functionName, classLoader, parent, previewId)
      parent
    },
  )
}

private fun renderNotificationInto(
  context: Context,
  className: String,
  functionName: String,
  classLoader: ClassLoader?,
  parent: FrameLayout,
  previewId: String?,
) {
  val notification =
    invokeNotificationPreviewFunction(context, className, functionName, classLoader)
  val view =
    inflateNotificationView(context, notification, parent)
      ?: error("NotificationPreview '$functionName' produced no inflatable RemoteViews")
  parent.addView(
    view,
    FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )
  // Best-effort structured-fields sidecar. Resolves the output dir from
  // `composeai.render.outputDir`; no-ops silently when the property is absent or [previewId] is
  // null, matching the behaviour of [RenderErrorSidecar].
  if (previewId != null) {
    NotificationSidecar.write(previewId, notification, context)
  }
}

/**
 * Resolves the user's notification factory and returns its result. Prefers a `(Context)` overload;
 * the no-arg overload is accepted too for functions that build a notification from a singleton
 * context (rare, but the symmetry with [TilePreviewComposable] keeps the contract predictable).
 */
private fun invokeNotificationPreviewFunction(
  context: Context,
  className: String,
  functionName: String,
  classLoader: ClassLoader?,
): Notification {
  val method = findNotificationPreviewMethod(className, functionName, classLoader)
  method.isAccessible = true
  val result =
    when (method.parameterTypes.size) {
      0 -> method.invoke(null)
      1 -> method.invoke(null, context)
      else ->
        error(
          "NotificationPreview '$functionName' has unsupported signature; " +
            "expected 0 or 1 (Context) parameters, found ${method.parameterTypes.size}"
        )
    }
  return result as? Notification
    ?: error("NotificationPreview '$functionName' did not return android.app.Notification")
}

private fun findNotificationPreviewMethod(
  className: String,
  functionName: String,
  classLoader: ClassLoader?,
): Method {
  val cls =
    if (classLoader != null) Class.forName(className, true, classLoader)
    else Class.forName(className)
  val candidates = cls.declaredMethods.filter { it.name == functionName }
  if (candidates.isEmpty()) {
    error("No method '$functionName' on '$className'")
  }
  return candidates.firstOrNull {
    it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java
  }
    ?: candidates.firstOrNull { it.parameterTypes.isEmpty() }
    ?: error(
      "NotificationPreview '$functionName' on '$className' has no supported overload " +
        "(expected no-arg or single Context parameter)"
    )
}

/**
 * Builds the expanded notification View. Tries `createBigContentView()` (the heads-up / expanded
 * layout used when the notification carries a `setStyle(...)`), falling back to the standard
 * `createContentView()` for collapsed-only notifications. Returns `null` if neither path produces a
 * `RemoteViews` — caller treats that as a render error.
 */
/**
 * AOSP-derived notification surface colours, picked off the active `Configuration.uiMode`. We
 * deliberately don't read `?android:attr/colorBackground` from the activity theme: the renderer's
 * sandbox activity uses a generic theme that resolves the same lavender for both day and night
 * modes, so the title row's `?attr/textColorPrimary` (near-white under NIGHT_YES) renders
 * white-on-white. Values approximate `Theme.DeviceDefault.Notification[.Dark]` (`#FFFFFF` day,
 * `#1F1F1F` night) — close enough to AOSP that the rendered PNG reads like the shade surface a
 * stock device would draw.
 */
private fun resolveNotificationBackgroundColor(context: Context): Int {
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
): View? {
  // `createBigContentView` / `createContentView` are marked deprecated for production
  // posting paths (where the system inflates them for you) but there's no non-deprecated
  // alternative when you specifically want the RemoteViews tree for offline rendering.
  val builder = Notification.Builder.recoverBuilder(context, notification)
  val remoteViews = builder.createBigContentView() ?: builder.createContentView() ?: return null
  return remoteViews.apply(context, parent)
}
