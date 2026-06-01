package ee.schimke.composeai.renderer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.renderer.TileRenderer
import androidx.wear.tiles.tooling.preview.TilePreviewData
import ee.schimke.composeai.data.render.IrSidecarChannel
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Renders a `@androidx.wear.tiles.tooling.preview.Preview` function into the
 * surrounding Compose tree via an [AndroidView] hosting the inflated tile View.
 *
 * The preview function is a *non-composable* `fun foo(): TilePreviewData` (or
 * `fun foo(context: Context): TilePreviewData`). We reflect into the Kotlin-compiled
 * `*Kt` class, invoke it, and drive the returned [TilePreviewData] through
 * [TileRenderer.inflateAsync] using a synthetic [RequestBuilders.TileRequest]
 * sized to match the discovered @Preview device.
 *
 * All tile classes are referenced via their compileOnly API surface — the
 * consumer module brings them at runtime. Modules without tile deps never set
 * `kind = TILE` during discovery, so this code is only ever invoked when the
 * classes are actually present.
 */
@Composable
fun TilePreviewComposable(
    className: String,
    functionName: String,
    widthDp: Int,
    heightDp: Int,
    device: String? = null,
    /**
     * Classloader used to resolve [className]. `null` defers to the caller-thread context
     * classloader (the standalone renderer path's user classes share the test classpath, so
     * `Class.forName(name)` finds them). The daemon path passes the per-render child loader so
     * tile previews resolve against freshly-recompiled user bytecode after every save instead of
     * the parent loader's stale copy — see [RenderEngine][ee.schimke.composeai.daemon.RenderEngine].
     */
    classLoader: ClassLoader? = null,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val parent = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Tiles expect to render onto a dark watchface substrate. Without
                // an explicit background the inflated ProtoLayout sits on a
                // transparent parent which flattens to white in the captured
                // PNG — text that's white-on-expected-black disappears. Paint
                // opaque black here to match the runtime appearance; consumers
                // that want a non-black frame can still layer on top inside the
                // tile itself.
                setBackgroundColor(Color.BLACK)
            }
            renderTileInto(context, className, functionName, widthDp, heightDp, device, classLoader, parent)
            parent
        },
    )
}

private fun renderTileInto(
    context: Context,
    className: String,
    functionName: String,
    widthDp: Int,
    heightDp: Int,
    device: String?,
    classLoader: ClassLoader?,
    parent: FrameLayout,
) {
    val data = invokeTilePreviewFunction(context, className, functionName, classLoader)

    val deviceParams = buildDeviceParameters(widthDp, heightDp, device)

    val tileRequest = RequestBuilders.TileRequest.Builder()
        .setDeviceConfiguration(deviceParams)
        .build()
    val tile: TileBuilders.Tile = data.onTileRequest(tileRequest)

    val resourcesRequest = RequestBuilders.ResourcesRequest.Builder()
        .setVersion(tile.resourcesVersion.ifEmpty { "1" })
        .setDeviceConfiguration(deviceParams)
        .build()
    val resources = data.onTileResourceRequest(resourcesRequest)

    val layout = tile.tileTimeline
        ?.timelineEntries
        ?.firstOrNull()
        ?.layout
        ?: error("TilePreview '$functionName' produced no layout (empty timeline)")

    // Capture the protolayout IR (the Layout + Resources protos) so a bundle can carry and replay
    // it via TileRenderer without this tile function's bytecode. Serialised here through the
    // generated protos — `toProto().toByteArray()` — so the :data-render-core channel stays
    // protolayout-free; the render harness drains the channel and writes the
    // renders/<stem>.tilelayout / .tileresources sidecars BundlePreviewTask.resolvePreviewIr packs.
    // `Resources` has no direct byte serializer (only toProto), so both go through the proto for
    // symmetry. Best-effort — an IR-capture hiccup must never fail the render.
    runCatching {
        IrSidecarChannel.offer(
            format = IrSidecarChannel.FORMAT_PROTOLAYOUT,
            bytes = layout.toProto().toByteArray(),
            resourcesBytes = resources.toProto().toByteArray(),
        )
    }

    // Inline executor — Robolectric has the main looper paused, so posting
    // to a background thread and awaiting back on main would deadlock. Inflating
    // on the caller thread completes before the future leaves the method.
    //
    // `TileRenderer` builds its default `ProtoLayoutTheme` from
    // `R.style.ProtoLayoutBaseTheme` via `context.getResources()`. That style
    // ships with `protolayout-renderer`'s AAR — it resolves correctly here
    // because the Gradle plugin puts AGP's `unit_test_config_directory` on
    // the renderer test classpath, so Robolectric loads the merged resource
    // APK containing every AAR's resources (including the tile theme). Without
    // that, every `getIdentifier` returns 0 and `TileRenderer` crashes with
    // `Unknown resource value type 0`.
    val renderer = TileRenderer(context, Runnable::run) { _ -> /* no-op loader */ }
    val view = renderer.inflateAsync(layout, resources, parent)
        .get(10, TimeUnit.SECONDS)
        ?: error("TileRenderer returned no view for preview '$functionName'")

    // Tile inflation defaults to WRAP_CONTENT, which collapses against an
    // AndroidView that's still measuring. Mirror `TileServiceViewAdapter`:
    // centre the inflated tile and give it explicit MATCH_PARENT layout.
    (view.layoutParams as? FrameLayout.LayoutParams)?.apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = ViewGroup.LayoutParams.MATCH_PARENT
        gravity = Gravity.CENTER
    }
}

private fun invokeTilePreviewFunction(
    context: Context,
    className: String,
    functionName: String,
    classLoader: ClassLoader?,
): TilePreviewData {
    val method = findTilePreviewMethod(className, functionName, classLoader)
    method.isAccessible = true
    val result = when (method.parameterTypes.size) {
        0 -> method.invoke(null)
        1 -> method.invoke(null, context)
        else -> error(
            "TilePreview '$functionName' has unsupported signature; " +
                "expected 0 or 1 (Context) parameters, found ${method.parameterTypes.size}"
        )
    }
    return result as? TilePreviewData
        ?: error("TilePreview '$functionName' did not return TilePreviewData")
}

/**
 * Resolves the static JVM method for a top-level tile preview function. The
 * Kotlin compiler places top-level functions on a synthetic `${File}Kt` class
 * (matching the `className` our discovery records). We prefer an overload that
 * takes a `Context` if present, falling back to a no-arg overload.
 *
 * [classLoader] threads through the daemon's per-render child loader so a tile preview resolves
 * against freshly-recompiled user bytecode after every save; `null` falls back to the
 * default `Class.forName(name)` behaviour (caller-thread context classloader), which is what the
 * standalone renderer needs.
 */
private fun findTilePreviewMethod(className: String, functionName: String, classLoader: ClassLoader?): Method {
    val cls = if (classLoader != null) Class.forName(className, true, classLoader) else Class.forName(className)
    val candidates = cls.declaredMethods.filter { it.name == functionName }
    if (candidates.isEmpty()) {
        error("No method '$functionName' on '$className'")
    }
    return candidates.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java }
        ?: candidates.firstOrNull { it.parameterTypes.isEmpty() }
        ?: error(
            "TilePreview '$functionName' on '$className' has no supported overload " +
                "(expected no-arg or single Context parameter)"
        )
}

private fun buildDeviceParameters(widthDp: Int, heightDp: Int, device: String?): DeviceParameters {
    val isRound = isRoundDevice(device)
    return DeviceParameters.Builder()
        .setScreenWidthDp(widthDp)
        .setScreenHeightDp(heightDp)
        .setScreenDensity(2.0f)
        .setScreenShape(
            if (isRound) DeviceParametersBuilders.SCREEN_SHAPE_ROUND
            else DeviceParametersBuilders.SCREEN_SHAPE_RECT,
        )
        .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
        .build()
}
