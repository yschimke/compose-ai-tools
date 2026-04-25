package ee.schimke.composeai.plugin

import java.util.Properties

/**
 * Plugin version baked into the jar by `generatePluginVersionResource` in
 * [gradle-plugin/build.gradle.kts]. Used to pick the matching published `renderer-android` AAR
 * version when a consumer applies the plugin via Maven coordinates (rather than an includeBuild of
 * this repo).
 */
internal object PluginVersion {
  val value: String by lazy {
    val props = Properties()
    val stream =
      PluginVersion::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/plugin/plugin-version.properties")
        ?: error("plugin-version.properties missing from plugin jar")
    stream.use { props.load(it) }
    props.getProperty("version") ?: error("version property missing from plugin-version.properties")
  }
}
