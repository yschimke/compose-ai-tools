package ee.schimke.composeai.renderer

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.reflect.Method

/**
 * Renders a `@NotificationPreview` function — `(Context) -> Notification` — into the surrounding
 * Compose tree via an [AndroidView] hosting the inflated `RemoteViews`. Mirrors the structure of
 * [TilePreviewComposable]: reflect the user's function, invoke it, hand the result to a platform
 * inflater, host the resulting View.
 *
 * Inflation path uses `Notification.Builder.recoverBuilder(context, notification)` to get back a
 * platform builder, then asks it for `createBigContentView()` (expanded heads-up / shade content)
 * and falls back to `createContentView()` for collapsed-only notifications. This is the AOSP
 * visual — the SystemUI chrome a Pixel / OEM device draws on top is not reproducible inside
 * Robolectric. See issue #1249 for the full design.
 */
@Composable
fun NotificationPreviewComposable(
    className: String,
    functionName: String,
    /**
     * Classloader used to resolve [className]. `null` defers to the caller-thread context
     * classloader (the standalone renderer path's user classes share the test classpath). Same
     * shape as [TilePreviewComposable] for the daemon path's per-render child loader.
     */
    classLoader: ClassLoader? = null,
    /**
     * Preview id used as the filename stem for the structured-fields JSON sidecar
     * (`<outputDir>/../data/notifications/<id>.notification.json`). `null` skips the sidecar
     * write — kept optional so call sites that don't care about the sidecar (a future doc
     * snippet, a quick test) can pass through unchanged. Both call sites the renderer / daemon
     * use today pass the manifest's preview id.
     */
    previewId: String? = null,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        factory = { ctx ->
            val parent = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
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
    val notification = invokeNotificationPreviewFunction(context, className, functionName, classLoader)
    val view = inflateNotificationView(context, notification, parent)
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
    val result = when (method.parameterTypes.size) {
        0 -> method.invoke(null)
        1 -> method.invoke(null, context)
        else -> error(
            "NotificationPreview '$functionName' has unsupported signature; " +
                "expected 0 or 1 (Context) parameters, found ${method.parameterTypes.size}",
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
    val cls = if (classLoader != null) Class.forName(className, true, classLoader) else Class.forName(className)
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
                "(expected no-arg or single Context parameter)",
        )
}

/**
 * Builds the expanded notification View. Tries `createBigContentView()` (the heads-up / expanded
 * layout used when the notification carries a `setStyle(...)`), falling back to the standard
 * `createContentView()` for collapsed-only notifications. Returns `null` if neither path
 * produces a `RemoteViews` — caller treats that as a render error.
 */
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
