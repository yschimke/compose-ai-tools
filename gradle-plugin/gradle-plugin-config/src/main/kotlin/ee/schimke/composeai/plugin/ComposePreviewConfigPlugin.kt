package ee.schimke.composeai.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configuration-only plugin, id `ee.schimke.composeai.preview.config`: lets a build commit
 * `composePreview { … }` **without pinning the rendering runtime**. It registers the
 * [PreviewExtension] DSL and the `composePreviewApplied` marker, and deliberately nothing else: no
 * Gradle-version floor, no tasks, AGP wiring or renderer artifacts. The `compose-preview` CLI
 * injects the runtime plugin at its own version, which reuses this extension and marker (see
 * [ComposePreviewDsl]).
 *
 * Meant to stay binary-stable, so consumers can pin it once and rarely bump it.
 */
abstract class ComposePreviewConfigPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    ComposePreviewDsl.createOrFindExtension(project)
    ComposePreviewDsl.registerAppliedTaskIfAbsent(project, ConfigPluginVersion.value)
  }
}
