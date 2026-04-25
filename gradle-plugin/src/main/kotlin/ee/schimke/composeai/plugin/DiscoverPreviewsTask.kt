package ee.schimke.composeai.plugin

import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class DiscoverPreviewsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyJars: ConfigurableFileCollection

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    /**
     * When `true`, [outputFile] is written with a populated
     * [PreviewManifest.accessibilityReport] pointer so downstream tools can
     * locate the sidecar report. The file itself is produced later by the
     * render task / verify task.
     */
    @get:Input
    abstract val accessibilityChecksEnabled: Property<Boolean>

    /**
     * When `true` and discovery produces zero previews, emit a diagnostics
     * block to the lifecycle log (classDirs contents, post-filter dep-JAR
     * sample, ClassGraph scan summary, observed annotation FQNs) and fail
     * the task. Wired from the `composePreview.failOnEmpty` extension /
     * `-PcomposePreview.failOnEmpty=true` Gradle property.
     */
    @get:Input
    abstract val failOnEmpty: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    companion object {
        private val PREVIEW_FQNS = setOf(
            "androidx.compose.ui.tooling.preview.Preview",
            "androidx.compose.desktop.ui.tooling.preview.Preview",
            TILE_PREVIEW_FQN,
        )
        private val CONTAINER_FQNS = setOf(
            "androidx.compose.ui.tooling.preview.Preview\$Container",
            "androidx.compose.ui.tooling.preview.Preview.Container",
            // Tiles @Preview is @Repeatable, so the compiler synthesises a
            // `Preview.Container` too. Picking it up here lets us see every
            // stacked tile preview (e.g. SMALL_ROUND + LARGE_ROUND on one fn).
            "androidx.wear.tiles.tooling.preview.Preview\$Container",
            "androidx.wear.tiles.tooling.preview.Preview.Container",
        )
        // androidx.compose.ui:ui-tooling-preview 1.11.0+ — wraps each preview in a custom
        // PreviewWrapperProvider. Matched by FQN so older apps (no such class on classpath)
        // simply never surface the annotation and discovery is a no-op.
        private const val PREVIEW_WRAPPER_FQN = "androidx.compose.ui.tooling.preview.PreviewWrapper"
        // Our own opt-in for scrolling-screenshot capture. Matched by FQN so projects
        // that don't depend on `ee.schimke.composeai:preview-annotations` are unaffected.
        private const val SCROLLING_PREVIEW_FQN = "ee.schimke.composeai.preview.ScrollingPreview"
        // Animation-window capture — sibling annotation to @ScrollingPreview, same
        // FQN-match policy. See `AnimatedPreview.kt`.
        private const val ANIMATED_PREVIEW_FQN = "ee.schimke.composeai.preview.AnimatedPreview"
        // The stable FQN is shared by both Android's ui-tooling-preview and CMP's
        // `org.jetbrains.compose.components:components-ui-tooling-preview` — Kotlin
        // `expect`/`actual` collapses onto the same `androidx...` class name on
        // every target we care about.
        private const val PREVIEW_PARAMETER_FQN = "androidx.compose.ui.tooling.preview.PreviewParameter"

        internal const val TILE_PREVIEW_FQN = "androidx.wear.tiles.tooling.preview.Preview"

        // failOnEmpty diagnostics: cap the JAR + annotation FQN sample sizes
        // so the lifecycle log stays readable on projects with huge classpaths.
        private const val DIAG_JAR_SAMPLE = 15
        private const val DIAG_ANNOTATION_SAMPLE = 20

        // Roborazzi's per-preview clock control. Opt-in: presence of the
        // annotation on a @Preview method fans out one extra manifest entry
        // per `ManualClockOptions.advanceTimeMillis` value, with filename
        // suffix `_TIME_<ms>ms`. Absent → single entry with null timing
        // (renderer falls back to its default CAPTURE_ADVANCE_MS).
        //
        // Shipped by `io.github.takahirom.roborazzi:roborazzi-annotations`.
        // We never load the class — ClassGraph reads the annotation and its
        // nested `ManualClockOptions` entries by descriptor, so the plugin
        // itself doesn't need a compile-time dep.
        private const val ROBO_COMPOSE_PREVIEW_OPTIONS_FQN =
            "com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions"
    }

    @TaskAction
    fun discover() {
        val existingClassDirs = classDirs.files.filter { it.exists() && it.isDirectory }
        // Match on the absolute path, not just the file name: AGP 9.x +
        // KGP 2.3 resolve AAR dependencies to `<cache>/transforms/<hash>/
        // transformed/<library>/jars/classes.jar` where the library name
        // lives in the parent directory, not the filename. Filtering on
        // `file.name` alone dropped every AAR-extracted jar — see #162.
        val filteredDependencyJars = dependencyJars.files.filter { file ->
            file.exists() && file.name.lowercase().endsWith(".jar") && run {
                val path = file.absolutePath.lowercase()
                path.contains("preview") || path.contains("tooling") ||
                    path.contains("compose") || path.contains("annotation")
            }
        }
        val classpath = existingClassDirs + filteredDependencyJars

        val previews = mutableListOf<PreviewInfo>()
        // Populated only on the diagnostics path (failOnEmpty + 0 previews)
        // so we can tell users whether ClassGraph saw any classes at all,
        // and which annotation FQNs it did see — which disambiguates
        // "classpath is wrong" from "@Preview FQN doesn't match" in a
        // single run.
        var scanClassCount = 0
        var scanMethodsWithAnnotations = 0
        val annotationFqnCounts = LinkedHashMap<String, Int>()
        // Which known @Preview annotation FQNs are reachable as ClassInfo
        // on the scan classpath. Empty → discovery cannot resolve multi-
        // preview annotations (they fan out via `scanResult.getClassInfo`),
        // which is almost always a misconfigured dep-jar classpath.
        var reachablePreviewFqns: List<String> = emptyList()

        if (classpath.isNotEmpty()) {
            ClassGraph()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .overrideClasspath(classpath.map { it.absolutePath })
                .ignoreParentClassLoaders()
                .scan().use { scanResult ->
                    reachablePreviewFqns = PREVIEW_FQNS.filter { scanResult.getClassInfo(it) != null }
                    for (classInfo in scanResult.allClasses) {
                        scanClassCount++
                        for (method in classInfo.methodInfo) {
                            val annotations = method.annotationInfo ?: continue
                            if (annotations.isNotEmpty()) scanMethodsWithAnnotations++
                            for (ann in annotations) {
                                annotationFqnCounts.merge(ann.name, 1, Int::plus)
                            }
                            discoverFromMethod(classInfo, method, annotations.toList(), scanResult, previews)
                        }
                    }
                }
        }

        // id already encodes the name + (device, fontScale, uiMode) variant suffix, so
        // dedup by id alone. Two identical preview variants on the same function collapse.
        val deduped = previews.distinctBy { it.id }

        // Rewrite each capture's renderOutput to a normalized, shell-safe
        // filename: drop the package prefix shared by every preview in the
        // module so `renders/ee.schimke.ha.previews.CardPreviewsKt.Foo.png`
        // lands at `renders/CardPreviewsKt.Foo.png`; sanitize spaces, parens,
        // and other awkward shell characters inherited from `@Preview(name =
        // "tile light (light)")`. Keeps `PreviewInfo.id` untouched — consumers
        // that key by id (history folders, CLI state, test names) are
        // unaffected.
        val normalized = normalizeRenderOutputs(deduped)

        val manifest = PreviewManifest(
            module = moduleName.get(),
            variant = variantName.get(),
            previews = normalized,
            accessibilityReport = "accessibility.json".takeIf { accessibilityChecksEnabled.get() },
        )

        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(json.encodeToString(manifest))

        logger.lifecycle("Discovered ${normalized.size} preview(s) in module '${moduleName.get()}':")
        for (preview in normalized) {
            logger.lifecycle("  ${preview.className}.${preview.functionName}${describeVariant(preview)}")
        }

        // Unconditional fail: the plugin scanned non-empty class dirs but
        // none of the known @Preview annotation classes are on the scan
        // classpath. Any multi-preview annotation (@LightDarkPreviews,
        // @WearPreviewDevices, user wrappers) needs its class reachable via
        // `scanResult.getClassInfo` to fan out, so this state silently
        // hides the consumer's previews — see #162 for a real-world
        // report. Preceded by the failOnEmpty diagnostics block so the
        // error message points at the data that disambiguates the cause.
        val previewAnnotationsMissing =
            scanClassCount > 0 && reachablePreviewFqns.isEmpty()
        if (normalized.isEmpty() && (failOnEmpty.get() || previewAnnotationsMissing)) {
            logEmptyDiagnostics(
                existingClassDirs = existingClassDirs,
                allClassDirs = classDirs.files.toList(),
                filteredJars = filteredDependencyJars,
                allJarCount = dependencyJars.files.size,
                scanClassCount = scanClassCount,
                scanMethodsWithAnnotations = scanMethodsWithAnnotations,
                annotationFqnCounts = annotationFqnCounts,
                reachablePreviewFqns = reachablePreviewFqns,
            )
            val reason = if (previewAnnotationsMissing) {
                "the @Preview annotation class is not on the ClassGraph classpath " +
                    "(dependency-jar filter dropped every jar carrying it)"
            } else {
                "with failOnEmpty=true"
            }
            throw org.gradle.api.GradleException(
                "composePreview: discovered 0 previews in module '${moduleName.get()}' — " +
                    "$reason. See diagnostics above.",
            )
        }
    }

    private fun logEmptyDiagnostics(
        existingClassDirs: List<java.io.File>,
        allClassDirs: List<java.io.File>,
        filteredJars: List<java.io.File>,
        allJarCount: Int,
        scanClassCount: Int,
        scanMethodsWithAnnotations: Int,
        annotationFqnCounts: Map<String, Int>,
        reachablePreviewFqns: List<String>,
    ) {
        logger.lifecycle("composePreview: failOnEmpty diagnostics (0 previews discovered):")
        logger.lifecycle("  classDirs (${allClassDirs.size} declared, ${existingClassDirs.size} existing):")
        for (dir in allClassDirs) {
            val exists = dir.exists()
            val isDir = dir.isDirectory
            val classCount = if (exists && isDir) {
                dir.walkTopDown().count { it.extension == "class" }
            } else 0
            logger.lifecycle("    - $dir")
            logger.lifecycle("      exists=$exists isDir=$isDir classFiles=$classCount")
        }
        logger.lifecycle("  dependencyJars: $allJarCount total, ${filteredJars.size} match " +
            "(preview|tooling|compose|annotation)")
        for (jar in filteredJars.take(DIAG_JAR_SAMPLE)) {
            logger.lifecycle("    - ${jar.name}")
        }
        if (filteredJars.size > DIAG_JAR_SAMPLE) {
            logger.lifecycle("    … and ${filteredJars.size - DIAG_JAR_SAMPLE} more")
        }
        logger.lifecycle("  ClassGraph scan: $scanClassCount classes, " +
            "$scanMethodsWithAnnotations methods with any annotation")
        if (scanClassCount > 0) {
            if (reachablePreviewFqns.isEmpty()) {
                // Most common #162-shaped failure: the consumer's preview
                // annotations live in AAR-extracted `<library>/jars/classes.jar`
                // files, whose `file.name` is just `classes.jar`. The
                // dep-jar filter used to match on file name only and
                // dropped every such jar, so no multi-preview annotation
                // could be resolved.
                logger.lifecycle("  known @Preview annotation classes NOT reachable on " +
                    "ClassGraph classpath — multi-preview resolution is disabled.")
                logger.lifecycle("    expected at least one of (by FQN):")
                for (fqn in PREVIEW_FQNS) logger.lifecycle("      - $fqn")
            } else {
                logger.lifecycle("  reachable @Preview annotation classes on ClassGraph classpath:")
                for (fqn in reachablePreviewFqns) logger.lifecycle("      - $fqn")
            }
        }
        val previewAnnotationsSeen = PREVIEW_FQNS.filter { annotationFqnCounts.containsKey(it) }
        if (previewAnnotationsSeen.isNotEmpty()) {
            // If this path triggers we have a real bug: @Preview is on the
            // classpath, it's on some method, but discovery still emitted
            // nothing. Make it impossible to miss in the log.
            logger.lifecycle("  known @Preview FQNs WERE seen on scanned methods " +
                "(discovery dropped them — please report):")
            for (fqn in previewAnnotationsSeen) {
                logger.lifecycle("    - $fqn (${annotationFqnCounts[fqn]})")
            }
        } else if (scanClassCount > 0) {
            logger.lifecycle("  no known @Preview FQN seen on any scanned method.")
            logger.lifecycle("    expected one of:")
            for (fqn in PREVIEW_FQNS) logger.lifecycle("      - $fqn")
            val topAnnotations = annotationFqnCounts.entries
                .sortedByDescending { it.value }
                .take(DIAG_ANNOTATION_SAMPLE)
            if (topAnnotations.isNotEmpty()) {
                logger.lifecycle("    top annotation FQNs actually observed:")
                for ((fqn, count) in topAnnotations) {
                    logger.lifecycle("      - $fqn ($count)")
                }
            }
        } else {
            logger.lifecycle("  ClassGraph scanned 0 classes — check the classDirs listing above.")
        }
    }

    /**
     * Computes the common dotted prefix shared by every preview id (up to and
     * including the last matched `.`) and strips it from each capture's
     * `renderOutput` filename. Also runs every stem through [sanitizeFileStem]
     * so spaces and shell-unfriendly characters inherited from `@Preview(name
     * = "tile light (light)")` don't end up in the PNG filename.
     *
     * `preview.id` itself is deliberately left untouched — it's the stable
     * identity consumers key by (history folders, CLI state, JUnit test
     * names). The renderOutput is purely the on-disk filename, and that's
     * what benefits from a shorter, quoted-free form.
     *
     * No-op on a single-preview module (empty prefix) or when sanitization
     * would collapse two distinct ids to the same stem — in that case we
     * keep the un-stripped, un-sanitized id for everyone so the pretty
     * rename never introduces a filename collision.
     */
    private fun normalizeRenderOutputs(previews: List<PreviewInfo>): List<PreviewInfo> {
        if (previews.isEmpty()) return previews
        val commonPrefix = commonDottedPrefix(previews.map { it.id })
        val stems = previews.map { sanitizeFileStem(it.id.removePrefix(commonPrefix)) }
        val safe = stems.toSet().size == previews.size &&
            stems.none { it.isEmpty() }
        return previews.mapIndexed { i, preview ->
            val newStem = if (safe) stems[i] else preview.id
            val rewritten = preview.captures.map { c ->
                c.copy(renderOutput = rewriteRenderStem(c.renderOutput, preview.id, newStem))
            }
            preview.copy(captures = rewritten)
        }
    }

    /** `renders/<oldStem><tail>.<ext>` → `renders/<newStem><tail>.<ext>`. */
    private fun rewriteRenderStem(renderOutput: String, oldStem: String, newStem: String): String {
        if (renderOutput.isEmpty() || oldStem == newStem) return renderOutput
        val dir = renderOutput.substringBeforeLast('/', missingDelimiterValue = "")
        val leaf = renderOutput.substringAfterLast('/')
        if (!leaf.startsWith(oldStem)) return renderOutput
        val rewritten = newStem + leaf.removePrefix(oldStem)
        return if (dir.isEmpty()) rewritten else "$dir/$rewritten"
    }

    private fun commonDottedPrefix(ids: List<String>): String {
        if (ids.size < 2) return ""
        var prefix = ids.first()
        for (id in ids.drop(1)) {
            var i = 0
            val limit = minOf(prefix.length, id.length)
            while (i < limit && prefix[i] == id[i]) i++
            prefix = prefix.substring(0, i)
            if (prefix.isEmpty()) return ""
        }
        val lastDot = prefix.lastIndexOf('.')
        return if (lastDot < 0) "" else prefix.substring(0, lastDot + 1)
    }

    /**
     * Sanitizes a filename stem to an ASCII-safe whitelist
     * (`[A-Za-z0-9._-]`). Every other character collapses to `_`, runs of
     * `_` collapse to a single `_`, and leading/trailing `_`, `.`, `-` are
     * trimmed. A whitelist is deliberate: we can't enumerate every awkward
     * character a preview name might contain (Unicode dashes, NBSP, RTL
     * marks, emoji), and any one of them can break a downstream tool that
     * expects a POSIX-plain filename. `.` is preserved so FQN-shaped stems
     * (`CardPreviewsKt.Tile_Light_States`) survive intact when the package
     * prefix strip can't flatten them further.
     */
    private fun sanitizeFileStem(s: String): String =
        s.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_', '.', '-')

    // Renders the distinguishing bits of a preview variant for the discovery log
    // so sibling expansions (e.g. @WearPreviewFontScales × 6) aren't visually
    // identical. Format mirrors the VSCode tooltip: `name` / `device` /
    // `WxHdp` / `font Nx` / `uiMode=N` / `locale` / `group`.
    private fun describeVariant(preview: PreviewInfo): String {
        val p = preview.params
        val parts = mutableListOf<String>()
        p.name?.let(parts::add)
        p.device?.let(parts::add)
        val w = p.widthDp
        val h = p.heightDp
        if (w != null && h != null) parts.add("${w}x${h}dp")
        if (p.fontScale != 1.0f) parts.add("font ${p.fontScale}x")
        if (p.uiMode != 0) parts.add("uiMode=${p.uiMode}")
        p.locale?.let(parts::add)
        p.group?.let { parts.add("group=$it") }
        // Summarise capture-level dimensions (time, scroll) on one line so
        // the log remains a single bullet per preview even for fan-outs.
        val timings = preview.captures.mapNotNull { it.advanceTimeMillis }
        if (timings.isNotEmpty()) {
            parts.add("${preview.captures.size} captures @ ${timings.joinToString(",") { "${it}ms" }}")
        }
        val scrollModes = preview.captures.mapNotNull { it.scroll?.mode }.distinct()
        if (scrollModes.isNotEmpty()) {
            parts.add("scroll=" + scrollModes.joinToString(",") { it.name.lowercase() })
        }
        val anim = preview.captures.firstNotNullOfOrNull { it.animation }
        if (anim != null) {
            val curveSuffix = if (anim.showCurves) "+curves" else ""
            parts.add("animated=${anim.durationMs}ms@${anim.frameIntervalMs}ms$curveSuffix")
        }
        return if (parts.isEmpty()) "" else "  [" + parts.joinToString(" · ") + "]"
    }

    private fun discoverFromMethod(
        classInfo: ClassInfo,
        method: MethodInfo,
        annotations: List<AnnotationInfo>,
        scanResult: ScanResult,
        previews: MutableList<PreviewInfo>,
    ) {
        // @PreviewWrapper and @ScrollingPreview are both non-repeatable and apply
        // to every @Preview on the function (including expansions from
        // multi-preview meta-annotations). `@ScrollingPreview.modes` fans out
        // one capture per entry — see [buildCaptures]. `@AnimatedPreview` is
        // single-shot (one GIF per function) so it doesn't fan out, but follows
        // the same "one annotation per function, applies to every preview
        // expansion" policy.
        val wrapperFqn = extractWrapperFqn(annotations)
        val scrollSpecs = extractScrollSpecs(annotations)
        val animationSpec = extractAnimationSpec(annotations)
        // @RoboComposePreviewOptions, similarly, applies to the function as a
        // whole — each timing fans out into its own manifest entry, orthogonal
        // to any multi-preview expansion.
        val timings = extractRoboTimings(annotations)
        // @PreviewParameter lives on a method PARAMETER, not the method itself,
        // so it's sourced from `parameterInfo` rather than the method
        // annotation list. Extracted once per function and applied to every
        // multi-preview expansion — the provider is the same no matter which
        // @Preview drove the fan-out.
        val previewParameter = extractPreviewParameter(method)

        val directPreviews = collectDirectPreviews(annotations)
        if (directPreviews.isNotEmpty()) {
            for (ann in directPreviews) {
                previews.add(makePreview(classInfo, method, ann, wrapperFqn, scrollSpecs, animationSpec, timings, previewParameter))
            }
            return
        }

        for (ann in annotations) {
            val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
            for (resolvedAnn in resolved) {
                previews.add(makePreview(classInfo, method, resolvedAnn, wrapperFqn, scrollSpecs, animationSpec, timings, previewParameter))
            }
        }
    }

    /**
     * Scans [method]'s parameters for `@PreviewParameter`. Returns the provider
     * FQN + `limit` of the FIRST parameter that carries the annotation; `null`
     * when none do. Supporting a single parameter mirrors the current upstream
     * (Studio/Layoutlib) semantic — multi-param preview functions require
     * explicit wiring in tooling code, which our renderer doesn't expose.
     *
     * ClassGraph surfaces parameter annotations on `MethodParameterInfo.annotationInfo`.
     * The `value` field on `@PreviewParameter` carries the provider KClass, which
     * comes back as an [AnnotationClassRef] — we pull its FQN without triggering
     * classloading (matches how [extractWrapperFqn] handles `@PreviewWrapper`).
     */
    private fun extractPreviewParameter(method: MethodInfo): Pair<String, Int>? {
        val params = method.parameterInfo ?: return null
        for (param in params) {
            val anns = param.annotationInfo ?: continue
            val ann = anns.firstOrNull { it.name == PREVIEW_PARAMETER_FQN } ?: continue
            val provider = when (val value = ann.parameterValues.getValue("provider")) {
                is AnnotationClassRef -> value.name
                is String -> value
                else -> null
            } ?: continue
            val limit = (ann.parameterValues.getValue("limit") as? Int)
                ?.coerceAtLeast(0)
                ?: Int.MAX_VALUE
            return provider to limit
        }
        return null
    }

    // Tile previews don't go through `mainClock` and can't scroll (the
    // renderer inflates a View via `TileRenderer` and has no Compose
    // animation clock / scrollable), so both dimensional annotations are
    // no-ops for tiles.
    private fun buildCaptures(
        ann: AnnotationInfo,
        previewId: String,
        scrolls: List<ScrollCapture>,
        animation: AnimationCapture?,
        timings: List<Long>,
    ): List<Capture> {
        val isTile = ann.name == TILE_PREVIEW_FQN
        val effectiveTimings = if (isTile) emptyList() else timings
        val effectiveScrolls = if (isTile) emptyList() else scrolls
        // Tile previews don't go through `mainClock` either — `TileRenderer`
        // inflates a static View and there's no animation surface to drive.
        val effectiveAnimation = if (isTile) null else animation

        // @AnimatedPreview produces its own dedicated capture, alongside any
        // scroll / time fan-out. The GIF gets a distinguishing `_anim` suffix
        // when other captures share the function (the multi-mode scroll
        // pattern), and the plain filename otherwise.
        val animationCaptures: List<Capture> = if (effectiveAnimation == null) emptyList() else {
            val sharesFn = effectiveScrolls.isNotEmpty() || effectiveTimings.isNotEmpty()
            val suffix = if (sharesFn) "_anim" else ""
            listOf(
                Capture(
                    animation = effectiveAnimation,
                    renderOutput = "renders/${previewId}${suffix}.gif",
                ),
            )
        }

        // Single-mode scroll keeps the plain filename so migrations from the
        // old single-valued `mode = …` annotation land on identical paths.
        // Multi-mode adds `_SCROLL_<mode>` to disambiguate siblings, same
        // pattern as `_TIME_<ms>ms` for the time dimension.
        val scrollRows: List<Pair<ScrollCapture?, String>> = when {
            effectiveScrolls.isEmpty() -> listOf(null to "")
            effectiveScrolls.size == 1 -> listOf(effectiveScrolls[0] to "")
            else -> effectiveScrolls.map { it to "_SCROLL_${it.mode.name.lowercase()}" }
        }
        val timeRows: List<Pair<Long?, String>> =
            if (effectiveTimings.isEmpty()) listOf(null to "")
            else effectiveTimings.map { ms -> ms to "_TIME_${ms}ms" }

        // When ONLY @AnimatedPreview is on the function, the scroll/time
        // cross-product would still emit one (null, null) row — i.e. a static
        // PNG capture. Suppress that to keep `@AnimatedPreview` a clean
        // single-output annotation.
        val emitStaticCross = effectiveScrolls.isNotEmpty() ||
            effectiveTimings.isNotEmpty() ||
            effectiveAnimation == null

        val scrollTimeCaptures: List<Capture> = if (!emitStaticCross) emptyList() else {
            scrollRows.flatMap { (scroll, scrollSuffix) ->
                timeRows.map { (ms, timeSuffix) ->
                    // GIF captures land on `.gif`; everything else is `.png`.
                    // Branching the extension per-capture (rather than per-preview)
                    // keeps multi-mode annotations like `modes = [TOP, GIF]`
                    // producing one PNG + one GIF from the same function.
                    val ext = if (scroll?.mode == ScrollMode.GIF) "gif" else "png"
                    Capture(
                        advanceTimeMillis = ms,
                        scroll = scroll,
                        renderOutput = "renders/${previewId}${scrollSuffix}${timeSuffix}.${ext}",
                    )
                }
            }
        }

        return scrollTimeCaptures + animationCaptures
    }

    // Reads `@RoboComposePreviewOptions(manualClockOptions = [...])` on the
    // preview function and returns the `advanceTimeMillis` of each entry.
    // Empty list if the annotation is absent OR present with no entries — the
    // latter is equivalent to "default" per Roborazzi's own scanner-support
    // behaviour. ClassGraph surfaces `manualClockOptions` as an
    // Object[] of `AnnotationInfo` because the field type is `Array<ManualClockOptions>`.
    private fun extractRoboTimings(annotations: List<AnnotationInfo>): List<Long> {
        val ann = annotations.firstOrNull { it.name == ROBO_COMPOSE_PREVIEW_OPTIONS_FQN } ?: return emptyList()
        val raw = ann.parameterValues.getValue("manualClockOptions") ?: return emptyList()
        val items = when (raw) {
            is Array<*> -> raw.filterIsInstance<AnnotationInfo>()
            is AnnotationInfo -> listOf(raw)
            else -> {
                // Some ClassGraph versions hand back a typed primitive array or
                // Kotlin wrapper — fall back to reflective iteration.
                val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
                (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? AnnotationInfo }
            }
        }
        return items.mapNotNull { it.parameterValues.getValue("advanceTimeMillis") as? Long }
    }

    private fun extractWrapperFqn(annotations: List<AnnotationInfo>): String? {
        val wrapperAnn = annotations.firstOrNull { it.name == PREVIEW_WRAPPER_FQN } ?: return null
        // The `wrapper: KClass<out PreviewWrapperProvider>` parameter surfaces as an
        // AnnotationClassRef — pull the FQN without triggering classloading.
        return when (val value = wrapperAnn.parameterValues.getValue("wrapper")) {
            is AnnotationClassRef -> value.name
            is String -> value
            else -> null
        }
    }

    /**
     * Reads `@AnimatedPreview(durationMs, frameIntervalMs, showCurves)` off
     * the function annotation list. Single-shot — at most one animation
     * capture per function, so we return a nullable spec rather than a list.
     * Negative / zero numeric fields fall back to the annotation defaults.
     */
    private fun extractAnimationSpec(annotations: List<AnnotationInfo>): AnimationCapture? {
        val ann = annotations.firstOrNull { it.name == ANIMATED_PREVIEW_FQN } ?: return null
        val pv = ann.parameterValues
        // `durationMs = 0` is the auto-detect sentinel; let the renderer ask
        // PreviewAnimationClock for the real duration. A positive value
        // overrides; negatives clamp to the sentinel.
        val durationMs = (pv.getValue("durationMs") as? Int)?.coerceAtLeast(0) ?: 0
        val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.takeIf { it > 0 } ?: 33
        val showCurves = (pv.getValue("showCurves") as? Boolean) ?: true
        return AnimationCapture(
            durationMs = durationMs,
            frameIntervalMs = frameIntervalMs,
            showCurves = showCurves,
        )
    }

    private fun extractScrollSpecs(annotations: List<AnnotationInfo>): List<ScrollCapture> {
        val ann = annotations.firstOrNull { it.name == SCROLLING_PREVIEW_FQN } ?: return emptyList()
        val pv = ann.parameterValues
        // ClassGraph surfaces the `modes: Array<ScrollMode>` field as an
        // Object[] of AnnotationEnumValue; same shape as `manualClockOptions`
        // above. Enum constants are compared by `.valueName` so we never
        // force-load the annotation's classes.
        val rawModes = pv.getValue("modes")
        val modes = readEnumArray(rawModes) { ScrollMode.valueOf(it) }
        if (modes.isEmpty()) return emptyList()
        val axis = (pv.getValue("axis") as? AnnotationEnumValue)?.valueName
            ?.let { runCatching { ScrollAxis.valueOf(it) }.getOrNull() }
            ?: ScrollAxis.VERTICAL
        val maxScrollPx = (pv.getValue("maxScrollPx") as? Int)?.coerceAtLeast(0) ?: 0
        val reduceMotion = (pv.getValue("reduceMotion") as? Boolean) ?: true
        // `frameIntervalMs` only meaningful for GIF mode; we still read it
        // unconditionally and carry it into every ScrollCapture so the
        // manifest shape stays uniform. `0` (or negative, coerced) signals
        // "use the renderer's default" — matching the annotation-side
        // DEFAULT_GIF_FRAME_INTERVAL_MS without duplicating the literal here.
        val frameIntervalMs = (pv.getValue("frameIntervalMs") as? Int)?.coerceAtLeast(0) ?: 0
        // Result fields (atEnd, reachedPx) default to "not reported" — the
        // renderer would fill them in post-capture; discovery knows only the
        // intent. De-dup to guard against `modes = [END, END]` producing
        // colliding paths. Sort by enum ordinal (TOP→END→LONG→GIF) so the
        // renderer captures the initial frame before driving the scroller —
        // otherwise `modes = [END, TOP]` would produce a "TOP" PNG at the
        // scrolled-end position.
        return modes.distinct().sortedBy { it.ordinal }.map { mode ->
            ScrollCapture(
                mode = mode,
                axis = axis,
                maxScrollPx = maxScrollPx,
                reduceMotion = reduceMotion,
                frameIntervalMs = frameIntervalMs,
            )
        }
    }

    // Reads an annotation's Array<EnumT> parameter and maps each entry by
    // `.valueName` through [parse]. ClassGraph can hand this back as a plain
    // array, a single AnnotationEnumValue (single-entry arrays), or a typed
    // array we need to walk reflectively — same cases as
    // [extractRoboTimings].
    private fun <T> readEnumArray(raw: Any?, parse: (String) -> T): List<T> {
        if (raw == null) return emptyList()
        val items = when (raw) {
            is Array<*> -> raw.filterIsInstance<AnnotationEnumValue>()
            is AnnotationEnumValue -> listOf(raw)
            else -> {
                val len = runCatching { java.lang.reflect.Array.getLength(raw) }.getOrNull() ?: 0
                (0 until len).mapNotNull { java.lang.reflect.Array.get(raw, it) as? AnnotationEnumValue }
            }
        }
        return items.mapNotNull { runCatching { parse(it.valueName) }.getOrNull() }
    }

    private fun isDirectPreview(ann: AnnotationInfo): Boolean =
        ann.name in PREVIEW_FQNS

    private fun isPreviewContainer(ann: AnnotationInfo): Boolean =
        ann.name in CONTAINER_FQNS

    private fun collectDirectPreviews(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val result = mutableListOf<AnnotationInfo>()
        for (ann in annotations) {
            when {
                isDirectPreview(ann) -> result.add(ann)
                isPreviewContainer(ann) -> {
                    val value = ann.parameterValues.getValue("value")
                    when (value) {
                        is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { result.add(it) }
                        is AnnotationInfo -> result.add(value)
                        else -> {
                            val len = java.lang.reflect.Array.getLength(value)
                            for (i in 0 until len) {
                                val elem = java.lang.reflect.Array.get(value, i)
                                if (elem is AnnotationInfo) result.add(elem)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun resolveMultiPreview(
        ann: AnnotationInfo,
        scanResult: ScanResult,
        visited: MutableSet<String>,
    ): List<AnnotationInfo> {
        if (ann.name in visited) return emptyList()
        if (isDirectPreview(ann) || isPreviewContainer(ann)) return emptyList()
        visited.add(ann.name)

        val annClassInfo = scanResult.getClassInfo(ann.name) ?: return emptyList()
        val directPreviews = collectDirectPreviews(annClassInfo.annotationInfo.toList())
        if (directPreviews.isNotEmpty()) return directPreviews

        val result = mutableListOf<AnnotationInfo>()
        for (metaAnn in annClassInfo.annotationInfo) {
            result.addAll(resolveMultiPreview(metaAnn, scanResult, visited))
        }
        return result
    }

    private fun makePreview(
        classInfo: ClassInfo,
        method: MethodInfo,
        ann: AnnotationInfo,
        wrapperClassName: String?,
        scrolls: List<ScrollCapture>,
        animation: AnimationCapture?,
        timings: List<Long>,
        previewParameter: Pair<String, Int>?,
    ): PreviewInfo {
        val params = extractPreviewParams(ann, wrapperClassName, previewParameter)
        val fqn = "${classInfo.name}.${method.name}"
        val suffix = buildVariantSuffix(params)
        val id = fqn + suffix
        return PreviewInfo(
            id = id,
            functionName = method.name,
            className = classInfo.name,
            sourceFile = packageQualifiedSourcePath(classInfo),
            params = params,
            captures = buildCaptures(ann, id, scrolls, animation, timings),
        )
    }

    // Package-qualified source path, e.g. "com/example/samplewear/Previews.kt".
    // The bytecode SourceFile attribute is just the basename, which collides
    // when two files with the same name live in different packages within one
    // module. Prefixing with the package path makes the value unique and lets
    // the VSCode extension / CLI resolve a preview back to the exact file.
    private fun packageQualifiedSourcePath(classInfo: ClassInfo): String? {
        val simpleName = classInfo.sourceFile ?: return null
        val pkg = classInfo.packageName.orEmpty()
        return if (pkg.isEmpty()) simpleName else "${pkg.replace('.', '/')}/$simpleName"
    }

    // Disambiguates multi-preview expansions (e.g. @WearPreviewDevices → large_round
    // + small_round) when the inner @Preview has no explicit `name`. Without this
    // every variant collides on the same id / PNG path.
    //
    // Prefer `group` — Horologist's @WearPreview* annotations set a distinct, human
    // readable group per variant (e.g. "Fonts - Large"), so it captures exactly what
    // varies. Fall back to device + fontScale + uiMode only if neither name nor
    // group is present.
    private fun buildVariantSuffix(params: PreviewParams): String {
        if (!params.name.isNullOrBlank()) return "_${params.name}"
        if (!params.group.isNullOrBlank()) return "_${sanitizeForPath(params.group)}"
        val parts = mutableListOf<String>()
        params.device?.substringAfterLast(":")?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (params.fontScale != 1.0f) parts.add("fs${params.fontScale}")
        if (params.uiMode != 0) parts.add("ui${params.uiMode}")
        return if (parts.isEmpty()) "" else "_" + parts.joinToString("_")
    }

    // Strip characters that would break file paths or IDs. Spaces are left alone
    // (they already appear in existing `_Red Box.png`-style outputs).
    private fun sanitizeForPath(s: String): String =
        s.replace(Regex("""[/\\:*?"<>|]"""), "_")

    private fun extractPreviewParams(
        ann: AnnotationInfo,
        wrapperClassName: String?,
        previewParameter: Pair<String, Int>?,
    ): PreviewParams {
        val pv = ann.parameterValues
        val kind = if (ann.name == TILE_PREVIEW_FQN) PreviewKind.TILE else PreviewKind.COMPOSE
        val device = (pv.getValue("device") as? String)?.ifBlank { null }
        val rawWidth = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 }
        val rawHeight = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 }
        val showSystemUi = (pv.getValue("showSystemUi") as? Boolean) ?: false
        // AS-parity sizing: when the user picked a device or asked for the
        // system UI frame, resolve up-front so downstream consumers (renderers,
        // VS Code extension, CLI) see the effective widthDp/heightDp and the
        // device's density. When no frame was requested, keep the raw user
        // values — nulls on either axis signal "wrap to intrinsic" to the
        // renderers, matching how Android Studio's preview pane sizes
        // component previews.
        val effectiveWidth: Int?
        val effectiveHeight: Int?
        val effectiveDensity: Float?
        if (device != null || showSystemUi) {
            val dims = DeviceDimensions.resolve(device, rawWidth, rawHeight)
            effectiveWidth = dims.widthDp
            effectiveHeight = dims.heightDp
            effectiveDensity = dims.density
        } else {
            effectiveWidth = rawWidth
            effectiveHeight = rawHeight
            // Pin Android Studio's default preview density (xxhdpi-ish, 420dpi
            // → 2.625x). Without this the Robolectric renderer defaults to
            // mdpi (1.0x), which is fine at the PNG level but fuzzy in the VS
            // Code tile grid: tiles have a `max-width: 180px`, so a 100-dp
            // composable that produced a 100-px PNG under mdpi gets upscaled
            // and looks blurry next to device-based previews rendered at
            // their native densities. Pinning here keeps wrap-content
            // previews at the same pixel density as both the Desktop
            // renderer and Studio's own preview pane.
            effectiveDensity = DeviceDimensions.DEFAULT_DENSITY
        }
        return PreviewParams(
            name = (pv.getValue("name") as? String)?.ifBlank { null },
            device = device,
            widthDp = effectiveWidth,
            heightDp = effectiveHeight,
            density = effectiveDensity,
            fontScale = (pv.getValue("fontScale") as? Float)?.takeIf { it > 0 } ?: 1.0f,
            showSystemUi = showSystemUi,
            showBackground = (pv.getValue("showBackground") as? Boolean) ?: false,
            backgroundColor = (pv.getValue("backgroundColor") as? Long) ?: 0L,
            uiMode = (pv.getValue("uiMode") as? Int)?.takeIf { it > 0 } ?: 0,
            locale = (pv.getValue("locale") as? String)?.ifBlank { null },
            group = (pv.getValue("group") as? String)?.ifBlank { null },
            // @PreviewWrapper targets composables. Tile previews aren't composable,
            // so even if the annotation happened to be present on the function,
            // the wrapper's `Wrap(content)` would never wrap the tile View.
            wrapperClassName = if (kind == PreviewKind.TILE) null else wrapperClassName,
            // @PreviewParameter targets composables too. Tile preview functions
            // return `TilePreviewData`; the renderer reflects them directly and
            // has no code path for injecting a provider value.
            previewParameterProviderClassName =
                if (kind == PreviewKind.TILE) null else previewParameter?.first,
            previewParameterLimit = previewParameter?.second ?: Int.MAX_VALUE,
            kind = kind,
            // @ScrollingPreview is applied by `makePreview` via `.copy(scroll = …)` so
            // the timings fan-out and scroll spec live side-by-side in one place.
        )
    }
}
