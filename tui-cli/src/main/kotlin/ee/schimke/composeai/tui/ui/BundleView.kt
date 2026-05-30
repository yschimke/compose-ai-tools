package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicMain
import com.jakewharton.mosaic.ui.Bitmap
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.tui.BundlePngMetadata
import ee.schimke.composeai.tui.LiveSession
import ee.schimke.composeai.tui.TuiArgs
import ee.schimke.composeai.tui.image.Bitmaps
import ee.schimke.composeai.tui.terminal.TerminalSize
import java.io.File
import kotlinx.coroutines.launch

/**
 * Bundle-PNG entry point. Renders ONE preview full-screen with no list / tab / status chrome — just
 * the image. The PNG handed on the command line is decoded and shown immediately as the first frame
 * (ImageIO reads the leading PNG of a PNG+ZIP polyglot bundle, ignoring the appended zip); then, if
 * the bundle names a module and we're inside its project on disk, the daemon is attached and later
 * renders replace the seed live as the source is edited. A plain PNG (or a bundle opened outside its
 * project) is shown as a static image.
 *
 * `q` / `Esc` quits; `r` forces a re-render (no-op without a live session).
 */
fun runBundle(png: File, projectRoot: File?, args: TuiArgs) {
  // Decode the bundle's own image up front so the very first composed frame already shows it — no
  // "rendering…" flash before the daemon attaches.
  val seed = Bitmaps.readPng(png)

  // `bundle.json` tells us which module + cover preview produced this PNG. Live re-render also needs
  // the source project on disk: without a project root (we're not inside a Gradle checkout) there's
  // no daemon to attach, so we fall back to the static seed.
  val metadata = BundlePngMetadata.readOrNull(png)
  val previewId = metadata?.coverPreviewId
  val module =
    if (projectRoot != null && metadata != null) {
      // Mirror the synthetic module Main builds for `--no-discovery`: <projectRoot>/<path-as-dirs>.
      val relative = metadata.modulePath.trimStart(':').replace(':', '/').ifEmpty { "." }
      PreviewModule(gradlePath = metadata.modulePath, projectDir = File(projectRoot, relative))
    } else {
      null
    }

  runMosaicMain {
    BundleApp(
      seed = seed,
      pngName = png.name,
      module = module,
      previewId = previewId,
      extensions = args.extensions,
    )
  }
}

/**
 * The whole bundle-mode UI: a single full-terminal image. Holds an optional [LiveSession] and swaps
 * the seed for the daemon's freshest render of [previewId] whenever a notification ticks.
 */
@Composable
private fun BundleApp(
  seed: Bitmap?,
  pngName: String,
  module: PreviewModule?,
  previewId: String?,
  extensions: Set<String>,
) {
  val initialSize = remember { TerminalSize.probe() }
  val terminalState = LocalTerminalState.current
  val cols = terminalState.size.columns.takeIf { it > 0 } ?: initialSize.cols
  val rows = terminalState.size.rows.takeIf { it > 0 } ?: initialSize.rows

  val scope = rememberCoroutineScope()
  val liveSession = remember { LiveSession(scope) }
  val liveState by liveSession.state.collectAsState()
  var exitRequested by remember { mutableStateOf(false) }

  // Attach the daemon once, if the bundle named a module. The session is sticky for the run.
  LaunchedEffect(module) { if (module != null) liveSession.enable(module, extensions) }
  // Mark the preview visible once the session is READY so the daemon pushes its re-renders.
  LaunchedEffect(liveState.status, previewId) {
    if (previewId != null && liveState.status == LiveSession.Status.READY) {
      liveSession.setVisible(previewId)
    }
  }

  if (exitRequested) {
    LaunchedEffect(Unit) { kotlin.system.exitProcess(0) }
    return
  }

  // Prefer the daemon's freshest render of this preview; fall back to the seed PNG until (or unless)
  // one exists. Re-resolved on every daemon tick.
  val liveFrame: Bitmap? =
    if (module != null && previewId != null) {
      remember(liveState.tick) { resolveRenderedPng(module, previewId)?.let(Bitmaps::readPng) }
    } else {
      null
    }
  val bitmap = liveFrame ?: seed

  Column(
    modifier =
      Modifier.onKeyEvent { event ->
        when (event.key) {
          "q",
          "Q",
          "Escape" -> {
            liveSession.disable()
            exitRequested = true
            true
          }
          "r" -> {
            if (previewId != null) scope.launch { liveSession.forceRender(previewId) }
            true
          }
          else -> false
        }
      }
  ) {
    if (bitmap == null) {
      Text("(failed to decode $pngName)".take(cols), textStyle = TextStyle.Dim)
    } else {
      Image(bitmap = bitmap, cellWidth = cols, cellHeight = rows)
    }
  }
}

/**
 * The daemon's most recent render of [previewId] for [module], or null if it hasn't rendered yet.
 * Mirrors `PreviewRow.resolvePng` — the renderer writes `build/compose-previews/<id>.png`, with
 * multi-capture previews landing as `<id>--<dimension>.png` (we take the first sibling).
 */
private fun resolveRenderedPng(module: PreviewModule, previewId: String): File? {
  val direct = module.projectDir.resolve("build/compose-previews/$previewId.png")
  if (direct.isFile) return direct
  val dir = direct.parentFile?.takeIf { it.isDirectory } ?: return null
  val prefix = "$previewId--"
  return dir
    .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".png") }
    ?.minByOrNull { it.name }
}
