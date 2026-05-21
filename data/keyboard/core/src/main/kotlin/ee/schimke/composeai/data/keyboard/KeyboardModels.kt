package ee.schimke.composeai.data.keyboard

/**
 * Stable identity of the `compose/keyboard` data product. Mirrors `Material3FocusProduct` /
 * `Material3AmbientProduct`: lifted out of the daemon-side registry so MCP clients and other
 * connectors can depend on the kind constant without pulling in the connector, Compose, or
 * Robolectric.
 *
 * Wire-shape `KeyboardOverride` lives alongside the other preview-override types on `daemon:core`
 * (see `ee.schimke.composeai.daemon.protocol.KeyboardOverride`); the override is what
 * `renderNow.overrides.keyboard` carries, and keeping it on `daemon:core` mirrors how
 * `AmbientOverride` / `WallpaperOverride` / `FocusOverride` are layered.
 */
object Material3KeyboardProduct {
  const val KIND: String = "compose/keyboard"
  const val SCHEMA_VERSION: Int = 1
}
