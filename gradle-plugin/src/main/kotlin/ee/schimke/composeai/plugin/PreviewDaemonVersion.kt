package ee.schimke.composeai.plugin

import java.util.Properties

/**
 * The compose-preview-daemon release this plugin resolves the renderers and daemon hosts at —
 * `ee.schimke.composeai:renderer-android`, `renderer-desktop`, `daemon-android`, `daemon-desktop`
 * and `data-layoutinspector-connector` — when a consumer applies the plugin from Maven rather than
 * from a checkout that carries those modules.
 *
 * Deliberately NOT [PluginVersion]. Those modules publish from yschimke/compose-preview-daemon on a
 * version line of their own since its 3.0.0 (#5336), so the plugin's own version stopped naming a
 * release they exist at. Baked into the jar by `generatePluginVersionResource` in
 * [gradle-plugin/build.gradle.kts] from the `composeai-preview-daemon` catalog pin — the one value
 * the CLI's sidecar provisioner and every module in this build resolve at too.
 */
internal object PreviewDaemonVersion {
  val value: String by lazy {
    val props = Properties()
    val stream =
      PreviewDaemonVersion::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/plugin/plugin-version.properties")
        ?: error("plugin-version.properties missing from plugin jar")
    stream.use { props.load(it) }
    props.getProperty("previewDaemon")
      ?: error("previewDaemon property missing from plugin-version.properties")
  }
}
