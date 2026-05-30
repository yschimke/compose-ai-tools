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

/** One paged preview in the bundle view: its id (may be null for a bare PNG) and decoded image. */
private data class BundlePage(val id: String?, val bitmap: Bitmap?)

/**
 * Bundle-PNG entry point. Renders the bundle's previews full-screen with minimal chrome (the image
 * plus a one-line footer when there's more than one preview).
 *
 * Since bundle schema v2 the zip carries a baked PNG per preview under `previews/<id>.png`, so this
 * works **fully detached from the originating project** — opening a bundle from `~/Downloads` lets
 * you page through every preview with the arrow keys, no Gradle checkout required. When the bundle
 * does name a module and we're inside its project on disk, the daemon is additionally attached and
 * later renders of the *current* preview replace the baked image live as the source is edited. An
 * older v1 bundle (or a `--no-render` pack) falls back to the single cover image decoded from the
 * polyglot's leading bytes.
 *
 * `q` / `Esc` quits; `←/→` (also `h/l`, `p/n`, `↑/↓`, `j/k`) page between previews; `r` forces a
 * re-render of the current preview (no-op without a live session).
 */
fun runBundle(png: File, projectRoot: File?, args: TuiArgs) {
  val contents = BundlePngMetadata.readContents(png)
  val metadata = contents.metadata

  // Prefer the baked per-preview PNGs (v2). Fall back to the single cover image decoded from the
  // polyglot's leading bytes for v1 bundles / bare PNGs so old artefacts still open.
  val pages =
    if (contents.previews.isNotEmpty()) {
      contents.previews.map { BundlePage(id = it.id, bitmap = Bitmaps.decode(it.pngBytes)) }
    } else {
      listOf(BundlePage(id = metadata?.coverPreviewId, bitmap = Bitmaps.readPng(png)))
    }

  // `bundle.json` tells us which module produced this PNG. Live re-render also needs the source
  // project on disk: without a project root (we're not inside a Gradle checkout) there's no daemon
  // to attach, so we just page through the baked images statically.
  val module =
    if (projectRoot != null && metadata != null && metadata.modulePath.isNotEmpty()) {
      // Mirror the synthetic module Main builds for `--no-discovery`: <projectRoot>/<path-as-dirs>.
      val relative = metadata.modulePath.trimStart(':').replace(':', '/').ifEmpty { "." }
      PreviewModule(gradlePath = metadata.modulePath, projectDir = File(projectRoot, relative))
    } else {
      null
    }

  runMosaicMain {
    BundleApp(pages = pages, pngName = png.name, module = module, extensions = args.extensions)
  }
}

/**
 * The whole bundle-mode UI: a single full-terminal image with an optional one-line footer. Holds an
 * optional [LiveSession] and swaps the current page's baked image for the daemon's freshest render
 * of the selected preview whenever a notification ticks.
 */
@Composable
private fun BundleApp(
  pages: List<BundlePage>,
  pngName: String,
  module: PreviewModule?,
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
  var index by remember { mutableStateOf(0) }

  val current = pages.getOrElse(index) { pages.first() }
  val currentId = current.id

  // Attach the daemon once, if the bundle named a module. The session is sticky for the run.
  LaunchedEffect(module) { if (module != null) liveSession.enable(module, extensions) }
  // Mark the current preview visible once the session is READY so the daemon pushes its re-renders.
  LaunchedEffect(liveState.status, currentId) {
    if (currentId != null && liveState.status == LiveSession.Status.READY) {
      liveSession.setVisible(currentId)
    }
  }

  if (exitRequested) {
    LaunchedEffect(Unit) { kotlin.system.exitProcess(0) }
    return
  }

  // Prefer the daemon's freshest render of the current preview; fall back to the baked image until
  // (or unless) one exists. Re-resolved on every daemon tick / page change.
  val liveFrame: Bitmap? =
    if (module != null && currentId != null) {
      remember(liveState.tick, currentId) {
        resolveRenderedPng(module, currentId)?.let(Bitmaps::readPng)
      }
    } else {
      null
    }
  val bitmap = liveFrame ?: current.bitmap

  val multi = pages.size > 1
  val imageRows = if (multi) (rows - 1).coerceAtLeast(1) else rows

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
            if (currentId != null) scope.launch { liveSession.forceRender(currentId) }
            true
          }
          "Right",
          "l",
          "n",
          "Down",
          "j" -> {
            if (multi) index = (index + 1) % pages.size
            true
          }
          "Left",
          "h",
          "p",
          "Up",
          "k" -> {
            if (multi) index = (index - 1 + pages.size) % pages.size
            true
          }
          else -> false
        }
      }
  ) {
    if (bitmap == null) {
      val label = currentId ?: pngName
      Text("(failed to decode $label)".take(cols), textStyle = TextStyle.Dim)
    } else {
      Image(bitmap = bitmap, cellWidth = cols, cellHeight = imageRows)
    }
    if (multi) {
      val name = currentId ?: pngName
      val footer = "  $name  (${index + 1}/${pages.size})   ←/→ page · q quit"
      Text(footer.take(cols).padEnd(cols), textStyle = TextStyle.Dim)
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
