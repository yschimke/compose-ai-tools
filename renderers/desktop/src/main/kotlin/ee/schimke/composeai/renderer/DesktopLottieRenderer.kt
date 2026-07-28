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
 * until that step has demonstrably reached the painter), so the Compottie parse + Skia surface
 * allocation happen once. Each captured frame is written as a PNG and stitched by [ApngEncoder],
 * which copies each frame's `IDAT` verbatim — so the RGBA (alpha-carrying) frames Skiko emits
 * become an alpha-carrying APNG with no re-quantisation.
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
    // The previous step's settled bytes double as the "stale" reference the next step must move
    // off — see [renderSettledFrame].
    var settled: ByteArray? = null
    for (i in 0 until frameCount) {
      progress.floatValue = i.toFloat() / frameCount
      Snapshot.sendApplyNotifications()
      settled = renderSettledFrame(scene, i, settled)
      val frameFile = File(frameDir, "frame-%05d.png".format(i))
      frameFile.writeBytes(settled)
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
 * Upper bound on render passes per swept frame. Reached only when a step's pixels genuinely equal
 * the previous step's (a held segment of the timeline, where "has the new progress landed?" is
 * unanswerable from the pixels because both answers look the same) — at which point the accumulated
 * passes are themselves the evidence, and the held pixels are returned.
 */
private const val MAX_LOTTIE_SETTLE_PASSES: Int = 8

/**
 * Render [scene] until the requested progress step has demonstrably reached the painter, and return
 * the settled PNG bytes.
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
 * Counting agreeing passes alone doesn't fix it either: if the handoff is late enough, the first N
 * passes can *all* carry the previous step's pixels and agree with each other, and the loop latches
 * on the stale frame. What makes the stale case decidable is that the stale pixels are not just any
 * pixels — they are exactly [previousFrame], the bytes the last step settled on. So a pass counts
 * as settled only once it has both moved off [previousFrame] and stopped changing. A late handoff
 * can no longer satisfy that by standing still, because standing still is the very thing being
 * rejected.
 *
 * The one case the pixels can't decide is a genuinely held frame, where the new progress renders
 * identically to the previous step. That runs to [MAX_LOTTIE_SETTLE_PASSES] and returns the held
 * pixels, which is the right answer either way: the previous step itself only returned once *it*
 * had settled, so no work was outstanding when this step began, and pumping the full pass allowance
 * with nothing else pending leaves "the frame is genuinely identical" as the explanation.
 * Continuously moving assets like `lottie/spin.json` never reach the cap.
 *
 * [previousFrame] is null for the first step, which has no predecessor to be stale against; it
 * settles on two agreeing passes, matching the still-capture path. `render()` defaults to nanoTime
 * 0, so the extra passes advance no clock-driven animation — they only drain pending work.
 */
private fun renderSettledFrame(
  scene: ImageComposeScene,
  frameIndex: Int,
  previousFrame: ByteArray?,
): ByteArray {
  var last: ByteArray? = null
  repeat(MAX_LOTTIE_SETTLE_PASSES) {
    val bytes =
      scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("Failed to encode Lottie APNG frame $frameIndex to PNG")
    val stable = last?.contentEquals(bytes) == true
    val advanced = previousFrame == null || !bytes.contentEquals(previousFrame)
    last = bytes
    if (stable && advanced) return bytes
  }
  return last ?: error("Failed to render Lottie APNG frame $frameIndex")
}
