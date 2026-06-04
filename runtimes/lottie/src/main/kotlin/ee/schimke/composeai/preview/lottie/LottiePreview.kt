package ee.schimke.composeai.preview.lottie

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.alexzhirkevich.compottie.LottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlin.math.roundToInt

/**
 * Renders a Lottie animation [asset] at a fixed [progress] inside a regular Compose `@Preview`.
 *
 * Authoring path for Lottie previews, sibling to `SplashScreenSurface` / `NotificationContent`. The
 * consumer ships the animation file under `src/main/resources/` (e.g. `lottie/loading.json`); the
 * compose-preview plugin links the processed-resources dir onto the render classpath, so [asset] is
 * resolved by the classloader at render time, and packs the same dir into bundles so the asset
 * travels with the preview.
 *
 * The composition is parsed **synchronously** via [LottieComposition.parse] rather than the async
 * `rememberLottieComposition` so the first composed frame already has the full document — the
 * Desktop renderer captures after only two `scene.render()` passes and does not pump coroutines, so
 * an async load would render blank.
 *
 * [progress] is the configured value baked into the captured PNG: `0f` is the first frame, `1f` the
 * last. Fan multiple `@Preview`s at different `progress` values to review keyframes, or (follow-up)
 * let the daemon drive it interactively so a VS Code slider scrubs the timeline.
 *
 * @param asset classpath resource path of the Lottie JSON (leading slash optional), e.g.
 *   `"lottie/loading.json"`.
 * @param progress timeline position in `0f..1f`; coerced into range.
 * @param contentScale how the animation is fitted into [modifier]'s bounds. Defaults to
 *   [ContentScale.Fit].
 */
@Composable
fun LottiePreview(
  asset: String,
  modifier: Modifier = Modifier,
  progress: Float = 0f,
  contentScale: ContentScale = ContentScale.Fit,
) {
  val clamped = progress.coerceIn(0f, 1f)
  LottiePreview(asset = asset, modifier = modifier, contentScale = contentScale) { clamped }
}

/**
 * Progress-provider overload: [progress] is read at draw time, so a caller that drives a
 * snapshot-backed state (e.g. `mutableFloatStateOf`) between renders sweeps the Lottie timeline
 * without rebuilding the composition. This is the animated-capture path — the desktop renderer's
 * `renderLottieGif` holds a single [androidx.compose.ui.ImageComposeScene] and flips the backing
 * state across the intrinsic-duration frame window, re-`render()`ing each step into a GIF frame.
 *
 * Parses the composition **synchronously** (see the class-level note) and clamps the provided
 * progress into `0f..1f`, so callers can hand back an un-normalised sweep value.
 *
 * @param asset classpath resource path of the Lottie JSON (leading slash optional).
 * @param progress timeline position provider; evaluated each draw, coerced into `0f..1f`.
 * @param contentScale how the animation is fitted into [modifier]'s bounds. Defaults to
 *   [ContentScale.Fit].
 */
@Composable
fun LottiePreview(
  asset: String,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  progress: () -> Float,
) {
  val composition = remember(asset) { LottieComposition.parse(loadLottieAsset(asset)) }
  Image(
    painter =
      rememberLottiePainter(composition = composition, progress = { progress().coerceIn(0f, 1f) }),
    contentDescription = asset,
    modifier = modifier,
    contentScale = contentScale,
  )
}

/**
 * The Lottie asset's intrinsic timeline length in milliseconds — `durationFrames / frameRate` (e.g.
 * a 60-frame clip authored at 30fps is 2000ms). This is the "default duration" the animated preview
 * path captures across when no explicit window is requested.
 *
 * Parses [asset] off the render classpath (same loader resolution as [LottiePreview]). Returns
 * [default] when the asset can't be parsed or declares a non-positive frame rate / length (a
 * degenerate single-frame document), so callers always get a usable, positive window.
 */
fun lottieIntrinsicDurationMillis(asset: String, default: Int = DEFAULT_LOTTIE_DURATION_MS): Int {
  val composition =
    runCatching { LottieComposition.parse(loadLottieAsset(asset)) }.getOrNull() ?: return default
  val frameRate = composition.frameRate
  val durationFrames = composition.durationFrames
  if (frameRate <= 0f || durationFrames <= 0f) return default
  return (durationFrames / frameRate * 1000f).roundToInt().coerceAtLeast(1)
}

/** Fallback intrinsic duration for a Lottie asset whose timeline can't be read. */
const val DEFAULT_LOTTIE_DURATION_MS: Int = 1000

/**
 * Reads a Lottie asset from the classpath as a UTF-8 string. Tries the thread context classloader
 * first (the render subprocess installs the consumer's classes/resources there), then this class's
 * own loader as a fallback for plain JVM unit tests. A leading slash is tolerated.
 *
 * @throws IllegalArgumentException when the resource is not on the classpath — a clear authoring
 *   error ("did you put it under src/main/resources?") rather than a downstream parse NPE.
 */
internal fun loadLottieAsset(asset: String): String {
  val normalized = asset.removePrefix("/")
  val loaders =
    listOfNotNull(
      Thread.currentThread().contextClassLoader,
      LottieAssetMarker::class.java.classLoader,
    )
  for (loader in loaders) {
    loader.getResourceAsStream(normalized)?.use { stream ->
      return stream.readBytes().decodeToString()
    }
  }
  throw IllegalArgumentException(
    "Lottie asset '$asset' not found on the classpath. Put it under src/main/resources " +
      "(e.g. src/main/resources/$normalized) so the preview plugin links and bundles it."
  )
}

/** Stable anchor for `::class.java.classLoader`; avoids depending on the inline lambda's loader. */
private object LottieAssetMarker
