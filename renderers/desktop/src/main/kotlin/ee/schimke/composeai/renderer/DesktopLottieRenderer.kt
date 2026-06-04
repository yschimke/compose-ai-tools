package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.preview.lottie.LottiePreview
import ee.schimke.composeai.preview.lottie.lottieIntrinsicDurationMillis
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat

/**
 * Default per-frame interval (≈25fps) for an animated Lottie capture. Snapped to GIF's 10ms timing
 * resolution at encode time by [ScrollGifEncoder].
 */
const val DEFAULT_LOTTIE_FRAME_INTERVAL_MS: Int = 40

/** Lower bound on the per-frame interval, to keep the frame count (and GIF size) bounded. */
const val MIN_LOTTIE_FRAME_INTERVAL_MS: Int = 20

/**
 * Upper bound on the captured window, regardless of the asset's declared length. A long ambient
 * loop still produces a GIF, just truncated to this many milliseconds so the artefact stays small.
 */
const val MAX_LOTTIE_GIF_DURATION_MS: Int = 5000

/**
 * Render a directly-discovered Lottie asset as a **looping animated GIF**, the animated companion
 * to [renderLottieAsset]'s single still frame. No consumer composable is involved — [LottiePreview]
 * loads [assetPath] off the render classpath and inflates it via Compottie.
 *
 * **Default duration is the asset's own timeline.** When [durationMillisOverride] is null (or
 * non-positive) the capture spans [lottieIntrinsicDurationMillis] — a 2s clip yields a 2s GIF —
 * coerced into `1..`[maxDurationMillis]. The window is sampled at [frameIntervalMs] into
 * `frameCount = round(durationMs / interval)` frames, with progress stepped `i / frameCount` so the
 * final frame wraps seamlessly back to the first (a clean loop with no end-of-cycle stutter).
 *
 * **One scene, many frames.** Unlike the per-render-scene one-shot path, this holds a single
 * [ImageComposeScene] and sweeps a snapshot-backed progress state across it, re-`render()`ing each
 * step — so the comparatively expensive Compottie parse + Skia surface allocation happen once, not
 * once per frame. `Snapshot.sendApplyNotifications()` after each write is what makes the held scene
 * observe the out-of-composition state change before the next `render()`.
 *
 * Returns the written [outputFile], or `null` when the GIF writer plugin declined (never on a
 * standard JRE) — mirrors [ScrollGifEncoder.encode]'s contract. Throws (propagated by the caller)
 * when the asset can't be inflated or a frame can't be encoded.
 */
fun renderLottieGif(
  assetPath: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  durationMillisOverride: Int? = null,
  frameIntervalMs: Int = DEFAULT_LOTTIE_FRAME_INTERVAL_MS,
  maxDurationMillis: Int = MAX_LOTTIE_GIF_DURATION_MS,
): File? {
  val durationMs =
    (durationMillisOverride?.takeIf { it > 0 } ?: lottieIntrinsicDurationMillis(assetPath))
      .coerceIn(1, maxDurationMillis)
  val interval = frameIntervalMs.coerceAtLeast(MIN_LOTTIE_FRAME_INTERVAL_MS)
  val frameCount = (durationMs.toDouble() / interval).roundToInt().coerceAtLeast(2)

  val progress = mutableFloatStateOf(0f)
  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density))
  val frames = ArrayList<BufferedImage>(frameCount)
  try {
    scene.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        val bgColor =
          when {
            backgroundColor != 0L -> Color(backgroundColor.toInt())
            showBackground -> Color.White
            else -> Color.Transparent
          }
        Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
          LottiePreview(asset = assetPath, modifier = Modifier.fillMaxSize()) {
            progress.floatValue
          }
        }
      }
    }
    // Settle the first composition before sampling, mirroring the two-render warmup of the
    // single-frame path.
    scene.render()
    for (i in 0 until frameCount) {
      progress.floatValue = i.toFloat() / frameCount
      Snapshot.sendApplyNotifications()
      frames += scene.render().toBufferedImage()
    }
  } finally {
    scene.close()
  }
  return ScrollGifEncoder.encode(frames = frames, outputFile = outputFile, frameDelayMs = interval)
}

/**
 * Encode a Skia frame to PNG bytes and decode into the `BufferedImage` [ScrollGifEncoder] wants.
 */
private fun org.jetbrains.skia.Image.toBufferedImage(): BufferedImage {
  val png =
    encodeToData(EncodedImageFormat.PNG) ?: error("Failed to encode Lottie GIF frame to PNG")
  return ImageIO.read(ByteArrayInputStream(png.bytes))
    ?: error("Failed to decode Lottie GIF frame PNG")
}
