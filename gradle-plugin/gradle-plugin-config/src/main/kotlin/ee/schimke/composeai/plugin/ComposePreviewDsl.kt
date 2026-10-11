package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewAppliedTask
import org.gradle.api.Project

/**
 * Shared registration for the `composePreview { }` DSL, used by both the config-only
 * [ComposePreviewConfigPlugin] and the runtime `ComposePreviewPlugin`. The two can coexist (config
 * committed in the build, runtime injected by the CLI at its own version), so everything here is
 * create-or-find / register-if-absent.
 */
object ComposePreviewDsl {
  const val EXTENSION_NAME = "composePreview"
  const val APPLIED_TASK_NAME = "composePreviewApplied"

  /**
   * The build's single `composePreview` [PreviewExtension], created with its conventions on first
   * call and reused afterwards. Gradle-property overrides (`-PcomposePreview.variant=…`) are read
   * lazily, and an explicit DSL value still wins over the convention.
   */
  fun createOrFindExtension(project: Project): PreviewExtension {
    project.extensions.findByType(PreviewExtension::class.java)?.let {
      return it
    }
    val extension = project.extensions.create(EXTENSION_NAME, PreviewExtension::class.java)

    extension.variant.convention(
      project.providers.gradleProperty("composePreview.variant").orElse("debug")
    )
    extension.kmpAndroidRobolectric.convention(
      project.providers
        .gradleProperty("composePreview.kmpAndroidRobolectric")
        .map { it.toBooleanStrictOrNull() ?: false }
        .orElse(false)
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
    // `addAll`, not `convention`: the property and the DSL are additive, whereas a convention would
    // be replaced by the first DSL `add`.
    extension.renderGraph.excludes.addAll(
      project.providers
        .gradleProperty("composePreview.renderGraphExcludes")
        .map { RenderGraphExclusion.parse(it) }
        .orElse(emptyList())
    )
    return extension
  }

  /**
   * Registers `composePreviewApplied` unless present; its `applied.json` is how VS Code discovers
   * applying modules, whichever plugin applied.
   */
  fun registerAppliedTaskIfAbsent(project: Project, version: String) {
    if (project.tasks.findByName(APPLIED_TASK_NAME) != null) return
    // Idempotent — returns the extension already created above (config plugin) or by the runtime
    // plugin, so the marker records the same `composePreview { }` values the render path reads.
    val extension = createOrFindExtension(project)
    project.tasks.register(APPLIED_TASK_NAME, ComposePreviewAppliedTask::class.java) {
      pluginVersion.set(version)
      modulePath.set(project.path)
      moduleName.set(project.name)
      // Lazy: reflects the AGP-resolved variant on the runtime plugin, or the configured /
      // `"debug"` value on the config-only one.
      variant.set(extension.variant)
      previewsEnabled.set(extension.enabled)
      outputFile.set(project.layout.buildDirectory.file("compose-previews/applied.json"))
      group = "compose preview"
      description =
        "Write a marker JSON advertising that this module applies the Compose Preview plugin."
    }
  }
}
