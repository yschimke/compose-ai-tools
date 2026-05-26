package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.tui.PreviewIndex
import ee.schimke.composeai.tui.image.Bitmaps
import java.io.File

/**
 * Centre pane: rendered preview image. The PNG is decoded into a [com.jakewharton.mosaic.ui.Bitmap]
 * and handed to the fork's [Image] composable, which picks the best rendering tier for the host
 * terminal — Kitty Graphics Protocol (kitty / ghostty / WezTerm), truecolor half-block (most modern
 * terminals + tmux), or brightness-ramp ASCII (dumb terminals, snapshot harnesses).
 *
 * The bitmap is re-decoded whenever the file's mtime changes, so a daemon notification or a vim
 * write that arrives while the user has this preview pinned recomposes the pane automatically.
 *
 * When the PNG is missing — for example because live mode is OFF and `composePreviewRenderAll` has
 * never been run for this module — the pane shows a one-line "no render yet" hint. Same path when
 * `previews.json` lists an id whose source has since been removed.
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
    val imageRows = (rows - 2).coerceAtLeast(1)
    val png: File? = remember(current.id, tick) { current.resolvePng() }
    if (png == null) {
      Text(
        "(no render yet — toggle Live mode (L) or run composePreviewRenderAll)".take(width),
        textStyle = TextStyle.Dim,
      )
      return@Column
    }
    val bitmap = remember(png.path, png.lastModified()) { Bitmaps.readPng(png) }
    if (bitmap == null) {
      Text("(failed to decode ${png.name})".take(width), textStyle = TextStyle.Dim)
    } else {
      Image(bitmap = bitmap, cellWidth = width, cellHeight = imageRows)
    }
  }
}
