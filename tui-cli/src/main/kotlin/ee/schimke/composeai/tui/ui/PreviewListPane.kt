package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.tui.PreviewIndex

/**
 * Left pane: scrolling list of preview rows. The active row is rendered with `Invert+Bold` so it
 * stays legible regardless of terminal theme; module-cross rows are separated by a faint heading
 * line so a multi-module project doesn't look like one indistinguishable scroll.
 *
 * Scrolls when the cursor would leave the visible window by recentring on the cursor — a half-
 * window slack keeps context above/below visible. We don't draw a scrollbar; the `n of m` counter
 * at the top is the entire scroll affordance, intentional given how cramped this pane gets in
 * narrow layouts.
 */
@Composable
fun PreviewListPane(index: PreviewIndex, focused: Boolean, width: Int, rows: Int) {
  val all = index.rows()
  val cursor = index.cursorIndex().coerceAtMost(all.lastIndex.coerceAtLeast(0))
  val visibleRows = (rows - 2).coerceAtLeast(1)
  val first =
    when {
      all.size <= visibleRows -> 0
      cursor < visibleRows / 2 -> 0
      cursor >= all.size - visibleRows / 2 -> (all.size - visibleRows).coerceAtLeast(0)
      else -> cursor - visibleRows / 2
    }
  val last = minOf(first + visibleRows, all.size)

  Column(modifier = Modifier.width(width).height(rows)) {
    val header =
      "Previews ${if (all.isEmpty()) "" else "${cursor + 1}/${all.size}"}".padEnd(width).take(width)
    Text(header, textStyle = if (focused) TextStyle.Bold + TextStyle.Invert else TextStyle.Bold)

    if (all.isEmpty()) {
      Text("(no previews)".take(width), textStyle = TextStyle.Dim)
      return@Column
    }

    var lastModule: String? = null
    for (i in first until last) {
      val row = all[i]
      if (row.module.gradlePath != lastModule) {
        lastModule = row.module.gradlePath
        val label = "── ${row.module.gradlePath} ".padEnd(width, '─').take(width)
        Text(label, textStyle = TextStyle.Dim)
      }
      val marker = if (i == cursor) "▶ " else "  "
      val label = (marker + row.id).padEnd(width).take(width)
      val style =
        when {
          i == cursor && focused -> TextStyle.Bold + TextStyle.Invert
          i == cursor -> TextStyle.Bold
          else -> TextStyle.Unspecified
        }
      Text(label, textStyle = style)
    }
  }
}
