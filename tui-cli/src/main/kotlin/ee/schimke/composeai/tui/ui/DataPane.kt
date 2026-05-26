package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.AccessibilityReport
import ee.schimke.composeai.tui.LiveSession
import ee.schimke.composeai.tui.PreviewIndex
import kotlinx.serialization.json.Json

/**
 * Right pane (wide) / third tab (narrow): structured data products for the selected preview. The
 * MVP surface is a11y findings — those are the highest-signal artefact the daemon produces and the
 * one most agents reach for after a layout regression. Adding new data products is intentionally a
 * copy-paste of one of the sections below: each section is a pre-grouped list of `(impact, summary,
 * locator)` tuples plus a header.
 *
 * a11y findings load via the standalone-Gradle aggregated `accessibility.json` even when live mode
 * is OFF. When live mode is ON the file is re-read on each daemon notification, so findings update
 * without a manual `r`. The "preview-data inline payload" path (calling
 * `session.fetchData(previewId, "a11y/atf", inline = true)` directly) is documented in
 * `tui-cli/LIMITATIONS.md` under "structured data fetched inline rather than via sidecar" as a
 * follow-up — the disk path keeps live and dead modes symmetric for now.
 */
@Composable
fun DataPane(
  index: PreviewIndex,
  liveSession: LiveSession,
  focused: Boolean,
  width: Int,
  rows: Int,
  tick: Long,
) {
  val current = index.current()
  Column(modifier = Modifier.width(width).height(rows)) {
    Text(
      "Data".padEnd(width).take(width),
      textStyle = if (focused) TextStyle.Bold + TextStyle.Invert else TextStyle.Bold,
    )
    if (current == null) {
      Text("(no selection)".take(width), textStyle = TextStyle.Dim)
      return@Column
    }

    val a11y =
      remember(current.module.gradlePath, current.id, tick) {
        loadA11yEntry(current.module.projectDir, current.id)
      }

    Text("─ a11y ".padEnd(width, '─').take(width), textStyle = TextStyle.Dim)
    if (a11y == null) {
      Text("(no findings; enable Live mode (L) to fetch)".take(width), textStyle = TextStyle.Dim)
    } else if (a11y.findings.isEmpty()) {
      Text("✓ no findings".take(width))
    } else {
      val budget = (rows - 5).coerceAtLeast(2)
      for (finding in a11y.findings.take(budget)) {
        val level = finding.level.uppercase()
        val style =
          when (level) {
            "ERROR" -> TextStyle.Bold
            "WARNING",
            "WARN" -> TextStyle.Italic
            else -> TextStyle.Unspecified
          }
        val line = "$level: ${finding.type} — ${finding.message}".take(width)
        Text(line, textStyle = style)
      }
      if (a11y.findings.size > budget) {
        Text("… ${a11y.findings.size - budget} more".take(width), textStyle = TextStyle.Dim)
      }
    }
  }
}

private val json = Json { ignoreUnknownKeys = true }

private fun loadA11yEntry(projectDir: java.io.File, previewId: String): AccessibilityEntry? {
  val file = projectDir.resolve("build/compose-previews/accessibility.json")
  if (!file.isFile) return null
  return try {
    val report = json.decodeFromString(AccessibilityReport.serializer(), file.readText())
    report.entries.firstOrNull { it.previewId == previewId }
  } catch (_: Throwable) {
    null
  }
}
