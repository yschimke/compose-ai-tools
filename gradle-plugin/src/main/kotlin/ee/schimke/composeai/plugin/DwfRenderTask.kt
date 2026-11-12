package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewKind
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

/**
 * Compiles and renders Declarative Watch Face (`<WatchFace>`) XML previews discovered in
 * [previewsJson] via `ee.schimke.composeai.rcjvm.DwfRenderMainKt`, writing `<stem>.png`,
 * `<stem>.rc`, and `<stem>.remotecompose.json` into [outputDir].
 */
@CacheableTask
abstract class DwfRenderTask : DefaultTask() {

  @get:Internal abstract val projectDirectory: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val previewsJson: RegularFileProperty

  @get:Classpath abstract val dwfRendererClasspath: ConfigurableFileCollection

  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val resourceDirs: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Optional @get:Input abstract val renderJavaExecutable: Property<String>

  @get:Input abstract val tier: Property<String>

  @get:Input abstract val previewFilters: ListProperty<String>

  @Option(
    option = "preview",
    description = "Render only previews whose simple or fully-qualified name matches this pattern.",
  )
  fun setPreviewFilterOption(values: List<String>) {
    previewFilters.set(values)
  }

  @get:Input abstract val previewIdFilters: ListProperty<String>

  @Option(
    option = "preview-id",
    description = "Render only previews whose discovered id matches this pattern.",
  )
  fun setPreviewIdFilterOption(values: List<String>) {
    previewIdFilters.set(values)
  }

  @get:Input abstract val previewIdExcludes: ListProperty<String>

  @Option(
    option = "exclude-preview-id",
    description = "Skip previews whose discovered id matches this pattern.",
  )
  fun setPreviewIdExcludeOption(values: List<String>) {
    previewIdExcludes.set(values)
  }

  @get:Inject protected abstract val execOperations: ExecOperations

  init {
    tier.convention("full")
    previewFilters.convention(emptyList())
    previewIdFilters.convention(emptyList())
    previewIdExcludes.convention(emptyList())
    outputs.cacheIf("composePreviewRenderDwf caches full, unfiltered runs only") {
      tier.get().equals("full", ignoreCase = true) &&
        previewFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
        previewIdFilters.getOrElse(emptyList()).none { it.isNotBlank() } &&
        previewIdExcludes.getOrElse(emptyList()).none { it.isNotBlank() }
    }
  }

  @TaskAction
  fun render() {
    val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }
    val manifestFile = previewsJson.get().asFile
    if (!manifestFile.isFile) return
    val rawManifest = json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())

    val nameFiltered =
      selectNamedPreviews(
        rawManifest.previews,
        previewFilters.getOrElse(emptyList()),
        manifestFile.absolutePath,
      )
    val idSelected =
      selectPreviewIds(
        nameFiltered,
        previewIdFilters.getOrElse(emptyList()),
        manifestFile.absolutePath,
        rawManifest.previews,
      )
    val idFiltered = excludePreviewIds(idSelected, previewIdExcludes.getOrElse(emptyList()))
    val dwfPreviews: List<PreviewInfo> = idFiltered.filter { it.params.dwfAssetPath != null }

    val outDir = outputDir.get().asFile
    outDir.mkdirs()
    if (dwfPreviews.isEmpty()) {
      logger.info("composePreviewRenderDwf: no DWF watch face previews selected.")
      return
    }

    val filteredManifestFile = File(temporaryDir, "dwf-previews.json")
    filteredManifestFile.parentFile.mkdirs()
    filteredManifestFile.writeText(
      json.encodeToString(PreviewManifest.serializer(), rawManifest.copy(previews = dwfPreviews)),
    )

    val cliArgs = buildList {
      add("--previews-json")
      add(filteredManifestFile.absolutePath)
      add("--output-dir")
      add(outDir.absolutePath)
      resourceDirs.files
        .filter { it.isDirectory }
        .forEach { dir ->
          add("--resource-dir")
          add(dir.absolutePath)
        }
    }

    execOperations.javaexec {
      projectDirectory.orNull?.asFile?.let { workingDir = it }
      renderJavaExecutable.orNull?.takeIf { it.isNotBlank() }?.let { executable = it }
      classpath = dwfRendererClasspath
      mainClass.set("ee.schimke.composeai.rcjvm.DwfRenderMainKt")
      jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dapple.awt.UIElement=true",
        "-Djava.awt.headless=true",
      )
      args = cliArgs
    }

    logger.lifecycle("Rendered ${dwfPreviews.size} Declarative Watch Face preview(s)")
  }
}

/**
 * Rewrites `previews.json` into `previews-robolectric.json` with Declarative Watch Face entries
 * marked `kind = SVG` so `RobolectricRenderTest` matches `--preview-id dwf__*` filters without
 * throwing while skipping DWF entries before composable reflection (`composePreviewRenderDwf`
 * renders them instead).
 */
@CacheableTask
abstract class PrepareRobolectricManifestTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val previewsJson: RegularFileProperty

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun rewrite() {
    val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }
    val raw =
      json.decodeFromString(PreviewManifest.serializer(), previewsJson.get().asFile.readText())
    val rewritten =
      raw.copy(
        previews =
          raw.previews.map { entry ->
            if (entry.params.dwfAssetPath != null) {
              entry.copy(params = entry.params.copy(kind = PreviewKind.SVG))
            } else {
              entry
            }
          },
      )
    val out = outputFile.get().asFile
    out.parentFile.mkdirs()
    out.writeText(json.encodeToString(PreviewManifest.serializer(), rewritten))
  }
}
