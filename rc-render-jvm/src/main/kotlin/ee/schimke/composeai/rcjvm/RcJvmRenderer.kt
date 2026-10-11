@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  androidx.compose.runtime.InternalComposeApi::class,
)

package ee.schimke.composeai.rcjvm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcManifestTypefaceLoader
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Render a captured Remote Compose document through `rc-player-compose` to a PNG.
 *
 * [density] and [fontScale] go to the scene as one [Density], so `RemoteDensity.Host` text resolves
 * at both. [seeds] are serve `rc.<name>=…` knob edits over the authored defaults. [dark] selects
 * the `ColorTheme` branch (light by default, never the build machine's OS theme, for
 * reproducibility).
 *
 * Everything happens in one `scene.render()` with no frame loop, so the result is the document at
 * t=0, the same frame as the baked capture.
 */
public fun renderRemoteDocumentToPng(
  bytes: ByteArray,
  widthPx: Int,
  heightPx: Int,
  density: Float = 2f,
  seeds: Map<String, RcNamedValue> = emptyMap(),
  dark: Boolean = false,
  fontScale: Float = 1f,
  typefaces: RcTypefaceLoader? = rcTypefaces(),
): ByteArray {
  val scene =
    ImageComposeScene(width = widthPx, height = heightPx, density = Density(density, fontScale)) {
      RcContent(bytes, seeds, dark, typefaces, Modifier.fillMaxSize())
    }
  try {
    val image = scene.render()
    val data =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: error("skiko could not encode the rendered image to PNG")
    return data.bytes
  } finally {
    scene.close()
  }
}

/**
 * Render a captured document and export the resulting Compose tree as a self-contained layered
 * `compose/figma-svg`, mirroring the desktop [ImageComposeScene] post-capture path into
 * [ComposeFigmaSvgDataProducer]. Unrepresentable draw ops become PNG layers, inlined before
 * returning because the subprocess deletes its temp directory. An all-canvas document exports as a
 * raster layer.
 */
public fun renderRemoteDocumentToSvg(
  bytes: ByteArray,
  widthPx: Int,
  heightPx: Int,
  density: Float = 2f,
  seeds: Map<String, RcNamedValue> = emptyMap(),
  dark: Boolean = false,
  fontScale: Float = 1f,
  typefaces: RcTypefaceLoader? = rcTypefaces(),
): ByteArray {
  val rootDir = Files.createTempDirectory("rcjvm-svg-").toFile()
  val previewId = "rc-jvm"
  val slotTables = mutableSetOf<CompositionData>()
  val scene =
    ImageComposeScene(width = widthPx, height = heightPx, density = Density(density, fontScale)) {
      InspectableContent(slotTables) {
        // Keep the document's authored pixel viewport as an explicit layout node, so a sparse
        // document cannot make the SVG shrink-wrap to only its semantic text nodes.
        Box(
          Modifier.size(
            with(LocalDensity.current) { widthPx.toDp() },
            with(LocalDensity.current) { heightPx.toDp() },
          )
        ) {
          RcContent(bytes, seeds, dark, typefaces, Modifier.fillMaxSize())
        }
      }
    }
  try {
    val framePng = File(rootDir, "frame.png")
    val image = scene.render()
    val encoded =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: error("skiko could not encode the rendered image for SVG export")
    framePng.writeBytes(encoded.bytes)
    val semanticsRoot =
      scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
        ?: error("cmp-jvm SVG export found no Compose semantics owner")
    val layout =
      LayoutInspectorDataProducer.buildPayload(
        root = semanticsRoot,
        slotTables = slotTables.toList(),
        density = density,
      ) ?: error("cmp-jvm SVG export could not build a layout tree")
    val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density)
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout,
      semantics = semantics,
      density = density,
      frameImage = framePng,
    )
    val previewDir = File(rootDir, previewId)
    val svgFile = File(previewDir, ComposeFigmaSvgDataProducer.FILE_SVG)
    val svg = svgFile.takeIf { it.isFile }?.readText().orEmpty()
    check(svg.startsWith("<svg")) { "cmp-jvm SVG producer wrote no SVG" }
    return inlineRasterLayers(svg, previewDir).toByteArray(Charsets.UTF_8)
  } finally {
    scene.close()
    rootDir.deleteRecursively()
  }
}

@Composable
private fun RcContent(
  bytes: ByteArray,
  seeds: Map<String, RcNamedValue>,
  dark: Boolean,
  typefaces: RcTypefaceLoader?,
  modifier: Modifier,
) {
  // Keyed on the document, not the frame: the map is the player's *initial* named values, and a
  // one-frame render never edits it afterwards.
  val namedValues =
    remember(bytes) { mutableStateMapOf<String, RcNamedValue>().apply { putAll(seeds) } }
  val theme = if (dark) RcPlayerTheme.Dark else RcPlayerTheme.Light
  // The player's own default is the only way to say "no host faces"; the parameter is not nullable.
  if (typefaces == null) {
    RcComposePlayer(bytes, modifier, theme = theme, namedValues = namedValues)
  } else {
    RcComposePlayer(
      bytes,
      modifier,
      theme = theme,
      namedValues = namedValues,
      typefaces = typefaces,
    )
  }
}

/** Inline only producer-owned relative PNG layers; this directory contains no untrusted files. */
private fun inlineRasterLayers(svg: String, svgDir: File): String {
  if (!svg.contains("figma-raster/")) return svg
  val href = Regex("href=\"([^\"]*figma-raster/[^\"]+\\.png)\"")
  return href.replace(svg) { match ->
    val relative = match.groupValues[1]
    if (relative.startsWith('/') || relative.contains("..") || relative.contains(':')) {
      return@replace match.value
    }
    val file = File(svgDir, relative)
    if (!file.isFile) return@replace match.value
    val data = Base64.getEncoder().encodeToString(file.readBytes())
    "href=\"data:image/png;base64,$data\""
  }
}

/** Captures the composition data consumed by the layout-inspector half of figma-svg export. */
@Composable
private fun InspectableContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}

/** System property naming the directory holding the player's `fonts.json` manifest and faces. */
public const val FONTS_DIR_PROPERTY: String = "composeai.rcjvm.fontsDir"

/**
 * The host typefaces from the `fonts.json` manifest under [FONTS_DIR_PROPERTY], loaded once per
 * process (so a pooled worker reuses them). Null — the player's default — when unconfigured. The
 * serve host points this at the Wasm player's vendored faces, so text width matches the browser and
 * parity lanes.
 */
internal fun rcTypefaces(): RcTypefaceLoader? = typefaceHolder

private val typefaceHolder: RcTypefaceLoader? by lazy {
  val dir = System.getProperty(FONTS_DIR_PROPERTY)?.let(::File)
  if (dir == null || !File(dir, "fonts.json").isFile) return@lazy null
  runCatching {
    runBlocking { RcManifestTypefaceLoader { url -> File(url).readBytes() }.load(dir.path) }
  }
    .getOrNull()
}
