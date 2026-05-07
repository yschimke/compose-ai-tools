package ee.schimke.composeai.data.focus

/**
 * Stable identity of the `compose/focus` data product. Mirrors `Material3AmbientProduct` /
 * `Material3WallpaperProduct`: lifted out of the daemon-side registry so MCP clients and other
 * connectors can depend on the kind constant without pulling in the connector, Compose, or
 * Robolectric.
 *
 * Wire-shape `FocusOverride` and the matching `FocusDirection` enum live alongside the other
 * preview-override types on `daemon:core` (see
 * `ee.schimke.composeai.daemon.protocol.FocusOverride`); the override is what
 * `renderNow.overrides.focus` carries, and keeping it on `daemon:core` mirrors how
 * `AmbientOverride` / `WallpaperOverride` / `Material3ThemeOverrides` are layered.
 */
object Material3FocusProduct {
  const val KIND: String = "compose/focus"
  const val SCHEMA_VERSION: Int = 1
}
