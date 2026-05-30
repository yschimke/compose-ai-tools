package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.tui.LiveSession
import ee.schimke.composeai.tui.PreviewIndex

/**
 * Wide layout: 3 columns side-by-side. Column widths are computed from the terminal width:
 * * list pane — 28 cols (fits a long preview id without truncation on most projects)
 * * data pane — 36 cols (key=value style a11y rows, the longer of the two side panels)
 * * preview pane — whatever's left after a 1-cell gap on each side, minimum 30
 *
 * If the terminal turns out to be narrower than the wide breakpoint after construction (e.g. the
 * user widened the terminal *just* past 120 and then immediately resized back), the preview pane
 * clamps to a minimum so the image doesn't collapse to zero-width — the App composable will flip to
 * NarrowLayout on the next recomposition anyway.
 */
@Composable
fun WideLayout(
  index: PreviewIndex,
  liveSession: LiveSession,
  focusedPane: Int,
  cols: Int,
  rows: Int,
  tick: Long,
) {
  val listWidth = 28
  val dataWidth = 36
  val previewWidth = (cols - listWidth - dataWidth - 2).coerceAtLeast(30)
  Row {
    PreviewListPane(index = index, focused = focusedPane == 0, width = listWidth, rows = rows)
    Spacer(Modifier.width(1))
    PreviewViewPane(
      index = index,
      focused = focusedPane == 1,
      width = previewWidth,
      rows = rows,
      tick = tick,
    )
    Spacer(Modifier.width(1))
    DataPane(
      index = index,
      liveSession = liveSession,
      focused = focusedPane == 2,
      width = dataWidth,
      rows = rows,
      tick = tick,
    )
  }
}

/**
 * Narrow layout: one full-width pane at a time with a tab strip at the top. Tabs are driven by the
 * same `focusedPane` int the wide layout uses, so the user's left/right key presses have the same
 * semantic on both layouts — only the visual presentation changes.
 */
@Composable
fun NarrowLayout(
  index: PreviewIndex,
  liveSession: LiveSession,
  focusedPane: Int,
  cols: Int,
  rows: Int,
  tick: Long,
) {
  Column {
    TabStrip(focused = focusedPane)
    Spacer(Modifier.height(1))
    when (focusedPane) {
      0 -> PreviewListPane(index = index, focused = true, width = cols, rows = rows - 2)
      1 ->
        PreviewViewPane(index = index, focused = true, width = cols, rows = rows - 2, tick = tick)
      else ->
        DataPane(
          index = index,
          liveSession = liveSession,
          focused = true,
          width = cols,
          rows = rows - 2,
          tick = tick,
        )
    }
  }
}

@Composable
private fun TabStrip(focused: Int) {
  val tabs = listOf("[1] List", "[2] Preview", "[3] Data")
  Row {
    tabs.forEachIndexed { i, label ->
      val style = if (i == focused) TextStyle.Bold + TextStyle.Invert else TextStyle.Dim
      Text(" $label ", textStyle = style)
      Text(" ")
    }
  }
}
