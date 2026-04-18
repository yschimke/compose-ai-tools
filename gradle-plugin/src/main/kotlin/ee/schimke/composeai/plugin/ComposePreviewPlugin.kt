package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewModelBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import javax.inject.Inject

abstract class ComposePreviewPlugin @Inject constructor(
    // Gradle injects build-scoped services into plugin constructors. This is
    // the documented way to get at `ToolingModelBuilderRegistry`; accessing
    // `project.services` directly is internal API and not stable.
    private val toolingRegistry: ToolingModelBuilderRegistry,
) : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

        // ToolingModelBuilderRegistry is a build-scoped service — registering
        // from any applying project makes the model available on every
        // Tooling-API connection for the build. `register` accepts multiple
        // builders for the same model (Gradle iterates on `canBuild`), so
        // registering once per applying subproject is safe even if
        // `buildAll` only ever gets called on the first one that matches.
        // Cross-project state (`rootProject.extras`) would trip Isolated
        // Projects, so we just let every applying project register.
        //
        // Consumed by the CLI / VS Code extension via
        // `connection.model(ComposePreviewModel::class.java)`.
        toolingRegistry.register(ComposePreviewModelBuilder())

        // `pluginManager.withPlugin` replaces the old `project.afterEvaluate { ... }`
        // block. `afterEvaluate` is discouraged under Gradle's Isolated Projects mode
        // (and in general for plugin wiring); the plugin-manager hook fires as soon
        // as the target plugin is applied, which is the right moment to wire up.
        //
        // The AGP-facing code (finalizeDsl / onVariants / cross-project dependency
        // declaration) is isolated in [AndroidPreviewSupport]. Gradle decorates this
        // plugin class at apply time and resolves all class references it sees in
        // the plugin's bytecode — so keeping AGP types *out* of ComposePreviewPlugin
        // is what lets the plugin load cleanly on non-Android projects (Compose
        // Multiplatform consumers, functional tests, etc.). AGP classes only get
        // loaded when `AndroidPreviewSupport.configure` actually runs.
        var androidConfigured = false
        val androidHandler: () -> Unit = {
            if (!androidConfigured) {
                androidConfigured = true
                AndroidPreviewSupport.configure(project, extension)
            }
        }
        project.pluginManager.withPlugin("com.android.application") { androidHandler() }
        project.pluginManager.withPlugin("com.android.library") { androidHandler() }

        project.pluginManager.withPlugin("org.jetbrains.compose") {
            if (androidConfigured) return@withPlugin
            if (project.plugins.hasPlugin("com.android.application")
                || project.plugins.hasPlugin("com.android.library")
            ) {
                return@withPlugin
            }
            ComposePreviewTasks.registerDesktopTasks(project, extension)
        }
    }
}
