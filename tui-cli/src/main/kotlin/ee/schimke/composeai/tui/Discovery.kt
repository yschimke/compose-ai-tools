package ee.schimke.composeai.tui

import ee.schimke.composeai.cli.PreviewInfo
import ee.schimke.composeai.cli.PreviewManifest
import ee.schimke.composeai.cli.PreviewModule
import java.io.File
import kotlinx.serialization.json.Json

/**
 * One row in the TUI's preview list — a (module, preview) pair plus the resolved PNG path that the
 * image pane will read. Lookups are by `id` within the currently-selected module; the row's own
 * `module` carries enough context to find the daemon descriptor and sidecar files later.
 */
data class PreviewRow(val module: PreviewModule, val info: PreviewInfo) {
  val id: String
    get() = info.id

  /**
   * Most recently rendered PNG for this preview, if any. Resolved at index-load time from the
   * conventional `<projectDir>/build/compose-previews/<id>.png` layout the gradle plugin and daemon
   * both write to. Returns null when no render has happened yet.
   */
  fun resolvePng(): File? {
    val direct = module.projectDir.resolve("build/compose-previews/${info.id}.png")
    if (direct.isFile) return direct
    // Multi-capture previews land as `<id>--<dimension>.png`; pick the lexicographically first
    // sibling. The TUI is per-preview, not per-capture, so we fall back to the simplest match.
    val dir = direct.parentFile ?: return null
    if (!dir.isDirectory) return null
    val prefix = "${info.id}--"
    return dir
      .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".png") }
      ?.minByOrNull { it.name }
  }

  /**
   * Conventional location of the daemon's per-preview accessibility overlay PNG (a half-frame
   * render with ATF findings annotated as red boxes). Present iff a11y has been fetched against the
   * live session.
   */
  fun resolveA11yOverlayPng(): File? =
    module.projectDir.resolve("build/compose-previews/data/${info.id}/a11y-overlay.png").takeIf {
      it.isFile
    }
}

/**
 * Filtered, navigable view onto every preview the discovery step found across the selected
 * module(s). Maintains a cursor — moving the cursor is what the arrow-key handler in the list pane
 * drives.
 *
 * Re-applies its filter when the user types into the search bar; the cursor sticks to the same
 * preview id when possible, otherwise clamps to the new bounds. This is what makes filter editing
 * feel non-destructive: typing more letters narrows the list around your selection instead of
 * resetting to the top.
 */
class PreviewIndex(
  initialRows: List<PreviewRow>,
  initialFilter: String? = null,
  initialExactId: String? = null,
) {
  private val all: List<PreviewRow> = initialRows
  private var filterText: String? = initialFilter
  private var exactId: String? = initialExactId
  private var cursor: Int = 0

  fun rows(): List<PreviewRow> = applyFilter(filterText, exactId)

  fun current(): PreviewRow? = rows().getOrNull(cursor)

  fun cursorIndex(): Int = cursor

  fun size(): Int = rows().size

  fun setFilter(text: String?) {
    val previousId = current()?.id
    filterText = text?.takeIf { it.isNotEmpty() }
    cursor = previousId?.let { id -> rows().indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
  }

  fun moveCursor(delta: Int) {
    val n = rows().size
    if (n == 0) {
      cursor = 0
      return
    }
    cursor = (cursor + delta).coerceIn(0, n - 1)
  }

  fun selectById(id: String) {
    val idx = rows().indexOfFirst { it.id == id }
    if (idx >= 0) cursor = idx
  }

  private fun applyFilter(filterText: String?, exactId: String?): List<PreviewRow> {
    if (exactId != null) return all.filter { it.id == exactId }
    if (filterText == null) return all
    return all.filter { it.id.contains(filterText, ignoreCase = true) }
  }

  companion object {
    /** Build a row list from a set of [PreviewModule]s by reading each module's `previews.json`. */
    fun loadRows(modules: List<PreviewModule>): List<PreviewRow> {
      val json = Json { ignoreUnknownKeys = true }
      val out = mutableListOf<PreviewRow>()
      for (module in modules) {
        val manifestFile =
          module.projectDir.resolve("build/compose-previews/previews.json").takeIf { it.isFile }
            ?: continue
        val manifest =
          try {
            json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
          } catch (_: Throwable) {
            continue
          }
        for (info in manifest.previews) {
          out += PreviewRow(module = module, info = info)
        }
      }
      return out
    }
  }
}
