package ee.schimke.composeai.renderer

import android.animation.AnimatorSet
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
   * Renders [drawable] (an `AnimatedVectorDrawable` / `AnimatedVectorDrawableCompat`) into a
   * multi-frame GIF.
   *
   * **The Robolectric paused-looper detour.** We pin `looperMode=PAUSED` because Compose's
   * render path needs it. Under PAUSED, the AVD's `ObjectAnimator` doesn't receive the
   * `Choreographer` frame callbacks that would normally tick its values forward — so a naive
   * `start()` + `setVisible(true, true)` + `idleFor(50ms)` loop captures the same t=0 state on
   * every frame. We tried both that idiom and `@LooperMode(LEGACY)` (overridden by the system
   * property anyway) for #259/#277; both leave every frame at t=0.
   *
   * **Workaround.** Reflect to the AVD's internal `AnimatorSet` and drive it manually via the
   * public `setCurrentPlayTime(ms)` API. This bypasses Choreographer and the looper entirely:
   * we walk frame times ourselves, set the animation clock, and capture each `draw()`. Works
   * because `AnimatorSet.setCurrentPlayTime` synchronously updates each child animator's
   * fraction and notifies its listeners (the AVD's `RenderNodeAnimatorSet`-equivalent path
   * pokes the underlying `VectorDrawable`'s group/path properties), and the next `draw()`
   * reflects those values.
   *
   * Falls back to a single-frame GIF (the previous behaviour) when reflection misses — e.g.
   * an unfamiliar AVD subclass, or a future API where the field name changed. Single-frame is
   * the safe degradation: the `.gif` extension stays intact so the manifest contract holds.
   *
   * Window length is bounded by [ANIMATED_DURATION_MS] regardless of `AnimatorSet.totalDuration`
   * — looping animators (`repeatCount=-1`, e.g. the sample's pulse) report `DURATION_INFINITE`
   * here, and even one-shot animators with `duration=10000` would produce an unwieldy GIF.
   */
  private fun renderAnimatedVector(drawable: Drawable, animatable: Animatable, outFile: File) {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    drawable.setBounds(0, 0, width, height)
    drawable.setVisible(true, true)
    val frames =
      try {
        val animatorSet = extractAnimatorSet(drawable, animatable)
        if (animatorSet != null) {
          captureAnimatedFrames(drawable, animatorSet, width, height)
        } else {
          listOf(captureSingleFrame(drawable, width, height))
        }
      } finally {
        drawable.setVisible(false, false)
      }
    ScrollGifEncoder.encode(
      frames = frames,
      outputFile = outFile,
      frameDelayMs = ANIMATED_FRAME_INTERVAL_MS.toInt(),
    )
  }

  /**
   * Extracts the internal `AnimatorSet` from an `AnimatedVectorDrawable` (platform) or
   * `AnimatedVectorDrawableCompat` (support lib) via reflection. Returns `null` when the
   * drawable isn't an AVD, or when the field layout has shifted in a way we don't handle —
   * caller falls back to the single-frame path.
   *
   * Both implementations stash the animator on a state class behind a field whose name has
   * been stable since the support lib's introduction (`mAnimatedVectorState.mAnimatorSet` for
   * platform; `mAnimatedVectorState.mAnimatorSet` for compat too). Search both class
   * hierarchies so a future subclass override doesn't break us silently.
   */
  private fun extractAnimatorSet(drawable: Drawable, animatable: Animatable): AnimatorSet? {
    // Walking the drawable's class hierarchy looking for any `AnimatorSet` field. Tries the
    // direct field first (`mAnimatorSetFromXml` on the platform AVD), then falls back to
    // probing inside `mAnimatedVectorState` and the `mAnimatorSet` wrapper
    // (`VectorDrawableAnimatorRT` on the platform path) — under Robolectric the direct
    // field starts null because the inflate path doesn't populate it; the wrapper does
    // hold an `AnimatorSet` reference once we kick start() the animation below.
    //
    // start() before reflection: forces the AVD to construct its child AnimatorSet on
    // demand. Without it both `mAnimatorSetFromXml` and the wrapper's internal animator
    // are null at this point.
    animatable.start()
    return try {
      // Direct AnimatorSet field on the drawable (fast path — when populated).
      findFieldValue(drawable) { it is AnimatorSet }?.let { return it as AnimatorSet }

      // Drawable.mAnimatorSet on the platform AVD is `VectorDrawableAnimator` (an interface
      // implemented by `VectorDrawableAnimatorRT` and `VectorDrawableAnimatorUI`); both
      // hold a child AnimatorSet via reflectable fields.
      val animatorWrapper =
        findFieldValueByName(drawable, "mAnimatorSet")
          ?: findFieldValueByName(drawable, "mAnimatorSetFromXml")
      if (animatorWrapper != null) {
        if (animatorWrapper is AnimatorSet) return animatorWrapper
        findFieldValue(animatorWrapper) { it is AnimatorSet }?.let { return it as AnimatorSet }
      }

      // mAnimatedVectorState.<...>.AnimatorSet — search one level into the state.
      val state = findFieldValueByName(drawable, "mAnimatedVectorState")
      if (state != null) {
        findFieldValue(state) { it is AnimatorSet }?.let { return it as AnimatorSet }
      }

      System.err.println(
        "compose-preview: no AnimatorSet found in ${drawable.javaClass.name} " +
          "(state=${state?.javaClass?.name}, wrapper=${animatorWrapper?.javaClass?.name}); " +
          "falling back to single-frame GIF"
      )
      null
    } catch (e: Throwable) {
      System.err.println(
        "compose-preview: AVD AnimatorSet reflection failed (${e.javaClass.simpleName}: " +
          "${e.message}); falling back to single-frame GIF"
      )
      null
    }
  }

  private fun findFieldValueByName(target: Any, name: String): Any? {
    var cls: Class<*>? = target.javaClass
    while (cls != null) {
      val field = cls.declaredFields.firstOrNull { it.name == name }
      if (field != null && !java.lang.reflect.Modifier.isStatic(field.modifiers)) {
        return try {
          field.isAccessible = true
          field.get(target)
        } catch (_: Throwable) {
          null
        }
      }
      cls = cls.superclass
    }
    return null
  }

  /**
   * Walks [target]'s class hierarchy (including superclasses) and returns the first non-null
   * field value matching [predicate]. Skips static fields and tolerates per-field access
   * failures (returns the next candidate rather than aborting). One-level only — no recursion
   * into the returned objects, which is what kept the previous implementation safe from JDK
   * 17+ module-access restrictions on private collection internals.
   */
  private fun findFieldValue(target: Any, predicate: (Any?) -> Boolean): Any? {
    var cls: Class<*>? = target.javaClass
    while (cls != null) {
      for (field in cls.declaredFields) {
        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
        try {
          field.isAccessible = true
          val value = field.get(target) ?: continue
          if (predicate(value)) return value
        } catch (_: Throwable) {
          // Field inaccessible (e.g. JDK 17 module-restricted) or the value getter
          // threw — try the next field.
        }
      }
      cls = cls.superclass
    }
    return null
  }

  /**
   * Walks the animator's timeline in [ANIMATED_FRAME_INTERVAL_MS] increments, captures one
   * bitmap per frame. Caller has already called `setVisible(true, true)`. We `start()` the
   * animator before stepping because `setCurrentPlayTime` on a never-started animator
   * sometimes leaves children uninitialised; the explicit start ensures `mInitialized` is
   * true on each child `ObjectAnimator`.
   */
  private fun captureAnimatedFrames(
    drawable: Drawable,
    animatorSet: AnimatorSet,
    width: Int,
    height: Int,
  ): List<BufferedImage> {
    val rawDuration = animatorSet.totalDuration
    val duration =
      if (rawDuration <= 0L || rawDuration == AnimatorSet.DURATION_INFINITE) {
        ANIMATED_DURATION_MS
      } else {
        rawDuration.coerceAtMost(ANIMATED_DURATION_MS)
      }
    val frameCount = (duration / ANIMATED_FRAME_INTERVAL_MS).toInt().coerceAtLeast(2)
    val frames = ArrayList<BufferedImage>(frameCount)
    animatorSet.start()
    try {
      for (i in 0 until frameCount) {
        val t = (i * ANIMATED_FRAME_INTERVAL_MS).coerceAtMost(duration - 1)
        animatorSet.currentPlayTime = t
        // Force the drawable to re-evaluate against the new animator state — without this,
        // some AVD impls cache the last-drawn bitmap and skip the per-property update.
        drawable.invalidateSelf()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
          val canvas = Canvas(bitmap)
          drawable.draw(canvas)
          frames += bitmap.toBufferedImage()
        } finally {
          bitmap.recycle()
        }
      }
    } finally {
      animatorSet.cancel()
    }
    return frames
  }

  private fun captureSingleFrame(drawable: Drawable, width: Int, height: Int): BufferedImage {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
      val canvas = Canvas(bitmap)
      drawable.draw(canvas)
      bitmap.toBufferedImage()
    } finally {
      bitmap.recycle()
    }
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

    /**
     * Hard ceiling on AVD GIF length. Looping animators (`repeatCount=-1`, e.g. the sample's
     * pulse) report `AnimatorSet.DURATION_INFINITE` from `totalDuration` and would otherwise
     * walk forever; one-shot animators with `duration=10000` would produce an unwieldy GIF.
     * 1.5s × 50ms/frame = 30 frames, enough to show ~2.5 cycles of a 600ms pulse.
     */
    const val ANIMATED_DURATION_MS: Long = 1500L

    const val ANIMATED_FRAME_INTERVAL_MS: Long = 50L
  }
}
