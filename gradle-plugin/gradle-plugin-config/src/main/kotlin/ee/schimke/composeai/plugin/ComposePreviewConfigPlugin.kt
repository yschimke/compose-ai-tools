package ee.schimke.composeai.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configuration-only Compose Preview plugin — id `ee.schimke.composeai.preview.config`.
 *
 * Lets a consumer commit `composePreview { … }` configuration to their build **without pinning the
 * rendering runtime**. Applying this plugin:
 * - registers the `composePreview` DSL extension (the same [PreviewExtension] type the runtime
 *   plugin uses), so build scripts can configure variant / device / shards / etc.; and
 * - registers the `composePreviewApplied` marker task, so the module is discoverable.
 *
 * What it deliberately does **not** do, and why that's the point:
 * - **No Gradle-version floor.** Unlike the runtime plugin it never throws on old Gradle. Merely
 *   carrying preview configuration in a build must not break that build when previews aren't being
 *   rendered.
 * - **No render/discovery tasks, no AGP wiring, no renderer artifacts.** Those are the runtime's
 *   job. The `compose-preview` CLI auto-injects the full runtime plugin (at the CLI's own version)
 *   when it actually drives a render, and that injected plugin reuses the extension and marker this
 *   plugin already created (see [ComposePreviewDsl]). So the runtime version is chosen by the tool
 *   doing the work, not pinned in the consumer's build.
 *
 * This artifact is intended to move slowly and stay binary-stable: it only carries the DSL surface
 * and the marker, so consumers can pin it once (e.g. centrally in `settings.gradle.kts`
 * `pluginManagement { plugins { … } }`) and rarely bump it.
 */
abstract class ComposePreviewConfigPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    ComposePreviewDsl.createOrFindExtension(project)
    ComposePreviewDsl.registerAppliedTaskIfAbsent(project, ConfigPluginVersion.value)
  }
}
