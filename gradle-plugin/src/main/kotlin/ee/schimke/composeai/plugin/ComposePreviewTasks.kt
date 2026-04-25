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
                tier.set(tierProperty(project))
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

    /**
     * Shared `Provider<String>` for the `composePreview.tier` Gradle property.
     * `"fast"` (case-insensitive) tells the renderer to skip captures whose
     * `cost` exceeds [HEAVY_COST_THRESHOLD]; anything else maps to `"full"`.
     * Lazy + cacheable through `project.providers`, so reading `.get()` at
     * task-execution time doesn't invalidate the configuration cache when
     * the Gradle property flips between runs.
     */
    internal fun tierProperty(project: Project): Provider<String> =
        project.providers
            .gradleProperty("composePreview.tier")
            .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
            .orElse("full")

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
            // `-PcomposePreview.failOnEmpty=true` wins over the extension, so
            // CI profiles and one-off triage runs can flip the gate without
            // touching build.gradle(.kts). Same pattern as
            // `accessibilityChecks.enabled` above.
            failOnEmpty.set(
                project.providers.gradleProperty("composePreview.failOnEmpty")
                    .map { it.toBooleanStrictOrNull() ?: false }
                    .orElse(extension.failOnEmpty),
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
            tier.set(tierProperty(project))
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
        // Captured at config time so the `doLast` body doesn't reach for
        // `project` at execution (config-cache safe). Resolves at execution
        // to "fast" or "full"; "fast" tells the post-condition to tolerate
        // heavy captures that legitimately weren't rendered this run.
        val tierProvider = project.providers
            .gradleProperty("composePreview.tier")
            .map { v -> if (v.equals("fast", ignoreCase = true)) "fast" else "full" }
            .orElse("full")
        project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
            group = "compose preview"
            dependsOn(extension.historyEnabled.map { enabled -> if (enabled) historizeTask else renderTask })
            // `verifyAccessibility` runs AFTER rendering so PNGs always exist
            // even when the check fails. `finalizedBy` (instead of `dependsOn`)
            // lets the build still produce artefacts for CLI/VSCode to
            // inspect when the a11y threshold trips the build.
            verifyAccessibilityTask?.let { finalizedBy(it) }
            doLast {
                val isFastTier = tierProvider.get() == "fast"
                val manifestOnDisk = manifestFile.get().asFile
                if (!manifestOnDisk.exists()) return@doLast
                val manifest = previewManifestJson
                    .decodeFromString(PreviewManifest.serializer(), manifestOnDisk.readText())
                if (manifest.previews.isEmpty()) return@doLast

                // `build/compose-previews/renders/` is a derived artefact —
                // the renderer rewrites it every run, and downstream tools
                // (VS Code, CLI, history) compare the CURRENT manifest
                // against on-disk state. Files left over from deleted or
                // renamed previews confuse that comparison, so we delete
                // anything that isn't referenced by a current manifest
                // entry.
                //
                // Parameterized (`@PreviewParameter`) previews are special:
                // the Gradle side only knows the stem (e.g.
                // `Foo_PARAM_template.png`), not which fan-out filenames
                // the provider will produce. The renderer itself cleans up
                // its own stale fan-out before writing (see
                // `deleteStaleFanoutFiles` in the renderer modules), so
                // here we keep every `<stem>_*<ext>` match rather than
                // second-guessing the provider values.
                cleanStaleRenders(previewOutputDir.get().asFile.resolve("renders"), manifest, logger)
                // Each preview can produce multiple captures (`@RoboComposePreviewOptions`
                // time fan-out, future scroll / dimension fan-outs). Verify each
                // capture's renderOutput lands on disk — report back one missing
                // entry per preview with at least one missing capture.
                val outDir = previewOutputDir.get().asFile
                // Files owned by non-parameterized siblings — exclude them
                // from the `<stem>_*` glob so a `Foo_header.png` that
                // belongs to a different preview never gets treated as
                // part of `Foo`'s fan-out.
                val siblingNames = manifest.previews
                    .filter { it.params.previewParameterProviderClassName == null }
                    .flatMap { it.captures.map { c -> c.renderOutput } }
                    .filter { it.isNotEmpty() }
                    .map { java.io.File(outDir, it).name }
                    .toSet()
                val missing = manifest.previews
                    .filter { p ->
                        p.captures.any { c ->
                            // `tier=fast` legitimately skips heavy captures —
                            // their PNG/GIF either still exists on disk from a
                            // prior full run (and stays usable as the "stale"
                            // image) or hasn't been produced yet. Either way,
                            // it isn't a wiring bug worth failing the build
                            // over, so exclude them from the must-exist check.
                            if (isFastTier && isHeavyCost(c.cost)) return@any false
                            val rel = c.renderOutput.ifEmpty { "renders/${p.id}.png" }
                            // `@PreviewParameter` previews fan out at render
                            // time: manifest carries a `<stem>.png` template,
                            // but the actual files live at
                            // `<stem>_<label>.png` (one per provider value,
                            // or `_PARAM_<idx>` when the label can't be
                            // derived). Check that at least ONE matching
                            // fan-out file exists instead of demanding the
                            // template itself.
                            if (p.params.previewParameterProviderClassName != null) {
                                val file = outDir.resolve(rel)
                                val dir = file.parentFile ?: outDir
                                val prefix = file.nameWithoutExtension + "_"
                                val ext = ".${file.extension}"
                                !(dir.listFiles()?.any { f ->
                                    f.name.startsWith(prefix) &&
                                        f.name.endsWith(ext) &&
                                        f.name !in siblingNames
                                } ?: false)
                            } else {
                                !outDir.resolve(rel).exists()
                            }
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

    /**
     * Deletes files inside [rendersDir] that aren't referenced by [manifest].
     *
     * Keeps four kinds of files:
     * 1. Exact `renderOutput` matches from non-parameterized previews.
     * 2. `<stem>_*.<ext>` fan-out files where `<stem>` belongs to a
     *    `@PreviewParameter` preview — the renderer itself cleans up its
     *    own stale fan-outs (it knows the exact filenames), so the Gradle
     *    side deliberately stays conservative and doesn't delete files it
     *    can't be sure are stale.
     * 3. `<stem>.a11y.png` siblings of registered renders. The renderer's
     *    `AccessibilityOverlay` writes these next to the clean PNG when
     *    a preview produces ATF findings; the manifest doesn't list them
     *    (the pointer lives in `accessibility.json` instead), so without
     *    this exemption they'd be deleted between writing and publishing.
     * 4. Non-PNG/GIF files that aren't in the plugin's output domain.
     *
     * Anything else (PNGs or GIFs that were produced for a now-removed
     * preview) gets removed so downstream tools compare the manifest
     * against a clean directory.
     */
    private fun cleanStaleRenders(
        rendersDir: java.io.File,
        manifest: PreviewManifest,
        logger: org.gradle.api.logging.Logger,
    ) {
        if (!rendersDir.isDirectory) return

        val expectedRelPaths = manifest.previews
            .filter { it.params.previewParameterProviderClassName == null }
            .flatMap { it.captures.mapNotNull { c -> c.renderOutput.stripRendersPrefix() } }
            .toSet()

        // `<stem>_` / `.<ext>` pairs we MUST leave alone — each one is the
        // template filename of a `@PreviewParameter` preview. Any file in
        // [rendersDir] whose leaf name starts with one of these prefixes
        // and ends with the matching extension is treated as a fan-out
        // sibling and preserved.
        val paramStems = manifest.previews
            .filter { it.params.previewParameterProviderClassName != null }
            .flatMap { it.captures }
            .mapNotNull { c ->
                val rel = c.renderOutput.stripRendersPrefix() ?: return@mapNotNull null
                val leaf = rel.substringAfterLast('/')
                val dot = leaf.lastIndexOf('.')
                if (dot <= 0) null
                else FanoutKey(
                    relDir = rel.substringBeforeLast('/', missingDelimiterValue = ""),
                    prefix = leaf.substring(0, dot) + "_",
                    ext = leaf.substring(dot),
                )
            }
            .toSet()

        rendersDir.walkBottomUp()
            .filter { it.isFile && (it.extension == "png" || it.extension == "gif") }
            .forEach { f ->
                val rel = f.relativeTo(rendersDir).invariantSeparatorsPath
                if (rel in expectedRelPaths) return@forEach
                if (paramStems.any { it.matches(rel, f.name) }) return@forEach
                if (isA11ySiblingOfExpected(rel, expectedRelPaths)) return@forEach
                if (!f.delete()) {
                    logger.warn("compose-preview: couldn't delete stale render $f")
                }
            }
    }

    // `<stem>.a11y.png` lives next to the clean `<stem>.png` registered in
    // the manifest. Match by mechanical suffix-strip rather than scanning
    // accessibility.json: the cleanup runs whether a11y is enabled or not,
    // and a stale `.a11y.png` whose clean sibling has been removed is still
    // garbage we want gone.
    internal fun isA11ySiblingOfExpected(
        rel: String,
        expectedRelPaths: Set<String>,
    ): Boolean {
        if (!rel.endsWith(".a11y.png")) return false
        val cleanSibling = rel.removeSuffix(".a11y.png") + ".png"
        return cleanSibling in expectedRelPaths
    }

    private fun String.stripRendersPrefix(): String? {
        if (isEmpty()) return null
        return substringAfter("renders/", missingDelimiterValue = this).takeIf { it.isNotEmpty() }
    }

    private data class FanoutKey(val relDir: String, val prefix: String, val ext: String) {
        fun matches(rel: String, leaf: String): Boolean {
            val dir = rel.substringBeforeLast('/', missingDelimiterValue = "")
            return dir == relDir && leaf.startsWith(prefix) && leaf.endsWith(ext)
        }
    }
}
