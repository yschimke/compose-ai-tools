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
import java.io.File
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat

/** Default per-frame interval (≈25fps) for an animated Lottie capture. */
const val DEFAULT_LOTTIE_FRAME_INTERVAL_MS: Int = 40

/** Lower bound on the per-frame interval, to keep the frame count (and artefact size) bounded. */
const val MIN_LOTTIE_FRAME_INTERVAL_MS: Int = 20

/**
 * Upper bound on the captured window, regardless of the asset's declared length. A long ambient
 * loop still produces an animation, just truncated to this many milliseconds so the artefact stays
 * small.
 */
const val MAX_LOTTIE_GIF_DURATION_MS: Int = 5000

/**
 * Render a directly-discovered Lottie asset as a **looping animated PNG (APNG)** — the animated
 * companion the discovery layer emits alongside [renderLottieAsset]'s still frame.
 *
 * **Why APNG rather than GIF.** The discovered asset renders against a transparent background, and
 * GIF carries only 1-bit transparency: [javax.imageio]'s GIF writer thresholds every partially
 * transparent pixel to fully-opaque-or-transparent, crushing the Lottie shape's anti-aliased edge
 * into a hard two-colour boundary. A sub-pixel edge shift between otherwise-identical CI renders
 * then flips whole boundary pixels, so the committed GIF baseline churned on essentially every
 * push. APNG is a standard PNG container with full 8-bit alpha, so the edge survives as a stable
 * colour blend that the preview pipeline's pixelmatch gate treats as unchanged — and it still
 * autoplays inline on GitHub, the web, VS Code webviews, and the preview server (all browser-engine
 * surfaces), as long as the artefact keeps a `.png` extension so it's served as `image/png`.
 *
 * Captures the asset's intrinsic-duration window sampled at [frameIntervalMs] into `frameCount =
 * round(durationMs / interval)` frames, progress stepped `i / frameCount` so the loop wraps
 * seamlessly. Holds a single [ImageComposeScene] and sweeps a snapshot-backed progress state across
 * it (`Snapshot.sendApplyNotifications()` flushes each step, then [renderSettledFrame] renders
 * until the pixels stop changing), so the Compottie parse + Skia surface allocation happen once.
 * Each captured frame is written as a PNG and stitched by [ApngEncoder], which copies each frame's
 * `IDAT` verbatim — so the RGBA (alpha-carrying) frames Skiko emits become an alpha-carrying APNG
 * with no re-quantisation.
 *
 * Returns the written [outputFile], or throws (propagated by the caller) when the asset can't be
 * inflated or a frame can't be encoded.
 */
fun renderLottieApng(
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
  val frameDir = java.nio.file.Files.createTempDirectory("lottie-apng").toFile()
  val frameFiles = ArrayList<File>(frameCount)
  try {
    scene.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        val bgColor =
          when {
            backgroundColor != 0L -> Color(backgroundColor.toInt())
            showBackground -> Color.White
            // Transparent stays transparent — APNG carries the alpha the GIF path could not.
            else -> Color.Transparent
          }
        Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
          LottiePreview(asset = assetPath, modifier = Modifier.fillMaxSize()) {
            progress.floatValue
          }
        }
      }
    }
    // Settle the first composition before sampling, mirroring the still + GIF paths.
    scene.render()
    for (i in 0 until frameCount) {
      progress.floatValue = i.toFloat() / frameCount
      Snapshot.sendApplyNotifications()
      val frameFile = File(frameDir, "frame-%05d.png".format(i))
      frameFile.writeBytes(renderSettledFrame(scene, i))
      frameFiles += frameFile
    }
  } finally {
    scene.close()
  }
  return try {
    // APNG delay is delayNumerator/delayDenominator seconds; interval ms / 1000 keeps the cadence.
    ApngEncoder.encodeFromPngFrames(
      frames = frameFiles,
      delayNumerator = interval.toShort(),
      delayDenominator = 1000.toShort(),
      loopCount = 0,
      out = outputFile,
    )
    outputFile
  } finally {
    frameDir.deleteRecursively()
  }
}

/**
 * How many consecutive render passes must agree byte-for-byte before a swept frame is accepted as
 * settled. Three, not two: a single agreeing pair can still be two *stale* passes when the progress
 * step is late reaching the painter, and one duplicated frame is enough to rewrite the whole APNG.
 */
private const val LOTTIE_SETTLE_AGREEMENTS: Int = 3

/**
 * Upper bound on render passes per swept frame, so a pathological asset that never reaches
 * [LOTTIE_SETTLE_AGREEMENTS] agreeing passes still terminates with its most recent pixels rather
 * than spinning.
 */
private const val MAX_LOTTIE_SETTLE_PASSES: Int = 8

/**
 * Render [scene] repeatedly until the encoded PNG stops changing, and return the settled bytes.
 *
 * Compottie routes a new `progress` to its painter through work that lands *after* the pass that
 * applied the snapshot — usually on the very next pass, but not always, because that work is
 * published from another thread and races the render. A fixed pass count therefore can't fix it:
 * with one pass per step the capture reused the previous step's pixels outright, and with a fixed
 * settle pass it still did so now and then. The duplicates fell on a different index each run
 * (frame 35 in one CI render, none in the next, frames 3 and 21 in an earlier one), so the
 * committed APNG's bytes flipped between two states push after push and the diff bot reported
 * `lottie/spin.json` as changed on PRs that never touched it (issue #2868).
 *
 * Converging on the pixels instead makes the capture independent of how many passes the async
 * handoff needs. A step whose progress already landed on the first pass settles immediately at the
 * [LOTTIE_SETTLE_AGREEMENTS]th identical pass; a late one keeps rendering until the new pixels
 * appear and then agree. `render()` defaults to nanoTime 0, so the extra passes advance no
 * clock-driven animation — they only drain pending work.
 */
private fun renderSettledFrame(scene: ImageComposeScene, frameIndex: Int): ByteArray {
  var previous: ByteArray? = null
  var agreements = 1
  repeat(MAX_LOTTIE_SETTLE_PASSES) {
    val bytes =
      scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("Failed to encode Lottie APNG frame $frameIndex to PNG")
    agreements = if (previous?.contentEquals(bytes) == true) agreements + 1 else 1
    previous = bytes
    if (agreements >= LOTTIE_SETTLE_AGREEMENTS) return bytes
  }
  return previous ?: error("Failed to render Lottie APNG frame $frameIndex")
}
