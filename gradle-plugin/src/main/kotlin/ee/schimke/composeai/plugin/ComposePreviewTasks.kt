package ee.schimke.composeai.plugin

import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

private val previewManifestJson = Json { ignoreUnknownKeys = true }

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
        } catch (e: org.gradle.api.UnknownProjectException) {
            project.logger.debug("compose-ai-tools: :renderer-desktop project not found, skipping", e)
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
            registerRenderAllPreviews(project, extension, renderTask, previewOutputDir, verifyAccessibilityTask = null)
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
            // Gradle property override: `-PcomposePreview.accessibilityChecks.enabled=true`
            // wins over the extension. Lets VSCode / CLI flip the feature on
            // for a run without editing build.gradle.kts. Isolated-Projects-
            // safe because `providers.gradleProperty` is.
            accessibilityChecksEnabled.set(
                project.providers.gradleProperty("composePreview.accessibilityChecks.enabled")
                    .map { it.toBooleanStrictOrNull() ?: false }
                    .orElse(extension.accessibilityChecks.enabled),
            )
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
        registerRenderAllPreviews(project, extension, renderTask, previewOutputDir, verifyAccessibilityTask = null)
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
        verifyAccessibilityTask: TaskProvider<*>?,
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

        // Post-condition check: every entry in the manifest must have a PNG
        // on disk after the render dependency ran. We ship the renderer
        // (RobolectricRenderTest on Android, RenderPreviewsTask on desktop)
        // so we KNOW the task should run for a non-empty manifest — a missing
        // PNG is a wiring bug, never expected. The most common offender on
        // Android is `renderPreviews` reporting NO-SOURCE because the AAR's
        // classes.jar wasn't expanded via `zipTree` before being added to
        // `testClassesDirs`, which silently skips rendering; without this
        // check the failure surfaces only in downstream tools (CLI / VSCode).
        val manifestFile = previewOutputDir.map { it.file("previews.json") }
        val rendersDir = previewOutputDir.map { it.dir("renders") }
        project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
            group = "compose preview"
            dependsOn(extension.historyEnabled.map { enabled -> if (enabled) historizeTask else renderTask })
            // `verifyAccessibility` runs AFTER rendering so PNGs always exist
            // even when the check fails. `finalizedBy` (instead of `dependsOn`)
            // lets the build still produce artefacts for CLI/VSCode to
            // inspect when the a11y threshold trips the build.
            verifyAccessibilityTask?.let { finalizedBy(it) }
            doLast {
                val manifestOnDisk = manifestFile.get().asFile
                if (!manifestOnDisk.exists()) return@doLast
                val manifest = previewManifestJson
                    .decodeFromString(PreviewManifest.serializer(), manifestOnDisk.readText())
                if (manifest.previews.isEmpty()) return@doLast
                // Each preview can produce multiple captures (`@RoboComposePreviewOptions`
                // time fan-out, future scroll / dimension fan-outs). Verify each
                // capture's renderOutput lands on disk — report back one missing
                // entry per preview with at least one missing capture.
                val outDir = previewOutputDir.get().asFile
                val missing = manifest.previews
                    .filter { p ->
                        p.captures.any { c ->
                            val rel = c.renderOutput.ifEmpty { "renders/${p.id}.png" }
                            !outDir.resolve(rel).exists()
                        }
                    }
                    .map { it.id }
                if (missing.isNotEmpty()) {
                    val preview = missing.take(3).joinToString(", ")
                    val andMore = if (missing.size > 3) " (+${missing.size - 3} more)" else ""
                    throw GradleException(
                        "renderAllPreviews: render produced no PNG for ${missing.size} of " +
                            "${manifest.previews.size} preview(s): $preview$andMore. This means " +
                            "`renderPreviews` was skipped or silently did nothing — on Android " +
                            "that usually means it reported NO-SOURCE because " +
                            "RobolectricRenderTest.class wasn't discoverable on its " +
                            "testClassesDirs. Run with --info to see the task outcome.",
                    )
                }
            }
        }
    }
}
