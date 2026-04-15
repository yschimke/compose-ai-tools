package ee.schimke.composeai.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposePreviewPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

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
