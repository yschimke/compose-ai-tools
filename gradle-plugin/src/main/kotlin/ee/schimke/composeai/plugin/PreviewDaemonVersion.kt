package ee.schimke.composeai.plugin

import java.util.Properties
import org.gradle.api.Project

/**
 * The compose-preview-daemon release this plugin resolves the renderers and daemon hosts at —
 * `ee.schimke.composeai:renderer-android`, `renderer-desktop`, `daemon-android`, `daemon-desktop`
 * and `data-layoutinspector-connector` — when a consumer applies the plugin from Maven rather than
 * from a checkout that carries those modules. It names the `compose-preview-daemon-bom` release
 * [PreviewDaemonModules] imports, not the version of any one of those modules.
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

/**
 * How the plugin puts a compose-preview-daemon module on a configuration it creates for a consumer:
 * versionless, with `compose-preview-daemon-bom` at [PreviewDaemonVersion] imported as a platform
 * on the same configuration.
 *
 * The daemon repository publishes only the modules a release changes, so its coordinates do not all
 * sit at one version: at 3.13.0 `renderer-android` published but `data-a11y-core` stayed at 3.12.0.
 * A coordinate pinned to the daemon *release* version therefore resolves only until the first
 * release that skips that module, and then 404s for every consumer of this plugin — a break
 * arriving from a repository this one did not change. The BOM is the published record of which
 * version of each module belongs to a release, so it is the only coordinate that names one.
 *
 * The BOM's constraints are ordinary (non-strict) ones, so a consumer that declares a newer daemon
 * module on the same configuration still wins conflict resolution, exactly as it did against the
 * old direct pin; and [ComposePreviewTasks.ensureRendererDesktopConfig]'s "populate the
 * configuration yourself" override skips both the module and the platform.
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
   * Imports the daemon BOM onto [configurationName] (once) and returns the versionless coordinate
   * of [artifactId] for the caller to add there. The two are tied together so no call site can add
   * the module without the platform that versions it.
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
