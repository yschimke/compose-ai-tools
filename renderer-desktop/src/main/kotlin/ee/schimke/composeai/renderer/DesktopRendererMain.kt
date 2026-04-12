package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone entry point for rendering Compose Desktop previews to PNG.
 *
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile [wrapperClassName]
 *
 * The optional 9th argument is the FQN of a `PreviewWrapperProvider` (Compose 1.11+);
 * pass an empty string or omit to skip wrapping.
 */
fun main(args: Array<String>) {
    if (args.size < 8) {
        System.err.println("Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile> [wrapperClassName]")
        exitProcess(1)
    }

    val className = args[0]
    val functionName = args[1]
    val widthPx = args[2].toInt()
    val heightPx = args[3].toInt()
    val density = args[4].toFloat()
    val showBackground = args[5].toBoolean()
    val backgroundColor = args[6].toLong()
    val outputFile = File(args[7])
    val wrapperClassName = args.getOrNull(8)?.takeIf { it.isNotBlank() }

    try {
        renderPreview(
            className, functionName, widthPx, heightPx, density,
            showBackground, backgroundColor, outputFile, wrapperClassName,
        )
    } catch (e: Exception) {
        System.err.println("Render failed for $className.$functionName: ${e.message}")
        e.printStackTrace()
        exitProcess(2)
    }
}

private fun renderPreview(
    className: String,
    functionName: String,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    showBackground: Boolean,
    backgroundColor: Long,
    outputFile: File,
    wrapperClassName: String?,
) {
    val clazz = Class.forName(className)
    val composableMethod = clazz.getDeclaredComposableMethod(functionName)

    val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = Density(density),
    )

    scene.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            val bgColor = when {
                backgroundColor != 0L -> Color(backgroundColor.toInt())
                showBackground -> Color.White
                else -> Color.Transparent
            }
            val body: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                    InvokeComposable(composableMethod, null)
                }
            }
            // `@PreviewWrapper(Provider::class)` — instantiate the provider reflectively
            // so the renderer stays compatible with apps on stable Compose (no
            // `PreviewWrapperProvider` on classpath).
            if (wrapperClassName != null) {
                InvokeWrappedComposable(wrapperClassName, body)
            } else {
                body()
            }
        }
    }

    // Render two frames for animations/effects to settle
    scene.render()
    val image = scene.render()

    val pngData = image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("Failed to encode image to PNG")

    outputFile.parentFile?.mkdirs()
    outputFile.writeBytes(pngData.bytes)

    scene.close()
}

@Composable
private fun InvokeComposable(
    composableMethod: ComposableMethod,
    instance: Any?,
) {
    composableMethod.invoke(currentComposer, instance)
}

/**
 * Reflectively instantiates the `PreviewWrapperProvider` identified by [wrapperFqn]
 * and invokes its `Wrap(content)` composable around [body].
 *
 * See [RobolectricRenderTest.resolveWrapper] — same lookup strategy, same caveats.
 */
@Composable
private fun InvokeWrappedComposable(
    wrapperFqn: String,
    body: @Composable () -> Unit,
) {
    val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
    resolved.first.invoke(currentComposer, resolved.second, body)
}

private fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
    val cls = Class.forName(wrapperFqn)
    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
    // Wrap(Function2, Composer, int) at the bytecode level.
    val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
    return method to instance
}
