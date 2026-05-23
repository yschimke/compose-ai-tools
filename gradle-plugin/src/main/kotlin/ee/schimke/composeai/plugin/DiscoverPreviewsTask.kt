package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.PreviewDiscovery
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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

/**
 * Gradle adapter over [PreviewDiscovery]. Resolves the task's Gradle-typed inputs to plain
 * `java.io.File` / `String` values, hands them to the pure-JVM library, routes warnings and the
 * discovery summary back through Gradle's logger, and writes the resulting `previews.json` to
 * [outputFile].
 *
 * The scan logic itself lives in `:preview-discovery` so non-Gradle build systems (Bazel rules,
 * Amper task definitions in `yschimke/compose-ai-contrib`) can drive it without depending on Gradle
 * or AGP. See [PreviewDiscovery] for the library contract; this file is intentionally thin and
 * should stay that way.
 */
@CacheableTask
abstract class DiscoverPreviewsTask : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classDirs: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val dependencyJars: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection

  @get:Input abstract val moduleName: Property<String>

  @get:Input abstract val variantName: Property<String>

  /**
   * Project root path used to render module-relative source paths in the manifest. Captured at
   * configuration time so the task action stays configuration-cache-safe.
   */
  @get:Input abstract val projectDirectory: Property<String>

  /**
   * When `true` and discovery produces zero previews, emit a diagnostics block to the lifecycle log
   * (classDirs contents, post-filter dep-JAR sample, ClassGraph scan summary, observed annotation
   * FQNs) and fail the task. Wired from the `composePreview.failOnEmpty` extension /
   * `-PcomposePreview.failOnEmpty=true` Gradle property.
   */
  @get:Input abstract val failOnEmpty: Property<Boolean>

  // a11y data products are daemon-only — the standalone Gradle path neither produces them nor
  // stamps a manifest pointer for them. New per-extension report rollups would add their own
  // dedicated input here when they have an on-disk artefact to point at.

  @get:OutputFile abstract val outputFile: RegularFileProperty

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  @TaskAction
  fun discover() {
    val input =
      PreviewDiscovery.Input(
        classDirs = classDirs.files.toList(),
        dependencyJars = dependencyJars.files.toList(),
        sourceFiles = sourceFiles.files.toList(),
        moduleName = moduleName.get(),
        variantName = variantName.get(),
        projectDirectory = File(projectDirectory.get()),
        failOnEmpty = failOnEmpty.get(),
      )
    when (val outcome = PreviewDiscovery.discover(input)) {
      is PreviewDiscovery.Outcome.Success -> {
        outcome.warnings.forEach { logger.warn(it) }
        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(json.encodeToString(outcome.manifest))
        outcome.infoMessages.forEach { logger.lifecycle(it) }
      }
      is PreviewDiscovery.Outcome.Failure -> {
        // Surface per-method skip reasons (private @Preview, unsupported parameters) before the
        // diagnostics dump so users can see WHY a method was filtered out — when failOnEmpty=true
        // and every candidate was skipped, these warnings are the most actionable signal. Mirrors
        // the Success branch's warning emission so the failure path doesn't drop them.
        outcome.warnings.forEach { logger.warn(it) }
        outcome.diagnostics.forEach { logger.lifecycle(it) }
        throw GradleException(outcome.reason)
      }
    }
  }
}
