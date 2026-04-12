package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
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
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile
 */
fun main(args: Array<String>) {
    if (args.size < 8) {
        System.err.println("Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile>")
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

    try {
        renderPreview(className, functionName, widthPx, heightPx, density, showBackground, backgroundColor, outputFile)
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
            Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                InvokeComposable(composableMethod, null)
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
    composableMethod: androidx.compose.runtime.reflect.ComposableMethod,
    instance: Any?,
) {
    composableMethod.invoke(currentComposer, instance)
}
