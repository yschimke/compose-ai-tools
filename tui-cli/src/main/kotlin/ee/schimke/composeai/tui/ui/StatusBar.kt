package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.tui.LiveSession

/**
 * Single-line status header: layout mode + terminal size, live-mode toggle state, filter, and a row
 * count. Visual conventions:
 * * Live mode label is bold-Inverted when ON to make the sticky state obvious from across the room.
 *   The previous design tried red text, but on terminals that don't render colour the OFF/ON
 *   distinction disappeared — Invert is the most universally legible style.
 * * Filter shows the active filter string (or `id=…` for `--id`) when editing is closed; when the
 *   user is mid-edit it's prefixed with `/` and ends in a `_` caret so it looks like a vim search
 *   prompt.
 * * Errors from the live session (failed daemon open, missing descriptor) appear in italic after
 *   the live label so the user knows why their toggle didn't go to READY.
 */
@Composable
fun StatusBar(
  isWide: Boolean,
  cols: Int,
  rows: Int,
  liveOn: Boolean,
  liveStatus: LiveSession.Status,
  liveError: String?,
  filterEditing: Boolean,
  filterDraft: String,
  currentFilter: String?,
  countShown: Int,
) {
  Row {
    Text("compose-preview-tui", textStyle = TextStyle.Bold)
    Text("  ")
    Text("${cols}×${rows}  ")
    Text(if (isWide) "[wide]" else "[tabs]", textStyle = TextStyle.Dim)
    Text("  ")
    val liveLabel =
      when {
        liveOn && liveStatus == LiveSession.Status.READY -> "LIVE"
        liveOn && liveStatus == LiveSession.Status.OPENING -> "live…"
        liveOn && liveStatus == LiveSession.Status.FAILED -> "live!"
        else -> "live off"
      }
    Text(
      liveLabel,
      textStyle =
        if (liveOn && liveStatus == LiveSession.Status.READY) TextStyle.Invert + TextStyle.Bold
        else TextStyle.Dim,
    )
    if (liveError != null && liveOn) {
      Text("  ")
      Text(liveError, textStyle = TextStyle.Italic + TextStyle.Dim)
    }
    Text("  ")
    Text("$countShown previews", textStyle = TextStyle.Dim)
    Text("  ")
    when {
      filterEditing -> Text("/${filterDraft}_", textStyle = TextStyle.Italic)
      currentFilter != null -> Text("filter=${currentFilter}", textStyle = TextStyle.Italic)
      else -> Text("/=filter L=live r=render q=quit", textStyle = TextStyle.Dim)
    }
  }
}
