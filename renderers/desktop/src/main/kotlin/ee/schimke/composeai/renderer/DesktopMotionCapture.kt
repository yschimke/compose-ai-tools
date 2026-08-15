package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * Shared machinery for the two **motion captures** on this backend — the self-driven
 * [renderAnimatedPreview] and the pointer-driven [renderInteractionPreview].
 *
 * The two differ only in what makes the pixels move; everything around that is identical (compose
 * the preview out of inspection mode, sample the root on a paused clock, encode the frame
 * sequence), so it lives here once. Keeping it shared is not just tidiness: a capture's frames must
 * all share one PNG header for APNG to stitch them, and the surest way to guarantee that is for
 * both paths to capture through the same function.
 */

/**
 * Accumulates captured frames and encodes them in the requested container.
 *
 * Frames arrive as encoded PNG bytes rather than as decoded bitmaps because that is what the APNG
 * path actually wants — it copies each frame's `IDAT` through verbatim, so decoding to a
 * `BufferedImage` and re-encoding would be work done only to be undone, and would risk re-encoding
 * the frames to headers that no longer match.
 */
internal class MotionFrameCollector(
  private val format: MotionFormatKind,
  private val outputFile: File,
) {
  /** APNG: PNG files on disk, which is what the encoder stitches. */
  private val frameFiles = mutableListOf<File>()
  /** GIF: decoded bitmaps, which is what the GIF encoder quantises. */
  private val frameImages = mutableListOf<BufferedImage>()
  private val scratch: File by lazy {
    File(outputFile.parentFile, ".${outputFile.nameWithoutExtension}-frames").apply { mkdirs() }
  }

  val frameCount: Int
    get() = if (format == MotionFormatKind.APNG) frameFiles.size else frameImages.size

  fun capture(pngBytes: ByteArray) {
    when (format) {
      MotionFormatKind.APNG -> {
        val file = File(scratch, "frame-%05d.png".format(frameFiles.size))
        file.writeBytes(pngBytes)
        frameFiles += file
      }
      MotionFormatKind.GIF ->
        frameImages +=
          (ImageIO.read(pngBytes.inputStream())
            ?: error("Motion frame PNG couldn't be decoded back to a BufferedImage"))
    }
  }

  /** Encodes the collected frames to [outputFile] and returns it. */
  fun encode(frameIntervalMs: Int): File {
    check(frameCount > 0) { "Motion capture for ${outputFile.name} collected no frames" }
    outputFile.parentFile?.mkdirs()
    return when (format) {
      MotionFormatKind.APNG -> {
        val (num, den) = apngDelayFor(frameIntervalMs)
        try {
          ApngEncoder.encodeFromPngFrames(
            frames = frameFiles,
            delayNumerator = num,
            delayDenominator = den,
            loopCount = 0,
            out = outputFile,
          )
        } finally {
          scratch.deleteRecursively()
        }
        outputFile
      }
      MotionFormatKind.GIF ->
        ScrollGifEncoder.encode(
          frames = frameImages,
          outputFile = outputFile,
          frameDelaysMs = IntArray(frameImages.size) { frameIntervalMs },
        )
          ?: throw IllegalStateException(
            "Motion capture: GIF encoder declined for ${outputFile.name}"
          )
    }
  }
}

/**
 * The APNG frame delay for a capture authored at [frameIntervalMs] milliseconds per frame, as the
 * exact `numerator / denominator` fraction of a second that APNG stores.
 *
 * The canonical frame rates are snapped to their exact rational form rather than being carried as
 * `ms/1000`, because the millisecond is an *authoring* unit and the rate is what the reader sees.
 * 60fps is the case that forces this: it is 16.67ms, which no integer number of milliseconds names,
 * so a literal `16/1000` plays at 62.5fps and `17/1000` at 58.8fps. `1/60` is what the author meant
 * and what APNG can hold — and holding it is the reason a 60fps capture is worth having here at
 * all, since a GIF's 1/100s delay quantisation cannot express any of these rates.
 *
 * Anything else is carried literally as `ms/1000`, which is exact for every rate a millisecond can
 * name.
 */
internal fun apngDelayFor(frameIntervalMs: Int): Pair<Short, Short> =
  when (frameIntervalMs) {
    16,
    17 -> 1.toShort() to 60.toShort() // 60fps
    20 -> 1.toShort() to 50.toShort() // 50fps
    33,
    34 -> 1.toShort() to 30.toShort() // 30fps
    40 -> 1.toShort() to 25.toShort() // 25fps
    else -> frameIntervalMs.toShort() to 1000.toShort()
  }

/**
 * Decodes a `MotionFormat` name off the renderer's positional argv, falling back to [default] for
 * an absent, blank or unrecognised value — a plugin newer than this renderer must degrade to a
 * format this renderer can write rather than failing the capture over a name.
 */
internal fun motionFormatArg(raw: String?, default: MotionFormatKind): MotionFormatKind =
  raw
    ?.takeIf { it.isNotBlank() }
    ?.let { name ->
      MotionFormatKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    } ?: default

/**
 * The composition locals every motion capture composes under.
 *
 * `LocalInspectionMode = false` is the load-bearing one. Components short-circuit animations when
 * they detect inspection mode, and catalog stickers commonly go further and freeze their own state
 * there so that a baked PNG cannot depend on having been tapped. A motion capture wants the
 * opposite of both, so it composes in the same configuration a live session does.
 *
 * [uiMode] carries `@Preview(uiMode = …)`'s night bit into `LocalSystemTheme`, which is what
 * Compose Desktop's `isSystemInDarkTheme()` reads. Without it a dark `@Preview` composes its
 * content in light colours — the single-frame path has provided this since the cover PNG was found
 * disagreeing with the daemon over exactly this, but the animation path never did, so until this
 * became shared code a dark `@AnimatedPreview` on desktop silently captured the light theme.
 */
// `LocalSystemTheme` is `@InternalComposeUiApi` — opted into here for the same reason the
// single-frame path does at `DesktopRendererMain`: it is the only seam Compose Desktop's
// `isSystemInDarkTheme()` reads, so a renderer that must honour `@Preview(uiMode = …)` has no
// public alternative.
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
@Composable
internal fun MotionPreviewProviders(
  rtl: Boolean,
  sceneDensity: Density,
  uiMode: Int,
  content: @Composable () -> Unit,
) {
  val systemTheme = systemThemeFromUiMode(uiMode)
  if (rtl) {
    CompositionLocalProvider(
      LocalInspectionMode provides false,
      LocalDensity provides sceneDensity,
      androidx.compose.ui.LocalSystemTheme provides systemTheme,
      androidx.compose.ui.platform.LocalLayoutDirection provides
        androidx.compose.ui.unit.LayoutDirection.Rtl,
      content = content,
    )
  } else {
    CompositionLocalProvider(
      LocalInspectionMode provides false,
      LocalDensity provides sceneDensity,
      androidx.compose.ui.LocalSystemTheme provides systemTheme,
      content = content,
    )
  }
}

/**
 * Captures the rendered root as encoded PNG bytes.
 *
 * Every frame of one capture comes back with the same width, height and colour type because the
 * scene is fixed-size — which is exactly the invariant [ApngEncoder] requires of its inputs.
 */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.captureRootPngBytes(): ByteArray {
  val bitmap = onRoot().captureToImage()
  val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
  try {
    val pngData =
      skiaImage.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode motion frame to PNG")
    try {
      return pngData.bytes
    } finally {
      pngData.close()
    }
  } finally {
    skiaImage.close()
  }
}

/**
 * Resolves the preview function, keeping `private fun` previews renderable (`openForInvoke`,
 * issue #3873) and matching an overload by its `@PreviewParameter` argument count.
 */
internal fun resolveMotionComposable(
  className: String,
  functionName: String,
  previewArgs: List<Any?>,
  classLoader: ClassLoader?,
): ComposableMethod {
  val clazz =
    if (classLoader != null) Class.forName(className, true, classLoader)
    else Class.forName(className)
  return (if (previewArgs.isEmpty()) clazz.getDeclaredComposableMethod(functionName)
    else findMotionComposableMethod(clazz, functionName, previewArgs))
    .openForInvoke()
}

private fun findMotionComposableMethod(
  clazz: Class<*>,
  name: String,
  previewArgs: List<Any?>,
): ComposableMethod {
  val argCount = previewArgs.size
  val candidate =
    clazz.declaredMethods.firstOrNull { m -> m.name == name && m.parameterCount >= argCount + 2 }
      ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking $argCount parameter(s)"
      )
  val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
  return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}

@Composable
internal fun InvokeMotionComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

@Composable
internal fun InvokeMotionWrappedComposable(
  wrapperFqn: String,
  classLoader: ClassLoader?,
  body: @Composable () -> Unit,
) {
  val resolved =
    androidx.compose.runtime.remember(wrapperFqn, classLoader) {
      val cls =
        if (classLoader != null) Class.forName(wrapperFqn, true, classLoader)
        else Class.forName(wrapperFqn)
      val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
      val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java).openForInvoke()
      method to instance
    }
  resolved.first.invoke(currentComposer, resolved.second, body)
}
