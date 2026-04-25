package ee.schimke.composeai.plugin

import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class RenderPreviewsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val previewsJson: RegularFileProperty

    @get:Input
    abstract val renderBackend: Property<String>

    @get:Input
    abstract val useComposeRenderer: Property<Boolean>

    /**
     * Render-tier filter. When `"fast"` the desktop / stub path skips any
     * preview whose representative capture is heavier than
     * [HEAVY_COST_THRESHOLD] (TOP / static stay in; LONG / GIF / animated
     * fall out). Default `"full"` keeps the historical behaviour (every
     * preview rendered).
     */
    @get:Input
    abstract val tier: Property<String>

    @get:Classpath
    abstract val renderClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    init {
        // Caching is intentionally gated on `tier=full`: a `tier=fast` run
        // only writes a subset of captures (fast ones), so a build-cache
        // restore from a fast snapshot would *wipe* the previous full run's
        // heavy outputs from `outputDir` — exactly the stale images the
        // interactive UI relies on. Up-to-date checks still apply, so a
        // re-run with no input changes is a no-op and the renders directory
        // stays as-is regardless of tier.
        outputs.cacheIf("renderPreviews caches tier=full runs only") {
            tier.get().equals("full", ignoreCase = true)
        }
    }

    @TaskAction
    fun render() {
        val json = Json { ignoreUnknownKeys = true }
        val rawManifest = json.decodeFromString<PreviewManifest>(previewsJson.get().asFile.readText())

        // Tier filter — drop previews whose representative capture is heavy
        // when running in `fast` mode. The desktop / stub path renders just
        // the first capture per preview, so the decision is per-preview
        // rather than per-capture (unlike the Robolectric path which can
        // pick and choose among an entry's captures). Skipped previews keep
        // their previous PNG on disk (referenced by the manifest, untouched
        // by `cleanStaleRenders`) so VS Code can still display the stale
        // image with its badge.
        val isFastTier = tier.get().equals("fast", ignoreCase = true)
        val previews = if (!isFastTier) rawManifest.previews else rawManifest.previews.filter {
            val firstCost = it.captures.firstOrNull()?.cost ?: STATIC_COST
            !isHeavyCost(firstCost)
        }
        val manifest = if (isFastTier) rawManifest.copy(previews = previews) else rawManifest

        if (manifest.previews.isEmpty()) {
            logger.lifecycle("No previews to render.")
            return
        }

        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        if (useComposeRenderer.get()) {
            renderWithCompose(manifest, outDir)
        } else {
            renderWithStub(manifest, outDir)
        }

        val tierTag = if (isFastTier) " (fast tier; ${rawManifest.previews.size - manifest.previews.size} heavy skipped)" else ""
        logger.lifecycle("Rendered ${manifest.previews.size} preview(s)$tierTag")
    }

    private fun renderWithCompose(manifest: PreviewManifest, outDir: java.io.File) {
        // This path is only used for desktop rendering.
        // Android rendering uses a separate Test-type task (see ComposePreviewPlugin).
        val mainClass = "ee.schimke.composeai.renderer.DesktopRendererMainKt"

        for (preview in manifest.previews) {
            val spec = DeviceDimensions.resolveForRender(
                device = preview.params.device,
                widthDp = preview.params.widthDp,
                heightDp = preview.params.heightDp,
                showSystemUi = preview.params.showSystemUi,
            )
            // Per-device density (= densityDpi / 160), so output bitmaps match
            // what Android Studio renders for the same `@Preview`. Source: the
            // same data sergio-sastre/ComposablePreviewScanner /
            // takahirom/roborazzi consume. Discovery pins `params.density` when
            // a device/showSystemUi frame applies; the wrap-content path leaves
            // it null and we fall back to `spec.density` (= DEFAULT_DENSITY).
            val density = preview.params.density ?: spec.density
            val widthPx = (spec.widthDp * density).toInt().coerceAtLeast(1)
            val heightPx = (spec.heightDp * density).toInt().coerceAtLeast(1)
            // The discovery task writes a normalized `renderOutput` (package
            // prefix stripped, unsafe chars sanitized) into each capture;
            // honour it rather than rebuilding the path from `preview.id`
            // so the file actually lands where the manifest claims it will.
            val relRender = preview.captures.firstOrNull()
                ?.renderOutput
                ?.substringAfter("renders/", missingDelimiterValue = "")
                ?.takeIf { it.isNotEmpty() }
                ?: "${preview.id}.png"
            val outputFile = outDir.resolve(relRender)

            execOperations.javaexec {
                classpath = renderClasspath
                this.mainClass.set(mainClass)
                args = listOf(
                    preview.className,
                    preview.functionName,
                    widthPx.toString(),
                    heightPx.toString(),
                    density.toString(),
                    preview.params.showBackground.toString(),
                    preview.params.backgroundColor.toString(),
                    outputFile.absolutePath,
                    // 9th arg — empty string signals "no wrapper" (keeps arg positions stable).
                    preview.params.wrapperClassName.orEmpty(),
                    // 10th/11th — AS-parity wrap flags. When set, the renderer
                    // wraps the composable, measures it, and crops the PNG to
                    // the intrinsic bounds on that axis.
                    spec.wrapWidth.toString(),
                    spec.wrapHeight.toString(),
                    // 12th/13th — @PreviewParameter spec. Empty string signals
                    // "no provider"; otherwise the renderer enumerates the
                    // provider's values.take(limit) in-process and writes one
                    // `<id>_PARAM_<idx>.png` per value. Plugin-side can't know
                    // the count (consumer's classpath isn't loaded here), so
                    // fan-out is delegated to the renderer process that already
                    // has everything on its classpath.
                    preview.params.previewParameterProviderClassName.orEmpty(),
                    preview.params.previewParameterLimit.toString(),
                )
            }
        }
    }

    private fun renderWithStub(manifest: PreviewManifest, outDir: java.io.File) {
        val workQueue = workerExecutor.noIsolation()

        for (preview in manifest.previews) {
            val spec = DeviceDimensions.resolveForRender(
                device = preview.params.device,
                widthDp = preview.params.widthDp,
                heightDp = preview.params.heightDp,
                showSystemUi = preview.params.showSystemUi,
            )
            val relRender = preview.captures.firstOrNull()
                ?.renderOutput
                ?.substringAfter("renders/", missingDelimiterValue = "")
                ?.takeIf { it.isNotEmpty() }
                ?: "${preview.id}.png"
            workQueue.submit(PreviewRenderWorkAction::class.java) {
                className.set(preview.className)
                functionName.set(preview.functionName)
                widthDp.set(spec.widthDp)
                heightDp.set(spec.heightDp)
                density.set(preview.params.density ?: spec.density)
                fontScale.set(preview.params.fontScale)
                showBackground.set(preview.params.showBackground)
                backgroundColor.set(preview.params.backgroundColor)
                outputFile.set(outDir.resolve(relRender))
                backend.set(renderBackend.get())
            }
        }
    }
}
