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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Standalone entry point for rendering Compose Desktop previews to PNG.
 *
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile [wrapperClassName] [wrapWidth] [wrapHeight]
 *
 * The optional 9th argument is the FQN of a `PreviewWrapperProvider` (Compose 1.11+);
 * pass an empty string or omit to skip wrapping.
 *
 * Args 10 and 11 are AS-parity wrap flags. When an axis wraps, widthPx/heightPx
 * are treated as a sandbox dimension — the renderer wraps the composable,
 * measures its intrinsic size, and crops the final PNG to that size on the
 * wrapped axis. Defaults to `false` when omitted so older callers keep the
 * full-frame behaviour.
 */
fun main(args: Array<String>) {
    if (args.size < 8) {
        System.err.println("Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile> [wrapperClassName] [wrapWidth] [wrapHeight]")
        exitProcess(1)
    }

    val className = args[0]
    val functionName = args[1]
    val widthPx = args[2].toIntOrNull() ?: run {
        System.err.println("Invalid widthPx: '${args[2]}' (expected integer)")
        exitProcess(1)
    }
    val heightPx = args[3].toIntOrNull() ?: run {
        System.err.println("Invalid heightPx: '${args[3]}' (expected integer)")
        exitProcess(1)
    }
    val density = args[4].toFloatOrNull() ?: run {
        System.err.println("Invalid density: '${args[4]}' (expected float)")
        exitProcess(1)
    }
    val showBackground = args[5].toBoolean()
    val backgroundColor = args[6].toLongOrNull() ?: run {
        System.err.println("Invalid backgroundColor: '${args[6]}' (expected long)")
        exitProcess(1)
    }
    val outputFile = File(args[7])
    val wrapperClassName = args.getOrNull(8)?.takeIf { it.isNotBlank() }
    val wrapWidth = args.getOrNull(9)?.toBoolean() ?: false
    val wrapHeight = args.getOrNull(10)?.toBoolean() ?: false

    try {
        renderPreview(
            className, functionName, widthPx, heightPx, density,
            showBackground, backgroundColor, outputFile, wrapperClassName,
            wrapWidth, wrapHeight,
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
    wrapWidth: Boolean,
    wrapHeight: Boolean,
) {
    val clazz = Class.forName(className)
    val composableMethod = clazz.getDeclaredComposableMethod(functionName)

    val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = Density(density),
    )

    // Measured content size in pixels, captured from the wrapping Box via
    // onGloballyPositioned. Only read when at least one axis wraps.
    var measured: IntSize? = null

    scene.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            val bgColor = when {
                backgroundColor != 0L -> Color(backgroundColor.toInt())
                showBackground -> Color.White
                else -> Color.Transparent
            }
            val body: @Composable () -> Unit = {
                if (wrapWidth || wrapHeight) {
                    // AS-parity wrap: measure the composable with unbounded
                    // constraints on wrapped axes (keep the sandbox constraint
                    // on fixed axes), capture the child's pixel size, then
                    // size the outer Box to exactly that. The .layout modifier
                    // lets us both observe and bound the child's size in a
                    // single measurement pass — more reliable under
                    // ImageComposeScene than onGloballyPositioned, which is
                    // tied to a post-layout effect pass the scene doesn't
                    // always flush.
                    // Bounded sandbox constraints (not Infinity) — matches
                    // Android Studio's preview pane. `fillMaxWidth` / LazyColumn
                    // / etc. require bounded constraints; they'd throw from
                    // `InlineClassHelper` under an Infinity max. Small
                    // composables (`Modifier.size(100.dp)`) still measure at
                    // their intrinsic size and get cropped to that below;
                    // `fillMax*` composables measure at the sandbox size and
                    // no crop happens on that axis.
                    Box(
                        modifier = Modifier
                            .layout { measurable, constraints ->
                                // Relax the min constraint on wrapped axes so
                                // small composables can shrink below the
                                // sandbox; keep the max bounded (the parent's
                                // maxWidth/maxHeight) so `fillMax*` / LazyColumn
                                // still have a finite viewport.
                                val wrappedConstraints = Constraints(
                                    minWidth = if (wrapWidth) 0 else constraints.minWidth,
                                    maxWidth = constraints.maxWidth,
                                    minHeight = if (wrapHeight) 0 else constraints.minHeight,
                                    maxHeight = constraints.maxHeight,
                                )
                                val placeable = measurable.measure(wrappedConstraints)
                                measured = IntSize(placeable.width, placeable.height)
                                layout(placeable.width, placeable.height) {
                                    placeable.place(0, 0)
                                }
                            }
                            .background(bgColor),
                    ) {
                        InvokeComposable(composableMethod, null)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                        InvokeComposable(composableMethod, null)
                    }
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

    // Crop to the measured content bounds on wrapped axes. `measured` is
    // populated during the Modifier.layout measure pass in the wrap branch
    // above — if it somehow wasn't set (shouldn't happen, but defensive),
    // fall back to the sandbox dimensions and write the uncropped PNG.
    if ((wrapWidth || wrapHeight) && measured != null) {
        val m = measured!!
        val cropW = (if (wrapWidth) m.width else widthPx).coerceIn(1, widthPx)
        val cropH = (if (wrapHeight) m.height else heightPx).coerceIn(1, heightPx)
        val decoded = ByteArrayInputStream(pngData.bytes).use { ImageIO.read(it) }
        if (decoded != null && (cropW < decoded.width || cropH < decoded.height)) {
            val sub = decoded.getSubimage(
                0,
                0,
                cropW.coerceAtMost(decoded.width),
                cropH.coerceAtMost(decoded.height),
            )
            ImageIO.write(sub, "PNG", outputFile)
        } else {
            outputFile.writeBytes(pngData.bytes)
        }
    } else {
        outputFile.writeBytes(pngData.bytes)
    }

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
