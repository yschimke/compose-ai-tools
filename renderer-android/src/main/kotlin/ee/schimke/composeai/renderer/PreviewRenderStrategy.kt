package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.reflect.getDeclaredComposableMethod

/**
 * Produces the composition body for a single [RenderPreviewEntry]. Selection
 * happens through [strategyFor] — driven by the [PreviewKind] recorded at
 * discovery time.
 *
 * Each strategy owns its own reflection + framing logic so the main Robolectric
 * pipeline stays oblivious to whether it's driving a @Composable or a tile.
 */
internal interface PreviewRenderStrategy {
    @Composable
    fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int)
}

private val STRATEGIES: Map<PreviewKind, PreviewRenderStrategy> = mapOf(
    PreviewKind.COMPOSE to ComposePreviewStrategy,
    PreviewKind.TILE to TilePreviewStrategy,
)

internal fun strategyFor(kind: PreviewKind): PreviewRenderStrategy =
    STRATEGIES[kind] ?: error("No render strategy registered for PreviewKind.$kind")

/**
 * Default strategy: reflect the `@Composable` and invoke it through the
 * Composer. Honours `@PreviewWrapper` by looking up the provider's
 * `Wrap(content)` method.
 *
 * No `Box(Modifier.fillMaxSize().background(bgColor)) { ... }` wrapper —
 * [RobolectricRenderTestBase.renderDefault] paints the background on the
 * activity window before `setContent`, so we don't need to emit layout-node
 * bytecode (`ComposeUiNode.setCompositeKeyHash` etc.) here. That's what
 * keeps the renderer runnable against older compose-ui BOMs (see the
 * commentary in `renderDefault` for the full compat story).
 */
private object ComposePreviewStrategy : PreviewRenderStrategy {
    @Composable
    override fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int) {
        val clazz = Class.forName(preview.className)
        val composableMethod = clazz.getDeclaredComposableMethod(preview.functionName)
        // Top-level `@Preview` functions compile into static methods on the
        // file's synthetic `FooKt` class, so `receiver = null` works. Google's
        // `com.android.compose.screenshot` tool (and Paparazzi-style tests)
        // idiomatically wrap previews in a regular `class ScreenshotTest { ... }`
        // — `SessionDetailsPreview` is then an instance method and invoking
        // with a null receiver throws `NullPointerException: Cannot invoke
        // "Object.getClass()" because "obj" is null` inside
        // `ComposableMethod.invoke`. Mirror how Compose tooling's
        // `ComposeViewAdapter` resolves the receiver: prefer the Kotlin
        // `object` singleton (INSTANCE), else instantiate via the nullary
        // constructor, else fall back to null for static methods.
        val receiver = resolvePreviewReceiver(clazz)
        val body: @Composable () -> Unit = {
            composableMethod.invoke(currentComposer, receiver)
        }
        val wrapperFqn = preview.params.wrapperClassName
        if (wrapperFqn != null) {
            val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
            resolved.first.invoke(currentComposer, resolved.second, body)
        } else {
            body()
        }
    }
}

/**
 * Resolves the JVM receiver instance to pass into
 * `ComposableMethod.invoke(composer, receiver, …)` for a preview function
 * declared on [clazz]. Extracted as a top-level internal function so
 * [PreviewReceiverTest] can exercise it without standing up a Robolectric
 * sandbox. Returns:
 *  - the `INSTANCE` field of a Kotlin `object` (singleton receiver);
 *  - a fresh nullary-ctor instance for regular classes (Google's
 *    `com.android.compose.screenshot` style: `class ScreenshotTest { @Preview fun …}`);
 *  - `null` for top-level functions — those compile into static methods on
 *    the file's synthetic `FooKt` class, and `ComposableMethod.invoke`
 *    accepts a null receiver for static methods.
 *
 * Matches how Compose tooling's `ComposeViewAdapter` resolves receivers in
 * the Studio preview pane.
 */
internal fun resolvePreviewReceiver(clazz: Class<*>): Any? {
    runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
    // Regular class: instantiate via nullary ctor. `setAccessible(true)` so
    // private/internal classes work too (Google's screenshotTest classes
    // are typically package-private or internal).
    return runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    }.getOrNull()
}

/**
 * Tiles strategy: invoke the non-composable preview function, drive the
 * returned [androidx.wear.tiles.tooling.preview.TilePreviewData] through
 * `TileRenderer`, and host the inflated View via an `AndroidView`. See
 * [TilePreviewComposable] for the heavy lifting.
 */
private object TilePreviewStrategy : PreviewRenderStrategy {
    @Composable
    override fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int) {
        TilePreviewComposable(preview, widthDp, heightDp)
    }
}
