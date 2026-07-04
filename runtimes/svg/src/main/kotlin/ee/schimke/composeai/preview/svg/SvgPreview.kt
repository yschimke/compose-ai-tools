package ee.schimke.composeai.preview.svg

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import java.io.ByteArrayInputStream

/**
 * Renders an SVG image [asset] inside a regular Compose `@Preview`.
 *
 * Authoring path for SVG previews, sibling to `LottiePreview` — the customization escape hatch over
 * the zero-config "just drop a `.svg` under resources" discovery. The consumer ships the artwork
 * under `src/main/resources/` (e.g. `svg/badge.svg`); the compose-preview plugin links the
 * processed-resources dir onto the render classpath, so [asset] is resolved by the classloader at
 * render time, and packs the same file into bundles so the artwork travels with the preview.
 *
 * The SVG is parsed **synchronously** via [loadSvgPainter] (Skia-backed, part of Compose Desktop)
 * so the first composed frame already has the full document — the Desktop renderer captures after
 * only two `scene.render()` passes and does not pump coroutines, so an async load would render
 * blank.
 *
 * Unlike Lottie there is no timeline: SVG is static (SMIL/CSS animation isn't replayed by
 * `loadSvgPainter`), so there is no `progress` knob — just how the artwork is fitted and tinted.
 *
 * @param asset classpath resource path of the SVG (leading slash optional), e.g. `"svg/badge.svg"`.
 * @param contentScale how the artwork is fitted into [modifier]'s bounds. Defaults to
 *   [ContentScale.Fit] so the SVG keeps its aspect ratio.
 * @param colorFilter optional tint applied to the whole drawing — `ColorFilter.tint(color)`
 *   recolors a monochrome icon. Defaults to `null` (draw the SVG's own colors).
 */
@Composable
fun SvgPreview(
  asset: String,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  colorFilter: ColorFilter? = null,
) {
  val bytes = remember(asset) { loadSvgAsset(asset) }
  SvgPreview(
    bytes = bytes,
    contentDescription = asset,
    modifier = modifier,
    contentScale = contentScale,
    colorFilter = colorFilter,
  )
}

/**
 * Pre-loaded-bytes overload: draws [bytes] (a raw SVG document) rather than resolving a classpath
 * resource. This is the seam the Desktop renderer's zero-config `kind=SVG` path uses — it loads the
 * asset eagerly (so a missing file surfaces a clear [IllegalArgumentException] before composition)
 * and hands the bytes here, keeping a single draw implementation shared with the [asset] overload.
 *
 * @param bytes the raw SVG document.
 * @param contentScale how the artwork is fitted into [modifier]'s bounds. Defaults to
 *   [ContentScale.Fit].
 * @param colorFilter optional tint applied to the whole drawing. Defaults to `null`.
 */
@Composable
fun SvgPreview(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  contentScale: ContentScale = ContentScale.Fit,
  colorFilter: ColorFilter? = null,
) {
  val density = LocalDensity.current
  val painter = remember(bytes, density) { loadSvgPainter(ByteArrayInputStream(bytes), density) }
  Image(
    painter = painter,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
    colorFilter = colorFilter,
  )
}

/**
 * Reads an SVG asset from the classpath as raw bytes. Tries the thread context classloader first
 * (the render subprocess installs the consumer's classes/resources there), then this class's own
 * loader as a fallback for plain JVM unit tests. A leading slash is tolerated.
 *
 * @throws IllegalArgumentException when the resource is not on the classpath — a clear authoring
 *   error ("did you put it under src/main/resources?") rather than a downstream parse failure.
 */
fun loadSvgAsset(asset: String): ByteArray {
  val normalized = asset.removePrefix("/")
  val loaders =
    listOfNotNull(Thread.currentThread().contextClassLoader, SvgAssetMarker::class.java.classLoader)
  for (loader in loaders) {
    loader.getResourceAsStream(normalized)?.use { stream ->
      return stream.readBytes()
    }
  }
  throw IllegalArgumentException(
    "SVG asset '$asset' not found on the classpath. Put it under src/main/resources " +
      "(e.g. src/main/resources/$normalized) so the preview plugin links and bundles it."
  )
}

/** Stable anchor for `::class.java.classLoader`; avoids depending on the inline lambda's loader. */
private object SvgAssetMarker
