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

        internal const val TILE_PREVIEW_FQN = "androidx.wear.tiles.tooling.preview.Preview"

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
        val classpath = existingClassDirs + dependencyJars.files.filter { file ->
            val name = file.name.lowercase()
            file.exists() && name.endsWith(".jar") &&
                (name.contains("preview") || name.contains("tooling") ||
                    name.contains("compose") || name.contains("annotation"))
        }

        val previews = mutableListOf<PreviewInfo>()

        if (classpath.isNotEmpty()) {
            ClassGraph()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .overrideClasspath(classpath.map { it.absolutePath })
                .ignoreParentClassLoaders()
                .scan().use { scanResult ->
                    for (classInfo in scanResult.allClasses) {
                        for (method in classInfo.methodInfo) {
                            val annotations = method.annotationInfo ?: continue
                            discoverFromMethod(classInfo, method, annotations.toList(), scanResult, previews)
                        }
                    }
                }
        }

        // id already encodes the name + (device, fontScale, uiMode) variant suffix, so
        // dedup by id alone. Two identical preview variants on the same function collapse.
        val deduped = previews.distinctBy { it.id }

        val manifest = PreviewManifest(
            module = moduleName.get(),
            variant = variantName.get(),
            previews = deduped,
            accessibilityReport = "accessibility.json".takeIf { accessibilityChecksEnabled.get() },
        )

        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(json.encodeToString(manifest))

        logger.lifecycle("Discovered ${deduped.size} preview(s) in module '${moduleName.get()}':")
        for (preview in deduped) {
            logger.lifecycle("  ${preview.className}.${preview.functionName}${describeVariant(preview)}")
        }
    }

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
        preview.captures.firstNotNullOfOrNull { it.scroll }?.let { s ->
            parts.add("scroll=${s.mode.name.lowercase()}")
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
        // multi-preview meta-annotations).
        val wrapperFqn = extractWrapperFqn(annotations)
        val scrollSpec = extractScrollSpec(annotations)
        // @RoboComposePreviewOptions, similarly, applies to the function as a
        // whole — each timing fans out into its own manifest entry, orthogonal
        // to any multi-preview expansion.
        val timings = extractRoboTimings(annotations)

        val directPreviews = collectDirectPreviews(annotations)
        if (directPreviews.isNotEmpty()) {
            for (ann in directPreviews) {
                previews.add(makePreview(classInfo, method, ann, wrapperFqn, scrollSpec, timings))
            }
            return
        }

        for (ann in annotations) {
            val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
            for (resolvedAnn in resolved) {
                previews.add(makePreview(classInfo, method, resolvedAnn, wrapperFqn, scrollSpec, timings))
            }
        }
    }

    // Tile previews don't go through `mainClock` and can't scroll (the
    // renderer inflates a View via `TileRenderer` and has no Compose
    // animation clock / scrollable), so both dimensional annotations are
    // no-ops for tiles.
    private fun buildCaptures(
        ann: AnnotationInfo,
        previewId: String,
        scroll: ScrollCapture?,
        timings: List<Long>,
    ): List<Capture> {
        val isTile = ann.name == TILE_PREVIEW_FQN
        val effectiveTimings = if (isTile) emptyList() else timings
        val effectiveScroll = scroll.takeUnless { isTile }

        if (effectiveTimings.isEmpty()) {
            // Single capture — either a static preview (all dims null) or a
            // scrolled-only preview (scroll set, time null).
            return listOf(
                Capture(
                    advanceTimeMillis = null,
                    scroll = effectiveScroll,
                    renderOutput = "renders/${previewId}.png",
                ),
            )
        }
        // Time-dimensional fan-out. Filename suffix matches Roborazzi's
        // compose-preview-scanner-support convention (`_TIME_<ms>ms`) so
        // PNGs coexist on disk under a single preview id. Scroll applies
        // to every time-frame when both annotations are present (today we
        // only fan out along time, not scroll).
        return effectiveTimings.map { ms ->
            Capture(
                advanceTimeMillis = ms,
                scroll = effectiveScroll,
                renderOutput = "renders/${previewId}_TIME_${ms}ms.png",
            )
        }
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

    private fun extractScrollSpec(annotations: List<AnnotationInfo>): ScrollCapture? {
        val ann = annotations.firstOrNull { it.name == SCROLLING_PREVIEW_FQN } ?: return null
        val pv = ann.parameterValues
        // Enum constants come through as AnnotationEnumValue; compare by .valueName so
        // we never force-load the annotation's classes.
        val mode = (pv.getValue("mode") as? AnnotationEnumValue)?.valueName
            ?.let { runCatching { ScrollMode.valueOf(it) }.getOrNull() }
            ?: return null
        val axis = (pv.getValue("axis") as? AnnotationEnumValue)?.valueName
            ?.let { runCatching { ScrollAxis.valueOf(it) }.getOrNull() }
            ?: ScrollAxis.VERTICAL
        val maxScrollPx = (pv.getValue("maxScrollPx") as? Int)?.coerceAtLeast(0) ?: 0
        val reduceMotion = (pv.getValue("reduceMotion") as? Boolean) ?: true
        // Result fields (atEnd, reachedPx) default to "not reported" — the
        // renderer would fill them in post-capture; discovery knows only the
        // intent.
        return ScrollCapture(
            mode = mode,
            axis = axis,
            maxScrollPx = maxScrollPx,
            reduceMotion = reduceMotion,
        )
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
        scroll: ScrollCapture?,
        timings: List<Long>,
    ): PreviewInfo {
        val params = extractPreviewParams(ann, wrapperClassName)
        val fqn = "${classInfo.name}.${method.name}"
        val suffix = buildVariantSuffix(params)
        val id = fqn + suffix
        return PreviewInfo(
            id = id,
            functionName = method.name,
            className = classInfo.name,
            sourceFile = packageQualifiedSourcePath(classInfo),
            params = params,
            captures = buildCaptures(ann, id, scroll, timings),
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
    ): PreviewParams {
        val pv = ann.parameterValues
        val kind = if (ann.name == TILE_PREVIEW_FQN) PreviewKind.TILE else PreviewKind.COMPOSE
        val device = (pv.getValue("device") as? String)?.ifBlank { null }
        val rawWidth = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 }
        val rawHeight = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 }
        // Resolve dimensions up-front so downstream consumers (renderers, VSCode
        // extension, CLI) see the effective widthDp/heightDp instead of having to
        // each re-implement DeviceDimensions. RenderPreviewsTask still calls resolve()
        // — passing already-resolved values is a no-op path.
        val dims = DeviceDimensions.resolve(device, rawWidth, rawHeight)
        return PreviewParams(
            name = (pv.getValue("name") as? String)?.ifBlank { null },
            device = device,
            widthDp = dims.widthDp,
            heightDp = dims.heightDp,
            density = dims.density,
            fontScale = (pv.getValue("fontScale") as? Float)?.takeIf { it > 0 } ?: 1.0f,
            showSystemUi = (pv.getValue("showSystemUi") as? Boolean) ?: false,
            showBackground = (pv.getValue("showBackground") as? Boolean) ?: false,
            backgroundColor = (pv.getValue("backgroundColor") as? Long) ?: 0L,
            uiMode = (pv.getValue("uiMode") as? Int)?.takeIf { it > 0 } ?: 0,
            locale = (pv.getValue("locale") as? String)?.ifBlank { null },
            group = (pv.getValue("group") as? String)?.ifBlank { null },
            // @PreviewWrapper targets composables. Tile previews aren't composable,
            // so even if the annotation happened to be present on the function,
            // the wrapper's `Wrap(content)` would never wrap the tile View.
            wrapperClassName = if (kind == PreviewKind.TILE) null else wrapperClassName,
            kind = kind,
            // @ScrollingPreview is applied by `makePreview` via `.copy(scroll = …)` so
            // the timings fan-out and scroll spec live side-by-side in one place.
        )
    }
}
