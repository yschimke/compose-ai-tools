package ee.schimke.composeai.plugin

import java.util.Properties

/**
 * XR `*-testing` fake versions the plugin injects onto a consumer's `composePreviewRenderXr` render
 * classpath (see [AndroidPreviewSupport]). Baked into the jar by `generatePluginVersionResource` in
 * [gradle-plugin/build.gradle.kts] from the version catalog, so the render-path injection stays in
 * lockstep with `:renderer-xr` / the XR samples instead of drifting on hand-edited literals. Bump
 * once in `gradle/libs.versions.toml`.
 */
internal object XrFakeVersions {
  private val props: Properties by lazy {
    val p = Properties()
    val stream =
      XrFakeVersions::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/plugin/xr-fake-versions.properties")
        ?: error("xr-fake-versions.properties missing from plugin jar")
    stream.use { p.load(it) }
    p
  }

  private fun get(key: String): String =
    props.getProperty(key) ?: error("$key property missing from xr-fake-versions.properties")

  /** `androidx.xr.compose` line — also drives the `compose-testing` fake. */
  val compose: String
    get() = get("compose")

  val runtimeTesting: String
    get() = get("runtimeTesting")

  val scenecoreTesting: String
    get() = get("scenecoreTesting")

  val arcoreTesting: String
    get() = get("arcoreTesting")

  /**
   * Pinned release of the native `xr-composite` compositor — the `<version>` segment of the shared
   * cache the CLI populates and [AndroidPreviewSupport.xrCompositeCacheBinaryPath] reads.
   *
   * Deliberately NOT [PluginVersion]. Keying the cache by each side's own version forced an
   * `xr-composite-*.tar.gz` asset onto every release (226 of them, for 13 source changes) and made
   * every upgrade re-download an unchanged binary. Both sides bake the single `xr-composite`
   * catalog entry, so writer and reader cannot drift.
   */
  val composite: String
    get() = get("composite")
}
