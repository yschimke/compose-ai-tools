package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewAppliedTask
import org.gradle.api.Project

/**
 * Shared registration helpers for the `composePreview { }` DSL, used by **both** the
 * configuration-only [ComposePreviewConfigPlugin] (this module) and the full runtime
 * `ComposePreviewPlugin` (`:gradle-plugin`).
 *
 * The two plugins can legitimately coexist in one build: a consumer applies the config-only plugin
 * to commit `composePreview { … }` intent without pinning the rendering runtime, and the CLI then
 * auto-injects the runtime plugin at *its own* version to actually drive renders. Because the
 * `composePreview` extension type and the `composePreviewApplied` marker task live in this shared
 * artifact, whichever plugin applies first creates them and the other reuses them — there is no
 * double-registration crash and no second copy of the extension type on the classpath.
 *
 * All registration here is **create-or-find / register-if-absent** for exactly that reason.
 */
object ComposePreviewDsl {
  const val EXTENSION_NAME = "composePreview"
  const val APPLIED_TASK_NAME = "composePreviewApplied"

  /**
   * Returns the build's single `composePreview` [PreviewExtension], creating and wiring its
   * conventions on first call. Idempotent: a second caller (the other plugin) gets the existing
   * instance so user-written `composePreview { … }` config flows into one set of `Property`
   * objects.
   *
   * The convention chain mirrors what the runtime plugin used to set inline — Gradle-property
   * overrides (`-PcomposePreview.variant=…`, etc.) are read at `.get()` time, so an explicit
   * `composePreview { variant = "x" }` in the build script still wins (`Property.set` beats
   * convention).
   */
  fun createOrFindExtension(project: Project): PreviewExtension {
    project.extensions.findByType(PreviewExtension::class.java)?.let {
      return it
    }
    val extension = project.extensions.create(EXTENSION_NAME, PreviewExtension::class.java)

    extension.variant.convention(
      project.providers.gradleProperty("composePreview.variant").orElse("debug")
    )
    extension.enforcePreviewToolingDependency.convention(
      project.providers
        .gradleProperty("composePreview.enforcePreviewToolingDependency")
        .map { it.toBooleanStrictOrNull() ?: true }
        .orElse(true)
    )
    extension.failOnMissingPreviewTooling.convention(
      project.providers
        .gradleProperty("composePreview.failOnMissingPreviewTooling")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(false)
    )
    return extension
  }

  /**
   * Registers the `composePreviewApplied` marker task unless it already exists. The marker
   * (`<module>/build/compose-previews/applied.json`) is how the VS Code extension discovers applied
   * modules authoritatively — it must be present whether the module applies the config-only plugin
   * or the full runtime plugin.
   */
  fun registerAppliedTaskIfAbsent(project: Project, version: String) {
    if (project.tasks.findByName(APPLIED_TASK_NAME) != null) return
    project.tasks.register(APPLIED_TASK_NAME, ComposePreviewAppliedTask::class.java) {
      pluginVersion.set(version)
      modulePath.set(project.path)
      moduleName.set(project.name)
      outputFile.set(project.layout.buildDirectory.file("compose-previews/applied.json"))
      group = "compose preview"
      description =
        "Write a marker JSON advertising that this module applies the Compose Preview plugin."
    }
  }
}
