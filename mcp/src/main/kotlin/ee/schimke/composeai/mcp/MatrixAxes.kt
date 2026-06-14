package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One cell of a render matrix: the display-axis values that distinguish it from its siblings.
 *
 * Shared by the `render_matrix` MCP tool and the `compose-preview render-matrix` CLI command
 * (issue #1788) so the cross-product expansion, cell cap, override mapping, and human labels stay
 * identical across both surfaces. A null axis means "leave at the preview's default" for that
 * dimension.
 */
data class MatrixCell(
  val device: String? = null,
  val locale: String? = null,
  val uiMode: String? = null,
  val fontScale: Float? = null,
) {
  /**
   * Typed [PreviewOverrides] for this cell, ready for `renderNow`. `uiMode` is parsed to [UiMode];
   * an unrecognised value throws so the caller surfaces "invalid axis values" rather than rendering
   * with a silent default.
   */
  fun toOverrides(): PreviewOverrides =
    PreviewOverrides(
      device = device,
      localeTag = locale,
      fontScale = fontScale,
      uiMode =
        uiMode?.let {
          when (it.lowercase()) {
            "light" -> UiMode.LIGHT
            "dark" -> UiMode.DARK
            else -> error("uiMode must be 'light' or 'dark', got '$it'")
          }
        },
    )

  /**
   * The cell's overrides echoed as the same wire JSON `render_preview.overrides` accepts, so an
   * agent can replay a single cell with `render_preview` to fetch its pixels.
   */
  fun overridesJson(): JsonObject = buildJsonObject {
    device?.let { put("device", it) }
    locale?.let { put("localeTag", it) }
    uiMode?.let { put("uiMode", it) }
    fontScale?.let { put("fontScale", it) }
  }

  /**
   * Compact human caption for contact-sheet tiles / CLI rows, e.g. `id:pixel_5 · ar · dark · 2.0x`;
   * `default` when no axis is set (the lone cell of an all-default matrix).
   */
  val label: String
    get() {
      val parts = listOfNotNull(device, locale, uiMode, fontScale?.let { "${it}x" })
      return if (parts.isEmpty()) "default" else parts.joinToString(" · ")
    }
}

/**
 * Cross-product expansion + bounds for a render matrix — the single source of truth for both the
 * MCP `render_matrix` tool and the CLI `render-matrix` command (issue #1788).
 */
object MatrixAxes {
  /** Upper bound on matrix cells, so a careless cross-product can't fan out unboundedly. */
  const val CELL_CAP = 24

  /** Cells a cross-product of these axes would produce; an unset (null) axis contributes 1. */
  fun cellCount(
    devices: List<String>?,
    locales: List<String>?,
    uiModes: List<String>?,
    fontScales: List<Float>?,
  ): Int =
    (devices?.size ?: 1) * (locales?.size ?: 1) * (uiModes?.size ?: 1) * (fontScales?.size ?: 1)

  /**
   * Expand the axes into the full cross-product in stable `device → locale → uiMode → fontScale`
   * order, so cell ordering is deterministic. An unset (null) axis contributes a single "leave at
   * default" value, so e.g. `uiModes=[light,dark]` with every other axis null yields two cells.
   */
  fun expand(
    devices: List<String>?,
    locales: List<String>?,
    uiModes: List<String>?,
    fontScales: List<Float>?,
  ): List<MatrixCell> = buildList {
    for (device in devices ?: listOf<String?>(null)) for (locale in
      locales ?: listOf<String?>(null)) for (uiMode in
      uiModes ?: listOf<String?>(null)) for (fontScale in fontScales ?: listOf<Float?>(null)) {
      add(MatrixCell(device = device, locale = locale, uiMode = uiMode, fontScale = fontScale))
    }
  }
}
