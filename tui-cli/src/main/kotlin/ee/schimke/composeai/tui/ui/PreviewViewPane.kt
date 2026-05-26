package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.tui.PreviewIndex
import ee.schimke.composeai.tui.image.AnsiImage
import java.io.File

/**
 * Centre pane: ASCII-rendered preview image. The image is read from disk every recomposition (cheap
 * — Mosaic only recomposes on actual state changes), so a daemon notification that arrives while
 * the user has the preview pinned re-renders without any glue.
 *
 * Uses [AnsiImage.renderAscii] rather than the truecolor `render` because embedding raw SGR escapes
 * in `Text` would break Mosaic's width tracking. See `tui-cli/LIMITATIONS.md` for the fork-shaped
 * escape hatch (a `RawText` composable that bypasses width measurement, or a
 * `Modifier.background(rgbColor)` per cell on a 2-pixel-high grid).
 *
 * When the PNG is missing — for example because live mode is OFF and `composePreviewRenderAll` has
 * never been run for this module — the pane shows a one-line "no render yet" hint instead of a
 * blank rectangle. This is also the path you hit if the user is on a module whose `previews.json`
 * lists a preview that's been removed from source since the last render.
 */
@Composable
fun PreviewViewPane(index: PreviewIndex, focused: Boolean, width: Int, rows: Int, tick: Long) {
  val current = index.current()
  Column(modifier = Modifier.width(width).height(rows)) {
    Text(
      "Preview".padEnd(width).take(width),
      textStyle = if (focused) TextStyle.Bold + TextStyle.Invert else TextStyle.Bold,
    )
    if (current == null) {
      Text("(no selection)".take(width), textStyle = TextStyle.Dim)
      return@Column
    }
    Text(current.id.padEnd(width).take(width), textStyle = TextStyle.Italic)
    val png: File? = remember(current.id, tick) { current.resolvePng() }
    if (png == null) {
      Text(
        "(no render yet — toggle Live mode (L) or run composePreviewRenderAll)".take(width),
        textStyle = TextStyle.Dim,
      )
      return@Column
    }
    val art =
      remember(png.path, png.lastModified(), width, rows) {
        AnsiImage.renderAscii(png, maxCols = width, maxRows = rows - 2)
      }
    if (art.isEmpty()) {
      Text("(failed to decode ${png.name})".take(width), textStyle = TextStyle.Dim)
    } else {
      for (line in art) Text(line.padEnd(width).take(width))
    }
  }
}
