package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentRecords
import ee.schimke.composeai.discovery.PreviewDiscovery
import ee.schimke.composeai.discovery.PreviewManifest
import ee.schimke.composeai.discovery.UiBuilderCatalogs
import ee.schimke.composeai.discovery.UiBuilderPolicyFile
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
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

/**
 * Gradle adapter over [PreviewDiscovery]: resolves Gradle-typed inputs to plain values, routes
 * warnings and the summary to Gradle's logger, and writes `previews.json` to [outputFile]. The scan
 * itself lives in `:preview-discovery` so non-Gradle build systems can use it; keep this file thin.
 */
@CacheableTask
abstract class DiscoverPreviewsTask : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classDirs: ConfigurableFileCollection

  /**
   * Class dirs of the active compilation. [classDirs] may include fallbacks whose stale classes
   * must not hide an empty restored output.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val activeClassDirs: ConfigurableFileCollection

  /**
   * The module's classes as directories from AGP's scoped `PROJECT` `CLASSES` artifact, in addition
   * to [classDirs]. Also wires the dependency on whichever task compiled them, including AGP 9
   * built-in Kotlin, whose output never reaches the legacy directory (#1924). Empty off Android.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassDirs: ListProperty<Directory>

  /**
   * The jar half of the same artifact, method-walked as project classes (unlike [dependencyJars])
   * (#1924). Empty off Android.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassJars: ListProperty<RegularFile>

  /**
   * Compiled output of `composePreviewSource` modules, whose previews this module renders. One
   * collection because a producer may resolve to a jar, an extracted `classes.jar` or a directory;
   * [discover] sorts them, since a jar on `classDirs` would be silently dropped. Method-walked as
   * project classes, unlike dependency jars.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val previewSourceClasses: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val dependencyJars: ConfigurableFileCollection

  /**
   * Maven coordinate of each [dependencyJars] entry by absolute path; see
   * [PreviewDiscovery.Input.dependencyJarCoordinates]. `@Internal`: derived from the tracked
   * [dependencyJars], and absolute-path keys would tie the cache key to one machine.
   */
  @get:Internal abstract val dependencyJarCoordinates: MapProperty<String, String>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection

  /**
   * Source files compiled into [activeClassDirs], used only by the empty-output integrity guard.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val activeSourceFiles: ConfigurableFileCollection

  /** Processed-resource roots scanned for Lottie assets (`kind=LOTTIE`). Empty skips the scan. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val resourceDirs: ConfigurableFileCollection

  @get:Input abstract val moduleName: Property<String>

  @get:Input abstract val variantName: Property<String>

  /** Project root for module-relative source paths, captured at configuration time. */
  @get:Input abstract val projectDirectory: Property<String>

  /**
   * When `true` and zero previews are found, log diagnostics and fail. From
   * `composePreview.failOnEmpty` / `-PcomposePreview.failOnEmpty=true`.
   */
  @get:Input abstract val failOnEmpty: Property<Boolean>

  // No a11y input: a11y is daemon-only.

  @get:OutputFile abstract val outputFile: RegularFileProperty

  /**
   * `components.json` (see `ComponentRecordFile`). A declared output because this task is cacheable
   * and only declared outputs are restored from the cache.
   */
  @get:OutputFile abstract val componentsFile: RegularFileProperty

  /**
   * `ui-builder.policy.json` candidates, module first, then repository root; the first that exists
   * wins. A file collection because a missing `@InputFile` fails the build, and "no policy" is
   * normal. Both locations occur (e.g. a root catalog plus a nested module publishing a different
   * system).
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderPolicyCandidates: ConfigurableFileCollection

  /** `catalog.spec.json` candidates, in the same order and for the same reason. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val catalogSpecCandidates: ConfigurableFileCollection

  /**
   * `ui-builder/designs/` trees for a policy's `templates`, module first. Inputs so edits re-run
   * the task and the advertised designs can be carried; an uncarried template would be a 404 in the
   * New design chooser.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderTemplateCandidates: ConfigurableFileCollection

  /**
   * Directories `templates` paths resolve against. `@Internal`: they're project dirs; change
   * detection is via [uiBuilderTemplateCandidates]. Exists so execution needn't touch `project`.
   */
  @get:Internal abstract val uiBuilderTemplateRoots: ConfigurableFileCollection

  /**
   * Where copied template designs land, declared so cache hits restore them and Gradle removes
   * designs a policy no longer names.
   */
  @get:OutputDirectory abstract val uiBuilderTemplateDir: DirectoryProperty

  /**
   * `ui-builder.json`, or nothing without a policy. Declared like [componentsFile]; deleted when
   * there's no policy so a removed policy stops publishing.
   */
  @get:OutputFile abstract val uiBuilderFile: RegularFileProperty

  /**
   * `ui-builder.guidelines.json` candidates beside each policy candidate; only the one beside the
   * chosen policy is published (see [UiBuilderGuidelinesFile]).
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderGuidelinesCandidates: ConfigurableFileCollection

  /**
   * The catalog's guidelines copied beside [uiBuilderFile], or nothing; removed whenever no catalog
   * is written.
   */
  @get:OutputFile abstract val uiBuilderGuidelinesFile: RegularFileProperty

  /** See [PreviewDiscovery.Input.lottieRenderSubdir]; Android sets a disjoint dir. */
  @get:Input abstract val lottieRenderSubdir: Property<String>

  /** See [PreviewDiscovery.Input.svgRenderSubdir]; Android sets a disjoint dir. */
  @get:Input abstract val svgRenderSubdir: Property<String>

  /**
   * Whether the backend can draw `@ColorCatalog` sheets; `false` on desktop (#2135) marks `CATALOG`
   * captures `optional`.
   */
  @get:Input abstract val catalogRenderSupported: Property<Boolean>

  /**
   * Whether the backend honours `@AnimatedPreview(format = Apng)`; `true` on both backends
   * ([ComposePreviewTasks.registerDiscoverTask]). `false` records GIF.
   */
  @get:Input abstract val animatedPreviewApngSupported: Property<Boolean>

  /**
   * Whether Wear device-less previews are retargeted onto the Wear canvas; `false` keeps them
   * wrap-content for widget/tile assets (#2670). From `retargetWearPreviews`.
   */
  @get:Input abstract val retargetWearPreviews: Property<Boolean>

  /**
   * Extra component-library owners (packages ending in `.` or exact classes), from
   * `componentLibraryPrefixes`.
   */
  @get:Input abstract val componentLibraryPrefixes: ListProperty<String>

  /**
   * The merged `AndroidManifest.xml`: detects Wear modules and drives app-level discovery
   * (activities, ACTIVITY previews, default tour start). Absent on desktop: non-Wear, no app-level
   * previews.
   */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedManifest: RegularFileProperty

  /**
   * Tour scripts (`compose-previews/tours/<name>.json`), each an APP_TOUR preview; only with
   * [mergedManifest].
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val tourSpecFiles: ConfigurableFileCollection

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Reader for the two catalog-authored files. `ignoreUnknownKeys` because their schemas belong
   * elsewhere and grow independently.
   */
  private val lenientJson = Json { ignoreUnknownKeys = true }

  @TaskAction
  fun discover() {
    // Union with the scoped project-class dirs so classes are found whatever compiled them; overlap
    // dedupes (#1924).
    val sharedPreviewSources = previewSourceClasses.files.filter { it.exists() }
    val scopedClassDirs =
      projectClassDirs.getOrElse(emptyList()).map { it.asFile } +
        sharedPreviewSources.filter { it.isDirectory }
    val scopedClassJars =
      projectClassJars.getOrElse(emptyList()).map { it.asFile } +
        sharedPreviewSources.filter { it.isFile && it.name.lowercase().endsWith(".jar") }
    // Plain substring match for the `android.hardware.type.watch` feature; no manifest means not
    // Wear.
    val isWear =
      mergedManifest.orNull
        ?.asFile
        ?.takeIf { it.exists() }
        ?.let { it.readText().contains("android.hardware.type.watch") } ?: false
    val input =
      PreviewDiscovery.Input(
        classDirs = classDirs.files.toList() + scopedClassDirs,
        dependencyJars = dependencyJars.files.toList(),
        dependencyJarCoordinates = dependencyJarCoordinates.getOrElse(emptyMap()),
        sourceFiles = sourceFiles.files.toList(),
        activeSourceFiles = activeSourceFiles.files.toList(),
        moduleName = moduleName.get(),
        variantName = variantName.get(),
        projectDirectory = File(projectDirectory.get()),
        failOnEmpty = failOnEmpty.get(),
        resourceDirs = resourceDirs.files.toList(),
        lottieRenderSubdir = lottieRenderSubdir.getOrElse("renders"),
        svgRenderSubdir = svgRenderSubdir.getOrElse("renders"),
        projectClassJars = scopedClassJars,
        activeClassDirs = activeClassDirs.files.toList(),
        catalogRenderSupported = catalogRenderSupported.getOrElse(true),
        animatedPreviewApngSupported = animatedPreviewApngSupported.getOrElse(false),
        isWear = isWear,
        retargetWearPreviews = retargetWearPreviews.getOrElse(true),
        componentLibraryPrefixes = componentLibraryPrefixes.getOrElse(emptyList()),
        mergedManifest = mergedManifest.orNull?.asFile?.takeIf { it.exists() },
        tourSpecFiles = tourSpecFiles.files.filter { it.isFile },
      )
    when (val outcome = PreviewDiscovery.discover(input)) {
      is PreviewDiscovery.Outcome.Success -> {
        outcome.warnings.forEach { logger.warn(it) }
        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(json.encodeToString(outcome.manifest))
        // Derived from the manifest and always written; an empty list says inference found nothing.
        val componentsOut = componentsFile.get().asFile
        componentsOut.parentFile.mkdirs()
        // The builder catalog chooses each component's overload (per its policy, never deprecated),
        // so it runs first and `components.json` publishes the chosen record.
        val record = writeUiBuilderCatalog(outcome.manifest)
        componentsOut.writeText(json.encodeToString(record))
        outcome.infoMessages.forEach { logger.lifecycle(it) }
      }
      is PreviewDiscovery.Outcome.Failure -> {
        // Per-method skip reasons first, as on success; they're often the real cause of zero
        // previews.
        outcome.warnings.forEach { logger.warn(it) }
        outcome.diagnostics.forEach { logger.lifecycle(it) }
        throw GradleException(outcome.reason)
      }
    }
  }

  /**
   * The authored policy and cover sheet, resolved from one location: if the module has either file,
   * the module wins (a missing policy there means no builder catalog); only a module with neither
   * falls back to the root. Picking each independently would give a nested catalog the root's
   * policy under its own identity.
   */
  private fun authoredPair(): AuthoredPair? {
    val modulePolicy = uiBuilderPolicyCandidates.files.firstOrNull()?.takeIf { it.isFile }
    val moduleSpec = catalogSpecCandidates.files.firstOrNull()?.takeIf { it.isFile }
    if (modulePolicy != null || moduleSpec != null) {
      return modulePolicy?.let { AuthoredPair(it, moduleSpec, moduleOwns = true) }
    }
    val rootPolicy = uiBuilderPolicyCandidates.files.drop(1).firstOrNull { it.isFile }
    val rootSpec = catalogSpecCandidates.files.drop(1).firstOrNull { it.isFile }
    return rootPolicy?.let { AuthoredPair(it, rootSpec, moduleOwns = false) }
  }

  /**
   * [moduleOwns] decides which side template lookup searches first, since a root policy's templates
   * are root-relative.
   */
  private data class AuthoredPair(val policy: File, val spec: File?, val moduleOwns: Boolean)

  /**
   * Copies the guidelines beside [policy] next to `ui-builder.json`, with included rule packs
   * merged. A malformed file only costs itself and a warning here; the publish workflow fails
   * loudly.
   */
  private fun writeUiBuilderGuidelines(policy: File, catalogId: String) {
    val source = UiBuilderGuidelinesFile.besidePolicy(policy) ?: return
    val text = source.readText()
    val problems = UiBuilderGuidelinesFile.problems(text, catalogId)
    if (problems.isNotEmpty()) {
      logger.warn(
        "composePreview: ${source.path} is not published: ${problems.joinToString("; ")}."
      )
      return
    }
    // Flattened once so consumers needn't fetch included packs.
    val flat = UiBuilderGuidelinesFile.flatten(text)
    flat.problem?.let {
      logger.warn(
        "composePreview: ${source.path}'s includes are not resolved ($it); published as written."
      )
    }
    uiBuilderGuidelinesFile.get().asFile.writeText(flat.text)
  }

  /**
   * Writes `ui-builder.json` via [UiBuilderCatalogs.generate] (shared `screen/generator` code), or
   * removes a stale one.
   *
   * **Never fails the build:** a malformed policy costs the builder catalog and a warning, since
   * every render lane waits on this task. The generator's findings travel inside the file as
   * `diagnostics`.
   */
  private fun writeUiBuilderCatalog(manifest: PreviewManifest): ComponentRecordFile {
    val record = ComponentRecords.from(manifest)
    val out = uiBuilderFile.get().asFile
    // Published only beside a catalog that is written, so cleared before anything can return.
    uiBuilderGuidelinesFile.get().asFile.delete()
    // No authored pair: remove any stale catalog.
    val authored =
      authoredPair()
        ?: return run {
          UiBuilderTemplateLookup.withdraw(out, uiBuilderTemplateDir.get().asFile)
          record
        }
    val (policyFile, specFile) = authored.policy to authored.spec
    val policy = runCatching {
      lenientJson.decodeFromString<UiBuilderPolicyFile>(policyFile.readText())
    }
      .getOrElse { failure ->
        logger.warn(
          "composePreview: ${policyFile.path} could not be read " +
            "(${failure.message ?: failure::class.simpleName}); no ui-builder.json written."
        )
        UiBuilderTemplateLookup.withdraw(out, uiBuilderTemplateDir.get().asFile)
        return record
      }
    val spec = specFile?.let {
      runCatching { lenientJson.decodeFromString<CatalogCoverSheet>(it.readText()) }.getOrNull()
    }
    // A missing cover sheet still publishes; only `system` and `title` come from it.
    val cover =
      UiBuilderCatalogs.CoverSheet(
        system = spec?.system ?: policy.catalogId ?: record.module.trimStart(':'),
        title = spec?.title ?: policy.catalogId ?: record.module,
      )
    val selection =
      ComponentRecords.select(manifest, UiBuilderCatalogs.authoredNames(cover, policy))
    val catalog =
      UiBuilderCatalogs.generate(selection.record, cover, policy, selection.diagnostics)
        ?: return selection.record
    out.parentFile.mkdirs()
    out.writeText(json.encodeToString(catalog))
    writeUiBuilderGuidelines(authored.policy, cover.system)
    // Copy the advertised designs beside the catalog; `compose-preview-server ui` has no other
    // source for them.
    val declared = catalog.statusSemantics.templates
    // Parse before copying, like the bundle lane, so a truncated design isn't offered.
    val resolved =
      UiBuilderTemplateLookup.resolve(
        uiBuilderTemplateRoots.files,
        declared,
        moduleOwnsPolicy = authored.moduleOwns,
      )
    val (usable, unreadable) =
      resolved.entries.partition { (_, file) ->
        runCatching { json.parseToJsonElement(file.readText()) }.isSuccess
      }
    unreadable.forEach { (path, file) ->
      logger.warn(
        "composePreview: template design '$path' (${file.path}) is not readable JSON, so it is " +
          "not copied; the builder catalog names it and the local chooser cannot open it."
      )
    }
    val found = usable.associate { (path, file) -> path to file }
    // Emptied first so designs a policy stopped naming stop being published.
    val templateDir = uiBuilderTemplateDir.get().asFile
    if (templateDir.exists()) templateDir.deleteRecursively()
    found.forEach { (path, file) ->
      val target = File(out.parentFile, path)
      target.parentFile.mkdirs()
      file.copyTo(target, overwrite = true)
    }
    templateDir.mkdirs()
    (declared - found.keys).sorted().forEach {
      logger.warn(
        "composePreview: the builder catalog names template design '$it', which is not under " +
          "ui-builder/ in this module or the repository root; the local builder cannot open it."
      )
    }
    val unresolved = catalog.diagnostics.size
    logger.lifecycle(
      "composePreview: wrote ${out.name} for ${catalog.catalog.id} " +
        "(platform ${catalog.catalog.platform}, " +
        "${catalog.statusSemantics.components.size} component policies, " +
        "${catalog.statusSemantics.builtins.size} builtins, $unresolved diagnostic(s))"
    )
    return selection.record
  }

  /** The two `catalog.spec.json` fields a builder catalog wants; the rest is the pipeline's. */
  @kotlinx.serialization.Serializable
  private data class CatalogCoverSheet(val system: String, val title: String)
}
