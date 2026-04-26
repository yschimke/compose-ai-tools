package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android XML resource renderer. Reads the `resources.json` written by `discoverAndroidResources`
 * (path via `composeai.resources.manifest`), iterates every [RenderResourceCapture], and writes a
 * PNG / GIF to the directory pointed at by `composeai.resources.outputDir`.
 *
 * Supported types: `VECTOR` (PNG), `ADAPTIVE_ICON` (PNG with shape mask + LEGACY fallback),
 * `ANIMATED_VECTOR` (GIF).
 *
 * Robolectric setup mirrors [RobolectricRenderTest]'s pin: SDK 35, NATIVE graphics, paused looper,
 * hardware pixel-copy. The Gradle task wiring (`renderAndroidResources`) sets the same system
 * properties on this Test task as on `renderPreviews`, so both paths share Robolectric's runtime
 * configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourcePreviewRenderTest {

  @Test
  fun renderResources() {
    val manifestPath =
      System.getProperty("composeai.resources.manifest")
        ?: error("composeai.resources.manifest system property not set")
    val outputDirPath =
      System.getProperty("composeai.resources.outputDir")
        ?: error("composeai.resources.outputDir system property not set")

    val manifest = json.decodeFromString<RenderResourceManifest>(File(manifestPath).readText())
    val outputRoot = File(outputDirPath)
    outputRoot.mkdirs()

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val packageName = context.packageName

    var rendered = 0
    var missing = 0
    for (preview in manifest.resources) {
      val (resType, resName) = preview.id.split('/', limit = 2).let { it[0] to it[1] }
      val resId = context.resources.getIdentifier(resName, resType, packageName)
      if (resId == 0) {
        System.err.println(
          "compose-preview: resource ${preview.id} not found on the consumer's R class " +
            "(package=$packageName); skipping ${preview.captures.size} capture(s)"
        )
        missing += preview.captures.size
        continue
      }

      for (capture in preview.captures) {
        val qualifiers = capture.variant?.qualifiers
        if (!qualifiers.isNullOrEmpty()) {
          RuntimeEnvironment.setQualifiers(qualifiers)
        }
        val drawable: Drawable? = ContextCompat.getDrawable(context, resId)
        if (drawable == null) {
          System.err.println(
            "compose-preview: ContextCompat.getDrawable returned null for ${preview.id} " +
              "at qualifiers=${qualifiers ?: "<default>"}; skipping capture"
          )
          missing++
          continue
        }
        val outFile = resolveOutputPath(outputRoot, capture.renderOutput)
        outFile.parentFile?.mkdirs()

        when (preview.type) {
          RenderResourceType.VECTOR -> {
            val bitmap = renderStaticDrawable(drawable)
            try {
              outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
              bitmap.recycle()
            }
            rendered++
          }
          RenderResourceType.ADAPTIVE_ICON -> {
            val shape = capture.variant?.shape
            if (shape == null) {
              System.err.println(
                "compose-preview: adaptive icon ${preview.id} capture has no shape; skipping"
              )
              missing++
              continue
            }
            val adaptive = drawable as? AdaptiveIconDrawable
            if (adaptive == null) {
              System.err.println(
                "compose-preview: ${preview.id} resolved as ${drawable.javaClass.simpleName}, " +
                  "not AdaptiveIconDrawable; skipping ${shape.name} capture"
              )
              missing++
              continue
            }
            val bitmap = renderAdaptiveIcon(adaptive, shape)
            try {
              outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
              bitmap.recycle()
            }
            rendered++
          }
          RenderResourceType.ANIMATED_VECTOR -> {
            val animatable = drawable as? Animatable
            if (animatable == null) {
              System.err.println(
                "compose-preview: ${preview.id} resolved as ${drawable.javaClass.simpleName}, " +
                  "not Animatable; skipping animated capture"
              )
              missing++
              continue
            }
            renderAnimatedVector(drawable, animatable, outFile)
            rendered++
          }
        }
      }
    }
    println("compose-preview resource render: $rendered file(s), $missing capture(s) missing")
  }

  /** Plain drawable → bitmap canvas at intrinsic size. Used for vectors. */
  private fun renderStaticDrawable(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bitmap
  }

  /**
   * Composites foreground + background into a 108dp canvas, then masks to [shape]. The mask path
   * uses [PorterDuff.Mode.SRC_IN] so anti-aliased edges come through cleanly — `Canvas.clipPath` is
   * documented as not anti-aliased, which produces visible jaggies on circular masks at the
   * densities we render at.
   *
   * `LEGACY` skips the mask and renders just the foreground against transparent, approximating the
   * pre-API-26 fallback. Real legacy mipmap files (`mipmap-mdpi/ic_launcher.png` etc.) aren't
   * surfaced through `AdaptiveIconDrawable.foreground`, so this is the closest approximation
   * without parsing the consumer's mipmap directory ourselves.
   */
  private fun renderAdaptiveIcon(
    drawable: AdaptiveIconDrawable,
    shape: RenderAdaptiveShape,
  ): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)

    if (shape == RenderAdaptiveShape.LEGACY) {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      drawable.foreground?.also {
        it.setBounds(0, 0, width, height)
        it.draw(canvas)
      }
      return bitmap
    }

    // 1. Compose fg+bg into an offscreen bitmap so the SRC_IN compositor below has a single
    //    layer to mask.
    val composed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      val composedCanvas = Canvas(composed)
      drawable.background?.also {
        it.setBounds(0, 0, width, height)
        it.draw(composedCanvas)
      }
      drawable.foreground?.also {
        it.setBounds(0, 0, width, height)
        it.draw(composedCanvas)
      }

      // 2. Draw the mask shape into the output bitmap, then composite the icon with SRC_IN so
      //    only the masked pixels survive — anti-aliased.
      val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val outputCanvas = Canvas(output)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
      when (shape) {
        RenderAdaptiveShape.CIRCLE ->
          outputCanvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        RenderAdaptiveShape.ROUNDED_SQUARE -> {
          val r = width * 0.22f
          outputCanvas.drawRoundRect(rect, r, r, paint)
        }
        RenderAdaptiveShape.SQUARE -> outputCanvas.drawRect(rect, paint)
        RenderAdaptiveShape.LEGACY -> error("unreachable") // handled above
      }
      paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
      outputCanvas.drawBitmap(composed, 0f, 0f, paint)
      return output
    } finally {
      composed.recycle()
    }
  }

  /**
   * Renders [drawable] (an `AnimatedVectorDrawable` / `AnimatedVectorDrawableCompat`) into a GIF.
   *
   * **Known limitation, documented:** under Robolectric's `looperMode=PAUSED` (which we pin
   * because Compose's render path needs it — see `RobolectricRenderTestBase`), AVD's
   * `ObjectAnimator` doesn't receive the `Choreographer` frame callbacks that drive its tick.
   * Both `setVisible(true, true) + start() + idleFor(50ms)` and `LooperMode.LEGACY` (which gets
   * overridden by the system property anyway) leave every frame at the animator's t=0 state.
   *
   * Rather than write 30 identical frames, we emit a single-frame GIF showing the icon's rest
   * state. Format stays `.gif` so the manifest/file-extension contract holds and a future
   * commit that drives the animator (likely via a `RobolectricRenderTest.mainClock`-style
   * Compose-host shim, or by parsing the animator XML and stepping values manually) can write
   * multi-frame GIFs into the same path without touching the discovery / wiring side.
   */
  private fun renderAnimatedVector(drawable: Drawable, animatable: Animatable, outFile: File) {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    drawable.setBounds(0, 0, width, height)
    drawable.setVisible(true, true)
    animatable.start()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val frame: BufferedImage =
      try {
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        bitmap.toBufferedImage()
      } finally {
        bitmap.recycle()
        animatable.stop()
        drawable.setVisible(false, false)
      }
    ScrollGifEncoder.encode(
      frames = listOf(frame),
      outputFile = outFile,
      frameDelayMs = ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS,
    )
  }

  /**
   * The manifest's `renderOutput` is module-relative (e.g. `renders/resources/drawable/foo.png`).
   * The Gradle task hands us `composeai.resources.outputDir` already pointing at the `renders/`
   * directory, so we strip the leading `renders/` segment and resolve under it.
   */
  private fun resolveOutputPath(outputRoot: File, renderOutput: String): File =
    File(outputRoot, renderOutput.removePrefix("renders/"))

  /**
   * Android `Bitmap` → AWT `BufferedImage`. Both use ARGB-packed ints with alpha in the high
   * byte, so `getPixels` / `setRGB` round-trip directly without channel reordering.
   */
  private fun Bitmap.toBufferedImage(): BufferedImage {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, pixels, 0, w)
    return img
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
