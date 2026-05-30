package ee.schimke.composeai.tui.ui

import com.jakewharton.mosaic.renderMosaic
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Text
import ee.schimke.composeai.tui.BundlePngMetadata
import ee.schimke.composeai.tui.image.Bitmaps
import ee.schimke.composeai.tui.terminal.TerminalSize
import java.io.File

/**
 * Non-interactive bundle dump: render every baked preview in [png] to stdout as text and return.
 *
 * This is the headless counterpart of [runBundle]. Where the interactive view takes over the
 * terminal (raw mode, alternate screen, a render loop), the dump uses Mosaic's one-shot
 * [renderMosaic] to turn each preview's `Image` into a static string and prints it — no PTY, no raw
 * mode, no daemon. That's what makes it usable from a CI step whose stdout is a plain pipe: the
 * `Image` composable picks its lowest-fidelity tier (half-block / ASCII) when no Kitty-graphics
 * capability is advertised, so the result "won't look great but technically works" in a build log.
 *
 * Reads the bundle's baked `previews/<id>.png` entries (schema v2+), so it works fully detached from
 * the originating project. Prints a short notice and returns cleanly when the file carries no baked
 * previews (e.g. an older v1 bundle, or one packed with `--no-render`).
 */
fun dumpBundle(png: File, cols: Int? = null, rowsPerPreview: Int = DEFAULT_DUMP_ROWS) {
  val contents = BundlePngMetadata.readContents(png)
  val width = (cols ?: TerminalSize.probe().cols).coerceIn(MIN_DUMP_COLS, MAX_DUMP_COLS)

  if (contents.previews.isEmpty()) {
    println("${png.name}: no baked previews found (needs a schema v2 bundle packed after a render).")
    return
  }

  val total = contents.previews.size
  println("${png.name}: $total preview(s)")
  contents.previews.forEachIndexed { index, preview ->
    println()
    println("=== ${preview.id} (${index + 1}/$total) ===")
    val bitmap = Bitmaps.decode(preview.pngBytes)
    if (bitmap == null) {
      println("(failed to decode previews/${preview.id}.png)")
      return@forEachIndexed
    }
    // renderMosaic composes a single frame and returns it as a string; the Image composable owns
    // the resample + tier selection (half-block / ASCII fallback when no graphics protocol). Guard
    // per-preview so a single render hiccup prints a note instead of aborting the whole dump.
    val frame =
      try {
        renderMosaic {
          Column { Image(bitmap = bitmap, cellWidth = width, cellHeight = rowsPerPreview) }
        }
      } catch (t: Throwable) {
        "(could not render ${preview.id}: ${t.javaClass.simpleName}: ${t.message})\n"
      }
    print(frame)
    if (!frame.endsWith("\n")) println()
  }
}

private const val DEFAULT_DUMP_ROWS = 20
private const val MIN_DUMP_COLS = 20
private const val MAX_DUMP_COLS = 120
