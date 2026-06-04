package ee.schimke.composeai.preview.lottie

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.alexzhirkevich.compottie.LottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter

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
  val composition = remember(asset) { LottieComposition.parse(loadLottieAsset(asset)) }
  val clamped = progress.coerceIn(0f, 1f)
  Image(
    painter = rememberLottiePainter(composition = composition, progress = { clamped }),
    contentDescription = asset,
    modifier = modifier,
    contentScale = contentScale,
  )
}

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
