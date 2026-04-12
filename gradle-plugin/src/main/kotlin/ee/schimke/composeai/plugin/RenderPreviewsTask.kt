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

    @get:Classpath
    abstract val renderClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun render() {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<PreviewManifest>(previewsJson.get().asFile.readText())

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

        logger.lifecycle("Rendered ${manifest.previews.size} preview(s)")
    }

    private fun renderWithCompose(manifest: PreviewManifest, outDir: java.io.File) {
        // This path is only used for desktop rendering.
        // Android rendering uses a separate Test-type task (see ComposePreviewPlugin).
        val mainClass = "ee.schimke.composeai.renderer.DesktopRendererMainKt"

        for (preview in manifest.previews) {
            val dims = DeviceDimensions.resolve(
                preview.params.device,
                preview.params.widthDp,
                preview.params.heightDp,
            )
            val density = 2.0f
            val widthPx = (dims.widthDp * density).toInt().coerceAtLeast(1)
            val heightPx = (dims.heightDp * density).toInt().coerceAtLeast(1)
            val outputFile = outDir.resolve("${preview.id}.png")

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
                )
            }
        }
    }

    private fun renderWithStub(manifest: PreviewManifest, outDir: java.io.File) {
        val workQueue = workerExecutor.noIsolation()

        for (preview in manifest.previews) {
            val dims = DeviceDimensions.resolve(
                preview.params.device,
                preview.params.widthDp,
                preview.params.heightDp,
            )
            workQueue.submit(PreviewRenderWorkAction::class.java) {
                className.set(preview.className)
                functionName.set(preview.functionName)
                widthDp.set(dims.widthDp)
                heightDp.set(dims.heightDp)
                fontScale.set(preview.params.fontScale)
                showBackground.set(preview.params.showBackground)
                backgroundColor.set(preview.params.backgroundColor)
                outputFile.set(outDir.resolve("${preview.id}.png"))
                backend.set(renderBackend.get())
            }
        }
    }
}
