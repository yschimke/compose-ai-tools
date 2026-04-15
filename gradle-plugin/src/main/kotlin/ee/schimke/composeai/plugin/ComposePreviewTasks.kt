package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * AGP-free task wiring shared between the Android and desktop code paths.
 * Keeping this out of [ComposePreviewPlugin] (or inside [AndroidPreviewSupport],
 * which does transitively reference AGP) means the desktop path can reuse these
 * helpers without dragging AGP onto the classpath.
 */
internal object ComposePreviewTasks {
    fun registerDesktopTasks(project: Project, extension: PreviewExtension) {
        val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

        val sourceClassDirs = project.files(
            project.layout.buildDirectory.dir("classes/kotlin/main"),
            project.layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            project.layout.buildDirectory.dir("classes/kotlin/desktop/main"),
        )

        val dependencyConfigName = listOf("jvmRuntimeClasspath", "desktopRuntimeClasspath", "runtimeClasspath")
            .firstOrNull { project.configurations.findByName(it) != null }
            ?: "runtimeClasspath"

        val discoverTask = registerDiscoverTask(project, sourceClassDirs, dependencyConfigName, previewOutputDir, extension) {
            onlyIf { extension.enabled.get() }
            for (name in listOf("compileKotlinJvm", "compileKotlinDesktop", "compileKotlin")) {
                if (project.tasks.findByName(name) != null) {
                    dependsOn(name)
                    break
                }
            }
        }

        val rendererConfigName = "composePreviewRenderer"
        val rendererConfig = project.configurations.maybeCreate(rendererConfigName)
        rendererConfig.isCanBeResolved = true
        rendererConfig.isCanBeConsumed = false

        val hasDesktopRenderer = try {
            project.dependencies.add(rendererConfigName, project.dependencies.project(mapOf("path" to ":renderer-desktop")))
            true
        } catch (_: Exception) {
            false
        }

        if (hasDesktopRenderer) {
            val renderTask = project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
                onlyIf { extension.enabled.get() }
                previewsJson.set(previewOutputDir.map { it.file("previews.json") })
                outputDir.set(previewOutputDir.map { it.dir("renders") })
                renderBackend.set("desktop")
                useComposeRenderer.set(true)
                renderClasspath.from(sourceClassDirs)
                project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
                renderClasspath.from(rendererConfig)
                group = "compose preview"
                description = "Render all previews to PNG"
                dependsOn(discoverTask)
            }
            registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)
        } else {
            registerStubRenderTask(project, previewOutputDir, sourceClassDirs, dependencyConfigName, discoverTask, extension)
        }
    }

    fun registerDiscoverTask(
        project: Project,
        sourceClassDirs: FileCollection,
        dependencyConfigName: String,
        previewOutputDir: Provider<Directory>,
        extension: PreviewExtension,
        configureDeps: DiscoverPreviewsTask.() -> Unit,
    ): TaskProvider<DiscoverPreviewsTask> {
        val artifactType = Attribute.of("artifactType", String::class.java)

        return project.tasks.register("discoverPreviews", DiscoverPreviewsTask::class.java) {
            classDirs.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { config ->
                // For Android projects, dependencies resolve as AARs. Use artifact view
                // filtering to request the extracted classes.jar (AGP registers the
                // transform). Desktop/JVM projects already return JARs so this is a no-op.
                dependencyJars.from(config.incoming.artifactView {
                    attributes.attribute(artifactType, "jar")
                }.files)
                dependencyJars.from(config.incoming.artifactView {
                    attributes.attribute(artifactType, "android-classes")
                }.files)
            }
            moduleName.set(project.name)
            variantName.set(extension.variant)
            outputFile.set(previewOutputDir.map { it.file("previews.json") })
            group = "compose preview"
            description = "Discover @Preview annotations in compiled classes"
            configureDeps()
        }
    }

    fun registerStubRenderTask(
        project: Project,
        previewOutputDir: Provider<Directory>,
        sourceClassDirs: FileCollection,
        dependencyConfigName: String,
        discoverTask: TaskProvider<DiscoverPreviewsTask>,
        extension: PreviewExtension,
    ) {
        val renderTask = project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
            onlyIf { extension.enabled.get() }
            previewsJson.set(previewOutputDir.map { it.file("previews.json") })
            outputDir.set(previewOutputDir.map { it.dir("renders") })
            renderBackend.set("stub")
            useComposeRenderer.set(false)
            renderClasspath.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
            group = "compose preview"
            description = "Render all previews to PNG (stub)"
            dependsOn(discoverTask)
        }
        registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)
    }

    /**
     * Registers `renderAllPreviews` as the user-facing entry point. When
     * `composePreview.historyEnabled = true`, inserts a `historizePreviews`
     * task between the render task and the aggregate so every run archives
     * changed PNGs into the configured history directory.
     *
     * `historizePreviews` is always registered (cheap — lazy TaskProvider) and
     * `renderAllPreviews` picks which task to depend on via a Provider that
     * Gradle resolves at task-graph-building time. That way we don't need to
     * call `extension.historyEnabled.get()` at plugin-apply time, which would
     * be premature — the user's `composePreview { ... }` block hasn't evaluated
     * yet when `withPlugin` fires.
     */
    fun registerRenderAllPreviews(
        project: Project,
        extension: PreviewExtension,
        renderTask: TaskProvider<*>,
        previewOutputDir: Provider<Directory>,
    ) {
        // Default history dir: <project>/.compose-preview-history — outside `build/`
        // so snapshots survive `./gradlew clean`. Users can override via extension.
        val defaultHistoryDir = project.layout.projectDirectory.dir(".compose-preview-history")
        val resolvedHistoryDir = extension.historyDir.orElse(defaultHistoryDir)

        val historizeTask = project.tasks.register("historizePreviews", HistorizePreviewsTask::class.java) {
            previewsJson.set(previewOutputDir.map { it.file("previews.json") })
            rendersDir.set(previewOutputDir.map { it.dir("renders") })
            historyDir.set(resolvedHistoryDir)
            group = "compose preview"
            description = "Archive changed previews into the local history directory"
            dependsOn(renderTask)
        }

        project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
            group = "compose preview"
            dependsOn(extension.historyEnabled.map { enabled -> if (enabled) historizeTask else renderTask })
        }
    }
}
