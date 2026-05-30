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
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import ee.schimke.composeai.tui.BundleExtractor
import ee.schimke.composeai.tui.BundlePngMetadata
import ee.schimke.composeai.tui.BundleSidecars
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
 * plus a one-line footer when there's more than one preview), and **assumes no project context**: a
 * bundle is self-contained, so showing it must not depend on a surrounding Gradle checkout.
 *
 * Since bundle schema v2 the zip carries a baked PNG per preview under `previews/<id>.png`, so this
 * works **fully detached from the originating project** — opening a bundle from `~/Downloads` lets
 * you page through every preview with the arrow keys, no Gradle checkout required. An older v1 bundle
 * (or a `--no-render` pack) falls back to the single cover image decoded from the polyglot's leading
 * bytes.
 *
 * If this is a real bundle (carries `classes/app.jar` + `previews.json`) and the daemon/renderer
 * sidecars ship in this install, the bundle's **own daemon** is spawned from those embedded classes;
 * the daemon's freshest render of the current preview then replaces its baked image live (read from
 * the path the daemon reports, never an assumed project layout), and `r` forces a re-render
 * (paused-clock animations step, deterministic re-render). Falls back to the static baked images
 * when the file is a plain PNG (no provenance), the sidecars aren't present (e.g. run from source
 * without `installDist`), or the daemon fails to start.
 *
 * `q` / `Esc` quits; `←/→` (also `h/l`, `p/n`, `↑/↓`, `j/k`) page between previews; `r` forces a
 * re-render of the current preview (no-op without a live session).
 */
fun runBundle(png: File, args: TuiArgs) {
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

  val modulePath = metadata?.modulePath?.takeIf { it.isNotEmpty() } ?: ":bundle"

  // Build a project-less opener iff this is a real bundle AND the sidecars are on disk. Extraction
  // happens here (once) so the temp dir's lifetime spans the whole session; a shutdown hook cleans
  // it up. `opener` is null → static baked images only.
  val extracted = BundleExtractor.extract(png)
  val sidecars = BundleSidecars.locate()
  val opener: (() -> RenderSession)? =
    if (extracted != null && sidecars != null && metadata?.coverPreviewId != null) {
      Runtime.getRuntime()
        .addShutdownHook(Thread { runCatching { extracted.workDir.deleteRecursively() } })
      val open: () -> RenderSession = {
        SubprocessRenderSessions.openBundleDaemon(
          daemonClasspath = sidecars.classpath(),
          classesDir = extracted.classesDir,
          previewsJson = extracted.previewsJson,
          workspaceRoot = extracted.workDir,
          modulePath = modulePath,
        )
      }
      open
    } else {
      extracted?.workDir?.deleteRecursively()
      null
    }

  runMosaicMain {
    BundleApp(
      pages = pages,
      pngName = png.name,
      modulePath = modulePath,
      extensions = args.extensions,
      opener = opener,
    )
  }
}

/**
 * The whole bundle-mode UI: a single full-terminal image with an optional one-line footer. When
 * [opener] is non-null it attaches the bundle's own daemon via [LiveSession.enableBundle] and swaps
 * the current page's baked image for the daemon's freshest render of the selected preview (read from
 * the path the daemon reports, never an assumed project layout) whenever a notification ticks.
 */
@Composable
private fun BundleApp(
  pages: List<BundlePage>,
  pngName: String,
  modulePath: String,
  extensions: Set<String>,
  opener: (() -> RenderSession)?,
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

  // Attach the bundle's own daemon once, if we have an opener. Sticky for the run.
  LaunchedEffect(opener) {
    if (opener != null) liveSession.enableBundle(modulePath, extensions, opener)
  }
  // Once READY (and on each page change), mark the current preview visible and kick one render so
  // the daemon frame replaces the baked image without the user pressing `r`.
  LaunchedEffect(liveState.status, currentId) {
    if (currentId != null && liveState.status == LiveSession.Status.READY) {
      liveSession.setVisible(currentId)
      liveSession.forceRender(currentId)
    }
  }

  if (exitRequested) {
    LaunchedEffect(Unit) { kotlin.system.exitProcess(0) }
    return
  }

  // Prefer the daemon's freshest render of the current preview, read from the path it reported on
  // the last `renderFinished`; fall back to the baked image until one exists. Re-decoded only when
  // the path changes.
  val livePngPath = currentId?.let { liveState.lastPng[it] }
  val liveFrame: Bitmap? = remember(livePngPath) { livePngPath?.let { Bitmaps.readPng(File(it)) } }
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
