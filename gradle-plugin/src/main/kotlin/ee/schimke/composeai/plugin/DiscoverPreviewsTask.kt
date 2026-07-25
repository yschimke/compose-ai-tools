package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.PreviewDiscovery
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
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

  /**
   * The module's own compiled classes laid out as directories, sourced from AGP's scoped `PROJECT`
   * `CLASSES` artifact (`variant.artifacts.forScope(PROJECT).toGet(CLASSES, …)`). Wired by the
   * Android backend in addition to [classDirs]; resolving the scoped artifact also creates the
   * implicit task dependency on whichever task compiled the classes — the standalone Kotlin Gradle
   * Plugin's `compile<Variant>Kotlin` OR AGP 9.x built-in Kotlin (`built_in_kotlinc`), whose output
   * the legacy hardcoded `build/tmp/kotlin-classes/<variant>` directory never receives. Optional /
   * empty on non-Android backends (desktop/JVM). See issue #1924.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassDirs: ListProperty<Directory>

  /**
   * The module's own compiled classes packaged as jars, the jar half of AGP's scoped `PROJECT`
   * `CLASSES` artifact (see [projectClassDirs]). Method-walked as project classes by
   * [PreviewDiscovery] — unlike [dependencyJars] — so previews compiled into a project jar are
   * discovered. Optional / empty on non-Android backends. See issue #1924.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassJars: ListProperty<RegularFile>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val dependencyJars: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection

  /**
   * Processed-resource roots (`build/resources/main`, `build/processedResources/<target>/main`)
   * scanned for Lottie animation assets — each becomes a `kind=LOTTIE` preview with no consumer
   * composable. Optional: empty on modules without resources, which simply skips the asset scan.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val resourceDirs: ConfigurableFileCollection

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

  /**
   * Subdirectory for Lottie capture `renderOutput` paths (see
   * [PreviewDiscovery.Input.lottieRenderSubdir]). Defaults to `"renders"`; the Android task sets a
   * disjoint dir so its JVM Lottie render doesn't share the `renders/` output with the Robolectric
   * render.
   */
  @get:Input abstract val lottieRenderSubdir: Property<String>

  /**
   * Subdirectory for `kind=SVG` capture `renderOutput` paths (see
   * [PreviewDiscovery.Input.svgRenderSubdir]). Defaults to `"renders"`; the Android task sets a
   * disjoint dir so its JVM SVG render doesn't share the `renders/` output with the Robolectric
   * render.
   */
  @get:Input abstract val svgRenderSubdir: Property<String>

  /**
   * Whether this module's render backend can draw `@ColorCatalog` sheets. The Android backend can
   * (default `true`); the desktop backend can't yet (#2135), so it passes `false` and discovery
   * marks the synthetic `CATALOG` captures `optional` — the single flag every consumer reads (the
   * render gate, VS Code's consistency check + render UI) to know a missing catalog PNG is expected
   * on that backend rather than a regression.
   */
  @get:Input abstract val catalogRenderSupported: Property<Boolean>

  /**
   * Whether a Wear module's device-less previews are retargeted onto the Wear canvas (227dp @
   * 2.0x). `true` (default) keeps the historical behaviour; `false` opts out so device-less
   * previews stay wrap-content and the renderer crops each PNG to its intrinsic layout bounds —
   * needed for Wear widget/tile previews exported as fixed-size drawable assets (#2670). No effect
   * on non-Wear modules. Wired from the `composePreview.retargetWearPreviews` extension /
   * `-PcomposePreview.retargetWearPreviews=false` Gradle property.
   */
  @get:Input abstract val retargetWearPreviews: Property<Boolean>

  /**
   * The variant's merged `AndroidManifest.xml` (AGP `SingleArtifact.MERGED_MANIFEST`). Used to
   * detect whether this is a Wear OS module — a `<uses-feature android:name=
   * "android.hardware.type.watch" …>` declaration — so frame-less, device-less component previews
   * render at wear density/width instead of the phone default, and for app-level discovery: its
   * `<activity>` declarations become [PreviewManifest.activities] metadata plus synthetic
   * `kind=ACTIVITY` previews (the launcher activity's render is the app's hero image), and its
   * launcher activity is the default start for tour specs. Optional: absent on the desktop backend
   * and on Android modules with no manifest artifact — treated as non-Wear, no app-level previews.
   */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedManifest: RegularFileProperty

  /**
   * Committed tour scripts (`compose-previews/tours/<name>.json` under the module root), each
   * becoming a synthetic `kind=APP_TOUR` preview whose captures are the tour's steps. Only honoured
   * when [mergedManifest] is present (tours launch real activities — Android backend only).
   * Optional / empty on modules without tours.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val tourSpecFiles: ConfigurableFileCollection

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  @TaskAction
  fun discover() {
    // Union the directory-scan candidates ([classDirs]) with the scoped PROJECT
    // CLASSES directories so the module's own classes are found regardless of
    // which compiler produced them. ClassGraph attributes each FQN to a single
    // element and previews are deduped by id, so any overlap between the two
    // sources is harmless. See issue #1924.
    val scopedClassDirs = projectClassDirs.getOrElse(emptyList()).map { it.asFile }
    val scopedClassJars = projectClassJars.getOrElse(emptyList()).map { it.asFile }
    // A Wear OS module declares `<uses-feature android:name="android.hardware.type.watch">` in its
    // merged manifest. Plain-substring match on the raw XML — enough to distinguish a Wear module
    // from a phone one without pulling in an XML parser; absent manifest → not Wear.
    val isWear =
      mergedManifest.orNull
        ?.asFile
        ?.takeIf { it.exists() }
        ?.let { it.readText().contains("android.hardware.type.watch") } ?: false
    val input =
      PreviewDiscovery.Input(
        classDirs = classDirs.files.toList() + scopedClassDirs,
        dependencyJars = dependencyJars.files.toList(),
        sourceFiles = sourceFiles.files.toList(),
        moduleName = moduleName.get(),
        variantName = variantName.get(),
        projectDirectory = File(projectDirectory.get()),
        failOnEmpty = failOnEmpty.get(),
        resourceDirs = resourceDirs.files.toList(),
        lottieRenderSubdir = lottieRenderSubdir.getOrElse("renders"),
        svgRenderSubdir = svgRenderSubdir.getOrElse("renders"),
        projectClassJars = scopedClassJars,
        catalogRenderSupported = catalogRenderSupported.getOrElse(true),
        isWear = isWear,
        retargetWearPreviews = retargetWearPreviews.getOrElse(true),
        mergedManifest = mergedManifest.orNull?.asFile?.takeIf { it.exists() },
        tourSpecFiles = tourSpecFiles.files.filter { it.isFile },
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
        // Surface per-method skip reasons (e.g. unsupported parameters) before the
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
