package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposePreviewPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

        project.afterEvaluate {
            if (!extension.enabled.get()) return@afterEvaluate

            val isAndroid = project.plugins.hasPlugin("com.android.application")
                || project.plugins.hasPlugin("com.android.library")
            val hasComposeDesktop = project.plugins.hasPlugin("org.jetbrains.compose")

            when {
                isAndroid -> registerTasks(project, extension, "android")
                hasComposeDesktop -> registerTasks(project, extension, "desktop")
                else -> project.logger.warn("compose-ai-tools: No Android or Compose Desktop target found")
            }
        }
    }

    private fun registerTasks(project: Project, extension: PreviewExtension, backend: String) {
        val variant = extension.variant.get()
        val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

        val sourceClassDirs = when (backend) {
            "android" -> project.files(
                project.layout.buildDirectory.dir("tmp/kotlin-classes/$variant"),
                project.layout.buildDirectory.dir("intermediates/javac/$variant/classes"),
                project.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant/compile${variant.cap()}Kotlin/classes"),
            )
            else -> project.files(
                project.layout.buildDirectory.dir("classes/kotlin/main"),
                project.layout.buildDirectory.dir("classes/kotlin/jvm/main"),
                project.layout.buildDirectory.dir("classes/kotlin/desktop/main"),
            )
        }

        val dependencyConfigName = when (backend) {
            "android" -> "${variant}RuntimeClasspath"
            else -> {
                listOf("jvmRuntimeClasspath", "desktopRuntimeClasspath", "runtimeClasspath")
                    .firstOrNull { project.configurations.findByName(it) != null }
                    ?: "runtimeClasspath"
            }
        }

        // Try to resolve the renderer module for full composable rendering
        val rendererConfigName = "composePreviewRenderer"
        val rendererConfig = project.configurations.maybeCreate(rendererConfigName)
        rendererConfig.isCanBeResolved = true
        rendererConfig.isCanBeConsumed = false

        val hasRenderer = when (backend) {
            "desktop" -> {
                try {
                    project.dependencies.add(rendererConfigName, project.dependencies.project(mapOf("path" to ":renderer-desktop")))
                    true
                } catch (_: Exception) {
                    project.logger.info("compose-ai-tools: renderer-desktop module not found, using stub renderer")
                    false
                }
            }
            // TODO: Android compose rendering not yet wired — see DESIGN.md "Open Issues"
            "android" -> false
            else -> false
        }

        val discoverTask = project.tasks.register("discoverPreviews", DiscoverPreviewsTask::class.java) {
            classDirs.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { config ->
                dependencyJars.from(config)
            }
            moduleName.set(project.name)
            variantName.set(variant)
            outputFile.set(previewOutputDir.map { it.file("previews.json") })
            group = "compose preview"
            description = "Discover @Preview annotations in compiled classes"

            when (backend) {
                "android" -> dependsOn("compile${variant.cap()}Kotlin")
                else -> {
                    for (name in listOf("compileKotlinJvm", "compileKotlinDesktop", "compileKotlin")) {
                        if (project.tasks.findByName(name) != null) {
                            dependsOn(name)
                            break
                        }
                    }
                }
            }
        }

        val renderTask = project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
            previewsJson.set(previewOutputDir.map { it.file("previews.json") })
            outputDir.set(previewOutputDir.map { it.dir("renders") })
            renderBackend.set(backend)
            useComposeRenderer.set(hasRenderer)

            // Build the classpath: module classes + dependencies + renderer module
            renderClasspath.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { config ->
                renderClasspath.from(config)
            }
            if (hasRenderer) {
                // TODO: For Android, the renderer classpath needs special handling:
                //  - AAR dependencies must be extracted to JARs
                //  - android.jar from the SDK must be on the boot classpath
                //  - The debugUnitTestRuntimeClasspath should be used instead
                //  Currently only desktop rendering uses the compose renderer.
                renderClasspath.from(rendererConfig)
            }

            group = "compose preview"
            description = "Render all previews to PNG"
            dependsOn(discoverTask)
        }

        project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
            group = "compose preview"
            dependsOn(renderTask)
        }
    }

    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
