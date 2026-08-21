package ee.schimke.composeai.daemon

import java.io.File

/**
 * Crops a captured frame PNG to the composable's measured intrinsic size on wrapped axes.
 *
 * Robolectric always draws into the whole activity window, so a wrap-content preview lands top-left
 * inside a sandbox frame that is usually much larger than the composable (a Wear component sticker
 * measures ~165×136 px inside a 454×454 round watch face). Both capture paths therefore have to
 * crop back to what was measured, and both must crop the **same** way or the static snapshot and
 * the live stream of one preview disagree on their frame size — which is exactly what happened
 * while only [RenderEngine] did it and the held/interactive loop in [RobolectricHost] streamed the
 * raw window.
 *
 * Uses `javax.imageio` rather than a Robolectric `Bitmap` shadow: `captureRoboImage` has already
 * written a standard PNG, so this is plain JVM image work on either side of the sandbox boundary.
 * Mirrors the standalone renderer's `cropPngTopLeft` and the desktop daemon's `cropToMeasured`.
 */
internal object WrappedFrameCrop {

  /**
   * Crops [file] in place. The non-wrapped axis keeps its captured pixel extent; a wrapped axis
   * whose measured size wasn't recorded (`<= 0`) or already fills the window is left unchanged
   * (`fillMax*` composables). A missing/unreadable file, or a crop that would be a no-op, returns
   * without touching anything.
   */
  fun cropTopLeft(
    file: File,
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    measuredWidth: Int,
    measuredHeight: Int,
  ) {
    if (!wrapWidth && !wrapHeight) return
    if (!file.exists()) return
    // Decide from the PNG header (a few dozen bytes) whether there is anything to crop, before
    // paying for a decode. On a wrapped preview that already fills the window — `fillMax*` content,
    // and every frame of it — the old shape decoded the whole image only to discover the crop was a
    // no-op, once per live frame at the interactive loop's cadence (#4283).
    val captured = headerSize(file)
    if (
      captured != null &&
        !cropChanges(captured, wrapWidth, wrapHeight, measuredWidth, measuredHeight)
    )
      return
    val original = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return
    val cropW =
      if (wrapWidth && measuredWidth in 1 until original.width) measuredWidth else original.width
    val cropH =
      if (wrapHeight && measuredHeight in 1 until original.height) measuredHeight
      else original.height
    if (cropW >= original.width && cropH >= original.height) return
    val cropped = original.getSubimage(0, 0, cropW, cropH)
    runCatching { javax.imageio.ImageIO.write(cropped, "PNG", file) }
  }

  /** True when cropping a [captured]-sized frame to the measured size would change any pixel. */
  private fun cropChanges(
    captured: Pair<Int, Int>,
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    measuredWidth: Int,
    measuredHeight: Int,
  ): Boolean {
    val (width, height) = captured
    val narrows = wrapWidth && measuredWidth in 1 until width
    val shortens = wrapHeight && measuredHeight in 1 until height
    return narrows || shortens
  }

  /**
   * The image's pixel size read from its header alone — `ImageReader.getWidth`/`getHeight` parse
   * the IHDR without decoding any pixel data. Null when the file can't be read as an image, which
   * sends the caller down the decode path so a malformed-header case behaves exactly as it did
   * before this fast path existed.
   */
  private fun headerSize(file: File): Pair<Int, Int>? = runCatching {
    val stream = javax.imageio.ImageIO.createImageInputStream(file) ?: return null
    stream.use {
      val readers = javax.imageio.ImageIO.getImageReaders(it)
      if (!readers.hasNext()) return null
      val reader = readers.next()
      try {
        reader.input = it
        reader.getWidth(0) to reader.getHeight(0)
      } finally {
        reader.dispose()
      }
    }
  }
    .getOrNull()
}
