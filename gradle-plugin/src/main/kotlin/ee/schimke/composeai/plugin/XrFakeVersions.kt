package ee.schimke.composeai.plugin

import java.util.Properties

/**
 * XR `*-testing` fake versions the plugin injects onto a consumer's
 * `composePreviewRenderXr` render classpath (see [AndroidPreviewSupport]). Baked into the jar by
 * `generatePluginVersionResource` in [gradle-plugin/build.gradle.kts] from the version catalog, so
 * the render-path injection stays in lockstep with `:renderer-xr` / the XR samples instead of
 * drifting on hand-edited literals. Bump once in `gradle/libs.versions.toml`.
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
}
