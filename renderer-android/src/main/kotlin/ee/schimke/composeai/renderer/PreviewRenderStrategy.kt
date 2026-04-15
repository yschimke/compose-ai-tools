package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Default strategy: reflect the `@Composable` and invoke it inside a Box that
 * paints the preview's configured background. Honours `@PreviewWrapper` by
 * looking up the provider's `Wrap(content)` method.
 */
private object ComposePreviewStrategy : PreviewRenderStrategy {
    @Composable
    override fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int) {
        val clazz = Class.forName(preview.className)
        val composableMethod = clazz.getDeclaredComposableMethod(preview.functionName)
        val bgColor = when {
            preview.params.backgroundColor != 0L -> Color(preview.params.backgroundColor.toInt())
            preview.params.showBackground -> Color.White
            else -> Color.Transparent
        }
        val body: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                composableMethod.invoke(currentComposer, null)
            }
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
