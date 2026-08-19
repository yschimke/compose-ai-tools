package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.motion.ApngEncoder
import ee.schimke.composeai.motion.apngDelayFor
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
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
  private val padArgb: Int = 0,
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

  /**
   * Adds one frame, padded or trimmed to exactly [target]. Nothing is ever scaled.
   *
   * Every frame of a capture must be the same size — an APNG's frames share one `IHDR`, and a GIF's
   * share one logical screen — but a wrap-measured capture does *not* hand them over that way. The
   * captured root is the composable's own bounds, so a composable that changes size mid-recording
   * produces frames that change size with it.
   *
   * The frame's pixels are copied 1:1 to the same coordinates and the remainder is filled with
   * [padArgb], the backdrop the preview already composes against. There is deliberately no
   * resampling anywhere in this path: scaling a frame up to the target would soften the component
   * against its own sharp sibling still, and — worse for what these captures document — a component
   * that expands would appear to *shrink* as the canvas grew around it, which is the opposite of
   * the motion being recorded.
   *
   * A `null` target, or a frame already at it, passes straight through — which is the fixed-frame
   * (device-sized) case, and it costs exactly what it did before any of this existed.
   */
  fun capture(pngBytes: ByteArray, target: IntSize? = null) {
    when (format) {
      MotionFormatKind.APNG -> {
        val file = File(scratch, "frame-%05d.png".format(frameFiles.size))
        file.writeBytes(padOrTrimPngBytes(pngBytes, target, padArgb))
        frameFiles += file
      }
      MotionFormatKind.GIF -> {
        val decoded =
          ImageIO.read(pngBytes.inputStream())
            ?: error("Motion frame PNG couldn't be decoded back to a BufferedImage")
        frameImages += padOrTrimImage(decoded, target, padArgb)
      }
    }
  }

  /** Drops everything collected so far, including the scratch frames on disk. */
  fun discard() {
    frameFiles.clear()
    frameImages.clear()
    scratch.deleteRecursively()
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
 * Captures the complete Skiko scene, including popup/dialog owners that are not descendants of the
 * preview's main semantics root.
 *
 * Compose Desktop paints those owners into the same scene surface even though `onRoot()` selects
 * only the main owner. Capturing the surface is therefore the lossless source for motion frames;
 * [MotionFrameCollector] still trims it to the measured capture bounds, so ordinary inline previews
 * keep the same dimensions and byte cost they had before popup support.
 */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.captureMotionSurfacePngBytes(): ByteArray =
  captureToImage().toPngBytes()

/**
 * Folds every active semantics owner's window bounds into [tracker]. Popups are separate roots, so
 * the main content's `onMeasured` callback cannot see them. Observing roots after each rendered
 * frame lets [recordMotionCapture] re-record at the union's required size when a gesture opens a
 * menu, tooltip, dialog, or sheet beyond the resting sticker bounds.
 */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.observeMotionRootBounds(tracker: MotionBoundsTracker) {
  for (node in onAllNodes(isRoot()).fetchSemanticsNodes()) {
    val bounds = node.boundsInWindow
    tracker.observe(ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
  }
}

private fun androidx.compose.ui.graphics.ImageBitmap.toPngBytes(): ByteArray {
  val bitmap = this
  val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
  try {
    val pngData = skiaImage.encodePngData() ?: error("Failed to encode motion frame to PNG")
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

/**
 * Resizes a captured frame's PNG bytes to exactly [target], or returns them untouched when they
 * already are.
 *
 * The size check reads the `IHDR` header directly instead of decoding, so a capture whose frames
 * are all already the target size — every fixed-frame preview, and every wrapped one that doesn't
 * change size, which is nearly all of them — never decodes a frame at all.
 */
private fun padOrTrimPngBytes(pngBytes: ByteArray, target: IntSize?, padArgb: Int): ByteArray {
  if (target == null) return pngBytes
  val width = pngIhdrInt(pngBytes, 16)
  val height = pngIhdrInt(pngBytes, 20)
  if (width <= 0 || height <= 0) return pngBytes
  if (width == target.width && height == target.height) return pngBytes
  val decoded =
    ImageIO.read(pngBytes.inputStream())
      ?: error("Motion frame PNG couldn't be decoded back to a BufferedImage")
  val out = ByteArrayOutputStream()
  ImageIO.write(padOrTrimImage(decoded, target, padArgb), "png", out)
  return out.toByteArray()
}

/**
 * [BufferedImage] counterpart of [padOrTrimPngBytes], also used directly by the GIF path's
 * already-decoded frames.
 *
 * The frame is anchored top-left, matching where the single-frame path takes its crop from, so a
 * component that grows does so into the new space rather than appearing to drift within the canvas.
 */
private fun padOrTrimImage(image: BufferedImage, target: IntSize?, padArgb: Int): BufferedImage {
  if (target == null) return image
  val w = target.width.coerceAtLeast(1)
  val h = target.height.coerceAtLeast(1)
  if (w == image.width && h == image.height) return image
  val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  val g = canvas.createGraphics()
  try {
    if (padArgb ushr 24 != 0) {
      g.color = java.awt.Color(padArgb, true)
      g.fillRect(0, 0, w, h)
    }
    // `getSubimage` returns a view sharing the parent's raster; drawing it into the canvas copies
    // the pixels out, so the encoders (and the GIF path, which holds every frame in memory at once)
    // don't pin full-size backing rasters.
    val visible = image.getSubimage(0, 0, minOf(w, image.width), minOf(h, image.height))
    g.drawImage(visible, 0, 0, null)
  } finally {
    g.dispose()
  }
  return canvas
}

/** Big-endian 4-byte read at [at] — used only for the PNG `IHDR` width/height fields. */
private fun pngIhdrInt(bytes: ByteArray, at: Int): Int {
  if (bytes.size < at + 4) return 0
  return ((bytes[at].toInt() and 0xFF) shl 24) or
    ((bytes[at + 1].toInt() and 0xFF) shl 16) or
    ((bytes[at + 2].toInt() and 0xFF) shl 8) or
    (bytes[at + 3].toInt() and 0xFF)
}

/**
 * The largest content size [ComposePreviewContentBox] measured across a whole recording.
 *
 * A static render measures once, so it can crop to *the* intrinsic size. A motion capture has no
 * such single size: a composable that expands mid-recording — a menu opening, a card growing into
 * its detail state, a list revealing an item — is bigger at frame 90 than it was at frame 0, and
 * cropping to the resting measurement would cut the expansion off exactly when it becomes the thing
 * worth looking at. So every measure pass is folded in here and the crop is taken from the maximum.
 */
internal class MotionBoundsTracker {
  var width: Int = 0
    private set

  var height: Int = 0
    private set

  val size: IntSize
    get() = IntSize(width, height)

  fun observe(measuredWidth: Int, measuredHeight: Int) {
    if (measuredWidth > width) width = measuredWidth
    if (measuredHeight > height) height = measuredHeight
  }
}

/**
 * The rect a motion capture's frames are trimmed to: the measured content on a wrapped axis, the
 * requested frame on a fixed one, never larger than the scene that was actually rendered.
 *
 * Mirrors the single-frame path's crop in [DesktopRendererMain] so a component's motion capture and
 * its static sticker come out the same size — which is what lets a viewer show one in place of the
 * other without the card resizing under the reader.
 */
internal fun motionCropSize(
  measured: IntSize,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  widthPx: Int,
  heightPx: Int,
  sceneSize: IntSize,
): IntSize =
  IntSize(
    (if (wrapWidth && measured.width > 0) measured.width else widthPx).coerceIn(1, sceneSize.width),
    (if (wrapHeight && measured.height > 0) measured.height else heightPx).coerceIn(
      1,
      sceneSize.height,
    ),
  )

/**
 * The composition a motion capture records, wrap-measured exactly as the single-frame path measures
 * it.
 *
 * Sharing [ComposePreviewContentBox] with the static render is the point: before this, the motion
 * paths composed into a bare `fillMaxSize` box, so every capture came out the full device sandbox —
 * a 137×84 switch published as a 1050×2100 recording of a switch adrift in empty space.
 */
@Composable
internal fun MotionCaptureRoot(
  rtl: Boolean,
  sceneDensity: Density,
  uiMode: Int,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  backgroundColor: Color,
  sizeBounds: PreviewSizeBounds,
  onMeasured: (width: Int, height: Int) -> Unit,
  wrapperClassName: String?,
  classLoader: ClassLoader?,
  content: @Composable () -> Unit,
) {
  MotionPreviewProviders(rtl = rtl, sceneDensity = sceneDensity, uiMode = uiMode) {
    val body: @Composable () -> Unit = {
      ComposePreviewContentBox(
        wrapWidth = wrapWidth,
        wrapHeight = wrapHeight,
        backgroundColor = backgroundColor,
        sizeBounds = sizeBounds,
        onMeasured = onMeasured,
        content = content,
      )
    }
    if (wrapperClassName != null) {
      InvokeMotionWrappedComposable(wrapperClassName, classLoader, body)
    } else {
      body()
    }
  }
}

/** What one recording pass committed to, and what it turned out to need. */
internal data class MotionPass(val crop: IntSize, val observed: IntSize)

/** How a capture was produced, for the renderer's log line. */
internal data class MotionCaptureResult(
  val frameCount: Int,
  val file: File,
  val crop: IntSize,
  val reRecorded: Boolean,
)

/**
 * Runs [record] once and encodes it — unless the composition grew past the crop that pass committed
 * to, in which case it records a second time at the size the growth actually needed.
 *
 * ### Why a re-record rather than a wider crop
 *
 * The crop is decided after the first frame, because that is the only point at which the resting
 * size is known and every frame from then on has to share it. Growth after that leaves two options,
 * and only one of them keeps the normal case cheap:
 * * crop every frame to the sandbox and trim at the end — correct, but it makes *every* capture pay
 *   for the rare one, since each frame is then encoded at device size before being cut back down;
 * * crop to the resting size immediately and re-record only if that turned out to be too small.
 *
 * This takes the second. A component that never changes size — which is nearly all of them —
 * records once and pays nothing for the machinery; one that expands is recorded twice and keeps its
 * expansion, instead of having it silently clipped at the frame edge.
 *
 * The retry is bounded to a single extra pass by construction: the scene stays sandbox-sized in
 * both passes, so pass one observes the true maximum even while cropping to less than it, and pass
 * two is handed that maximum up front rather than discovering it again.
 */
internal fun recordMotionCapture(
  outputFile: File,
  format: MotionFormatKind,
  frameIntervalMs: Int,
  padArgb: Int = 0,
  record: (collector: MotionFrameCollector, forcedCrop: IntSize?) -> MotionPass,
): MotionCaptureResult {
  val first = MotionFrameCollector(format, outputFile, padArgb)
  val pass = record(first, null)
  val grew = pass.observed.width > pass.crop.width || pass.observed.height > pass.crop.height
  if (!grew) {
    val count = first.frameCount
    return MotionCaptureResult(count, first.encode(frameIntervalMs), pass.crop, reRecorded = false)
  }

  first.discard()
  val retryCrop =
    IntSize(
      maxOf(pass.crop.width, pass.observed.width),
      maxOf(pass.crop.height, pass.observed.height),
    )
  System.err.println(
    "Motion capture on ${outputFile.name}: content grew from ${pass.crop.width}×" +
      "${pass.crop.height} to ${retryCrop.width}×${retryCrop.height} during the recording — " +
      "re-recording at the larger size so the expansion isn't clipped."
  )
  val second = MotionFrameCollector(format, outputFile, padArgb)
  record(second, retryCrop)
  val count = second.frameCount
  return MotionCaptureResult(count, second.encode(frameIntervalMs), retryCrop, reRecorded = true)
}
