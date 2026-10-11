package ee.schimke.composeai.plugin

import java.util.Properties
import org.gradle.api.Project

/**
 * The compose-preview-daemon release (the `compose-preview-daemon-bom` version
 * [PreviewDaemonModules] imports) that renderers and daemon hosts resolve at when the plugin comes
 * from Maven. Not [PluginVersion]: those modules have their own version line (see #5336). Baked in
 * by `generatePluginVersionResource` from the `composeai-preview-daemon` catalog pin.
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

/**
 * Adds compose-preview-daemon modules versionless, with `compose-preview-daemon-bom` at
 * [PreviewDaemonVersion] as a platform on the same configuration. The daemon repo publishes only
 * changed modules, so a module pinned to the release version would 404 on the first release that
 * skips it; the BOM records each module's version.
 *
 * The BOM's constraints are non-strict, so a consumer's newer daemon module still wins;
 * [ComposePreviewTasks.ensureRendererDesktopConfig]'s self-populated override skips both module and
 * platform.
 */
internal object PreviewDaemonModules {
  const val GROUP = "ee.schimke.composeai"
  const val BOM_ARTIFACT_ID = "compose-preview-daemon-bom"

  /** `ee.schimke.composeai:compose-preview-daemon-bom:<version>`. */
  fun bomCoordinate(version: String = PreviewDaemonVersion.value): String =
    "$GROUP:$BOM_ARTIFACT_ID:$version"

  /**
   * The versionless `ee.schimke.composeai:<artifactId>` coordinate the BOM supplies a version to.
   */
  fun coordinate(artifactId: String): String = "$GROUP:$artifactId"

  /**
   * Imports the daemon BOM onto [configurationName] (once) and returns [artifactId]'s versionless
   * coordinate, so no caller adds the module without its platform.
   */
  fun dependency(
    project: Project,
    configurationName: String,
    artifactId: String,
    version: String = PreviewDaemonVersion.value,
  ): String {
    addPlatform(project, configurationName, version)
    return coordinate(artifactId)
  }

  /** Imports `compose-preview-daemon-bom` at [version] onto [configurationName], idempotently. */
  fun addPlatform(
    project: Project,
    configurationName: String,
    version: String = PreviewDaemonVersion.value,
  ) {
    val declared = project.configurations.getByName(configurationName).dependencies
    if (declared.any { it.group == GROUP && it.name == BOM_ARTIFACT_ID }) return
    project.dependencies.add(
      configurationName,
      project.dependencies.platform(bomCoordinate(version)),
    )
  }
}
