package ee.schimke.composeai.render.matrix

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One cell of a render matrix: the display-axis values distinguishing it from its siblings; a null
 * axis keeps the preview's default. Shared by the `render_matrix` MCP tool and the
 * `compose-preview render-matrix` CLI command so expansion, caps and labels stay identical.
 */
public data class MatrixCell(
  public val device: String? = null,
  public val locale: String? = null,
  public val uiMode: String? = null,
  public val fontScale: Float? = null,
) {
  /**
   * Typed [PreviewOverrides] for `renderNow`. An unrecognised `uiMode` throws, surfacing invalid
   * axis values instead of silently rendering the default.
   */
  public fun toOverrides(): PreviewOverrides =
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
  public fun overridesJson(): JsonObject = buildJsonObject {
    device?.let { put("device", it) }
    locale?.let { put("localeTag", it) }
    uiMode?.let { put("uiMode", it) }
    fontScale?.let { put("fontScale", it) }
  }

  /**
   * Compact human caption for contact-sheet tiles / CLI rows, e.g. `id:pixel_5 · ar · dark · 2.0x`;
   * `default` when no axis is set (the lone cell of an all-default matrix).
   */
  public val label: String
    get() {
      val parts = listOfNotNull(device, locale, uiMode, fontScale?.let { "${it}x" })
      return if (parts.isEmpty()) "default" else parts.joinToString(" · ")
    }
}

/**
 * Cross-product expansion and bounds for a render matrix, shared by the MCP tool and CLI command.
 */
public object MatrixAxes {
  /** Upper bound on matrix cells, so a careless cross-product can't fan out unboundedly. */
  public const val CELL_CAP: Int = 24

  /** Cells a cross-product of these axes would produce; an unset (null) axis contributes 1. */
  public fun cellCount(
    devices: List<String>?,
    locales: List<String>?,
    uiModes: List<String>?,
    fontScales: List<Float>?,
  ): Int =
    (devices?.size ?: 1) * (locales?.size ?: 1) * (uiModes?.size ?: 1) * (fontScales?.size ?: 1)

  /**
   * Expand the axes into the full cross-product in stable `device → locale → uiMode → fontScale`
   * order. A null axis contributes one "default" value.
   */
  public fun expand(
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
