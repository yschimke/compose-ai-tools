package ee.schimke.composeai.plugin

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Walks the consumer's `res/` source roots and the merged `AndroidManifest.xml`, classifies XML
 * drawables / mipmaps via [ResourceXmlClassifier], and writes
 * `build/compose-previews/resources.json` containing one [ResourcePreview] per supported resource
 * plus a list of [ManifestReference] rows.
 *
 * No rendering happens here — captures land with their intended `renderOutput` paths but the PNGs /
 * GIFs are produced later by the (still-to-come) `renderAndroidResources` task. Wiring is split so
 * the manifest is cheap to regenerate (sub-second walks) and tooling can read the index without
 * waiting for Robolectric to spin up.
 */
@CacheableTask
abstract class DiscoverAndroidResourcesTask : DefaultTask() {

  /** Root `res/` directories from `Variant.sources.res.all`. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val resSourceRoots: ConfigurableFileCollection

  /** Merged `AndroidManifest.xml` from `Variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`. */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val mergedManifest: RegularFileProperty

  @get:Input abstract val moduleName: Property<String>

  @get:Input abstract val variantName: Property<String>

  @get:Input abstract val densities: ListProperty<String>

  @get:Input abstract val shapes: ListProperty<AdaptiveShape>

  /**
   * Project root path used to render module-relative paths in the manifest. Captured at
   * configuration time (via `project.layout.projectDirectory.asFile.absolutePath`) so the task
   * action stays config-cache-safe.
   */
  @get:Input abstract val projectDirectory: Property<String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun discover() {
    val projectRoot = File(projectDirectory.get())
    val sourceRoots = resSourceRoots.files.toList().filter { it.exists() }
    val resources =
      ResourceDiscovery.discover(
        ResourceDiscovery.Config(
          resSourceRoots = sourceRoots,
          densities = densities.get(),
          shapes = shapes.get(),
          sourceRootRelativePath = { root -> root.toRelativeStringSafe(projectRoot) },
        )
      )

    val references =
      mergedManifest.orNull
        ?.asFile
        ?.takeIf { it.exists() }
        ?.let { manifest ->
          ManifestReferenceExtractor.extract(
            file = manifest,
            source = manifest.toRelativeStringSafe(projectRoot),
          )
        } ?: emptyList()

    val manifest =
      ResourceManifest(
        module = moduleName.get(),
        variant = variantName.get(),
        resources = resources,
        manifestReferences = references,
      )
    val outFile = outputFile.get().asFile
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(manifest))
    logger.lifecycle(
      "Discovered ${resources.size} resource preview" +
        (if (resources.size == 1) "" else "s") +
        " and ${references.size} manifest reference" +
        (if (references.size == 1) "" else "s") +
        " for ${moduleName.get()}:${variantName.get()}"
    )
  }

  private fun File.toRelativeStringSafe(root: File): String {
    return try {
      relativeTo(root).path.replace(File.separatorChar, '/')
    } catch (_: IllegalArgumentException) {
      // File isn't under root (rare — shared resource roots in composite builds). Fall back to the
      // absolute path; tooling will treat it as opaque.
      absolutePath.replace(File.separatorChar, '/')
    }
  }

  private companion object {
    val json = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}
