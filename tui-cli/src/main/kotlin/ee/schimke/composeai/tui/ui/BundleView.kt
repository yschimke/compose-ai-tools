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

/**
 * Bundle-PNG entry point. Renders ONE preview full-screen with no list / tab / status chrome — just
 * the image — and **assumes no project context**: a bundle is self-contained, so showing it must not
 * depend on a surrounding Gradle checkout.
 *
 * The PNG handed on the command line is decoded and shown immediately as the first frame (ImageIO
 * reads the leading PNG of the PNG+ZIP polyglot, ignoring the appended zip). Then, if this is a real
 * bundle (carries `classes/app.jar` + `previews.json`) and the daemon/renderer sidecars ship in this
 * install, the bundle's **own daemon** is spawned from those embedded classes and the live frame
 * replaces the seed. There is no source tree to watch project-less, so "live" here means the daemon
 * is held open for `r`-driven re-renders (paused-clock animations step, deterministic re-render).
 *
 * Falls back to the static seed image when: the file is a plain PNG (no provenance), the sidecars
 * aren't present (e.g. run from source without `installDist`), or the daemon fails to start.
 *
 * `q` / `Esc` quits; `r` forces a re-render (no-op without a live session).
 */
fun runBundle(png: File, args: TuiArgs) {
  // Decode the bundle's own image up front so the very first composed frame already shows it — no
  // "rendering…" flash before the daemon attaches.
  val seed = Bitmaps.readPng(png)

  val coverPreviewId = BundlePngMetadata.readOrNull(png)?.coverPreviewId
  val modulePath = BundlePngMetadata.readOrNull(png)?.modulePath ?: ":bundle"

  // Build a project-less opener iff this is a real bundle AND the sidecars are on disk. Extraction
  // happens here (once) so the temp dir's lifetime spans the whole session; a shutdown hook cleans
  // it up. `opener` is null → static seed only.
  val extracted = BundleExtractor.extract(png)
  val sidecars = BundleSidecars.locate()
  val opener: (() -> RenderSession)? =
    if (extracted != null && sidecars != null && coverPreviewId != null) {
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
      seed = seed,
      pngName = png.name,
      previewId = coverPreviewId,
      modulePath = modulePath,
      extensions = args.extensions,
      opener = opener,
    )
  }
}

/**
 * The whole bundle-mode UI: a single full-terminal image. When [opener] is non-null it attaches the
 * bundle's daemon via [LiveSession.enableBundle] and shows the daemon's freshest render of
 * [previewId] (read from the path the daemon reports, never an assumed project layout); otherwise it
 * shows the static [seed].
 */
@Composable
private fun BundleApp(
  seed: Bitmap?,
  pngName: String,
  previewId: String?,
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

  // Attach the bundle's own daemon once, if we have an opener. Sticky for the run.
  LaunchedEffect(opener) {
    if (opener != null) liveSession.enableBundle(modulePath, extensions, opener)
  }
  // Once READY, mark the preview visible and kick one render so the daemon frame replaces the seed
  // without the user pressing `r`.
  LaunchedEffect(liveState.status, previewId) {
    if (previewId != null && liveState.status == LiveSession.Status.READY) {
      liveSession.setVisible(previewId)
      liveSession.forceRender(previewId)
    }
  }

  if (exitRequested) {
    LaunchedEffect(Unit) { kotlin.system.exitProcess(0) }
    return
  }

  // Prefer the daemon's freshest render of this preview, read from the path it reported on the last
  // `renderFinished`; fall back to the seed PNG until one exists. Re-decoded only when the path
  // changes.
  val livePngPath = previewId?.let { liveState.lastPng[it] }
  val liveFrame: Bitmap? = remember(livePngPath) { livePngPath?.let { Bitmaps.readPng(File(it)) } }
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
