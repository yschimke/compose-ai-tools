package ee.schimke.composeai.plugin

import java.util.Properties

/**
 * Version of the configuration-only plugin, baked into the jar by
 * `generateConfigPluginVersionResource` in [gradle-plugin/gradle-plugin-config/build.gradle.kts].
 *
 * Recorded into the `composePreviewApplied` marker so tooling can see which artifact produced it.
 * Deliberately read from a config-module-specific resource name
 * (`config-plugin-version.properties`) rather than the runtime plugin's `plugin-version.properties`
 * — both jars can sit on the same buildscript classpath when the config plugin is applied and the
 * CLI auto-injects the runtime, so the two version resources must not collide on the same path.
 */
internal object ConfigPluginVersion {
  val value: String by lazy {
    val props = Properties()
    val stream =
      ConfigPluginVersion::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/plugin/config-plugin-version.properties")
        ?: error("config-plugin-version.properties missing from config plugin jar")
    stream.use { props.load(it) }
    props.getProperty("version")
      ?: error("version property missing from config-plugin-version.properties")
  }
}
