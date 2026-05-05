package ee.schimke.composeai.data.wallpaper

import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/wallpaper` data product. Lifted out of
 * `WallpaperDataProductRegistry` so MCP clients and other connectors can depend on the payload
 * schema without pulling in the daemon-side registry, Compose Material3, or material-kolor.
 */
object Material3WallpaperProduct {
  const val KIND: String = "compose/wallpaper"
  const val SCHEMA_VERSION: Int = 1
}

/** Wire-shape returned by `data/fetch?kind=compose/wallpaper`. */
@Serializable
data class WallpaperPayload(
  /** Canonical seed color hex string (`#AARRGGBB`). */
  val seedColor: String,
  /** Whether the derived scheme is the dark variant. */
  val isDark: Boolean,
  /** Palette algorithm the connector applied. */
  val paletteStyle: WallpaperPaletteStyle = WallpaperPaletteStyle.TONAL_SPOT,
  /** Effective contrast level in `[-1.0, 1.0]`. */
  val contrastLevel: Double = 0.0,
  /** Material 3 color roles derived from [seedColor]; matches the schema of `compose/theme`. */
  val derivedColorScheme: Map<String, String>,
)
