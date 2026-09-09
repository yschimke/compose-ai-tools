package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentRecords
import ee.schimke.composeai.discovery.PreviewDiscovery
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
   * Class directories produced by the active compilation. [classDirs] can include compatibility
   * fallbacks for several Kotlin targets; stale classes in one of those inactive directories must
   * not hide an empty output restored for the compilation that discovery depends on.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val activeClassDirs: ConfigurableFileCollection

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
   * Source files compiled into [activeClassDirs], used only by the empty-output integrity guard.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val activeSourceFiles: ConfigurableFileCollection

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
   * `components.json` — the components those previews render (see `ComponentRecordFile`).
   *
   * A **declared** output, not a file written beside [outputFile]. This task is `@CacheableTask`,
   * and Gradle restores only declared outputs from the build cache: an undeclared write would
   * simply be missing on a cache hit while the task still reported success, and deleting the file
   * by hand would leave the task up to date so it never came back.
   */
  @get:OutputFile abstract val componentsFile: RegularFileProperty

  /**
   * `ui-builder.policy.json` candidates, most specific first: the module's own, then the repository
   * root's. The first that exists wins.
   *
   * A **file collection** rather than two optional `@InputFile`s because an `@InputFile` pointing
   * at a file that does not exist fails the build, and "no policy" is the ordinary case — every
   * module that is not a design catalog, and every design catalog that has not adopted the builder
   * contract. A collection simply does not contain what is not there.
   *
   * Two locations because both shapes exist in the wild: wear-m3-catalog keeps one
   * `catalog.spec.json` at its root for `:catalog` and a second inside `remote-catalog/` for the
   * module that publishes a different system, and the policy has to be findable beside either.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderPolicyCandidates: ConfigurableFileCollection

  /** `catalog.spec.json` candidates, in the same order and for the same reason. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val catalogSpecCandidates: ConfigurableFileCollection

  /**
   * The `ui-builder/designs/` trees a policy's `templates` paths resolve against — module first,
   * repository root second, matching how the policy and cover sheet are found.
   *
   * Declared as an input so a changed template design re-runs this task, and so the files can be
   * carried with the catalog that advertises them. A `templates` entry is a branch-relative path,
   * and the publish flow snapshots only what is written out — so a template that is not carried is
   * a 404 in the New design chooser, advertised by the catalog and absent from the branch.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderTemplateCandidates: ConfigurableFileCollection

  /**
   * The directories a `templates` path resolves against — the module's, then the repository root's.
   *
   * `@Internal` on purpose: these are the *project* directories, and snapshotting them would make
   * every file in the project an input to this task. Change detection is carried by
   * [uiBuilderTemplateCandidates], which snapshots only the `ui-builder/` tree; this property
   * exists so execution can resolve a branch-relative path without reaching for `project`, which is
   * not available under the configuration cache.
   */
  @get:Internal abstract val uiBuilderTemplateRoots: ConfigurableFileCollection

  /**
   * Where the copied template designs land, declared so Gradle owns them.
   *
   * Without this the task's declared outputs were `previews.json`, `components.json` and
   * `ui-builder.json`, so a cache hit in a clean checkout restored the catalog and none of the
   * designs it advertises — the local New design chooser would then list templates that are not
   * there. Declaring the directory also lets Gradle remove designs a policy has stopped naming,
   * which a copy loop alone never does.
   */
  @get:OutputDirectory abstract val uiBuilderTemplateDir: DirectoryProperty

  /**
   * The template designs a policy names, resolved to real files.
   *
   * Paths a policy cannot supply a file for are simply absent from the map; the caller reports them
   * rather than failing, because a catalog naming a template it does not ship is a mistake to tell
   * somebody about and not a reason to publish no catalog.
   */
  /** The one directory a `templates` path may live under, and the tree declared as an input. */
  private val UI_BUILDER_DIR = "ui-builder"

  private fun templateFiles(paths: List<String>): Map<String, File> {
    if (paths.isEmpty()) return emptyMap()
    val roots = uiBuilderTemplateRoots.files.filter { it.isDirectory }
    return paths
      .distinct()
      // Only under `ui-builder/`, which is exactly the tree declared as this task's input. A
      // path outside it resolves to a real file Gradle is not watching, so editing that file
      // would invalidate nothing and an up-to-date or cached build would keep publishing stale
      // bytes — the input declaration and the lookup have to describe the same set or neither
      // means anything. Anything else is reported by the caller as unresolvable rather than read
      // from somewhere untracked.
      .filter { it == UI_BUILDER_DIR || it.startsWith("$UI_BUILDER_DIR/") }
      .mapNotNull { path ->
        roots
          .firstNotNullOfOrNull { root -> File(root, path).takeIf { it.isFile } }
          ?.let { path to it }
      }
      .toMap()
  }

  /**
   * `ui-builder.json` — the builder catalog this module publishes, or nothing when it authors no
   * policy.
   *
   * Declared for the reason [componentsFile] is: this task is `@CacheableTask` and Gradle restores
   * only declared outputs, so an undeclared write would be missing on a cache hit while the task
   * still reported success. A module with no policy has the file deleted rather than left stale —
   * removing `ui-builder.policy.json` has to remove the catalog it produced, or the build keeps
   * publishing a description nobody authored any more.
   */
  @get:OutputFile abstract val uiBuilderFile: RegularFileProperty

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

  /**
   * The reader for the two files the catalog repository authors, as opposed to the writer for the
   * files this task produces.
   *
   * `ignoreUnknownKeys` because both are contracts this task does not own: `catalog.spec.json`
   * belongs to the design-artifacts pipeline and has a large schema, and `ui-builder.policy.json`
   * will grow fields a plugin released today has never heard of. Refusing either over a key this
   * task never reads would make every additive change to those schemas a plugin release.
   */
  private val lenientJson = Json { ignoreUnknownKeys = true }

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
        // The components those previews render, rather than the renders themselves. Derived wholly
        // from the manifest, so it never disagrees with it, and written unconditionally — an empty
        // component list is a fact worth publishing (it says inference found nothing), not a
        // reason to omit the file.
        val componentsOut = componentsFile.get().asFile
        componentsOut.parentFile.mkdirs()
        componentsOut.writeText(json.encodeToString(ComponentRecords.from(outcome.manifest)))
        writeUiBuilderCatalog(ComponentRecords.from(outcome.manifest))
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

  /**
   * The authored pair — policy and cover sheet — resolved from ONE location.
   *
   * Both are looked for in the module directory first and the repository root second, but they are
   * chosen *together*: a multi-catalog repository routinely has a root policy for its main catalog
   * and a nested module with its own `catalog.spec.json` and deliberately no policy of its own.
   * Picking each file independently would hand that module the root's platform, frame, builtins and
   * templates under its own cover sheet's identity — a hybrid catalog describing a module nobody
   * wrote a policy for, which is worse than the nothing it should publish.
   *
   * So: if the module has either file, the module's location wins outright and a missing policy
   * there means this module publishes no builder catalog. Only a module with neither falls back to
   * the root.
   */
  private fun authoredPair(): Pair<File, File?>? {
    val modulePolicy = uiBuilderPolicyCandidates.files.firstOrNull()?.takeIf { it.isFile }
    val moduleSpec = catalogSpecCandidates.files.firstOrNull()?.takeIf { it.isFile }
    if (modulePolicy != null || moduleSpec != null) {
      return modulePolicy?.let { it to moduleSpec }
    }
    val rootPolicy = uiBuilderPolicyCandidates.files.drop(1).firstOrNull { it.isFile }
    val rootSpec = catalogSpecCandidates.files.drop(1).firstOrNull { it.isFile }
    return rootPolicy?.let { it to rootSpec }
  }

  /**
   * Write `ui-builder.json` beside the record, or remove a stale one.
   *
   * The generator is [UiBuilderCatalogs.generate], which lives in the shared `screen/generator`
   * source so the same code runs here, in a test, and in the browser. Nothing about it is
   * Gradle-shaped; this method's whole job is finding the two authored files and reporting what
   * happened.
   *
   * **Never fails the build.** A malformed policy costs the builder catalog and a warning, not the
   * discovery run: `previews.json` and `components.json` are what every other consumer of this task
   * is waiting for, and a render lane stopped by a typo in a file it does not read would be a poor
   * trade. The generator's own findings travel *inside* the published file as `diagnostics`, where
   * a person who was not watching this build can still read them.
   */
  private fun writeUiBuilderCatalog(record: ComponentRecordFile) {
    val out = uiBuilderFile.get().asFile
    // No authored pair — this module publishes no builder catalog. Removing the policy has to
    // remove the catalog it produced: a stale file would keep being published and would describe a
    // catalog nobody authors any more.
    val (policyFile, specFile) = authoredPair() ?: return run { if (out.exists()) out.delete() }
    val policy = runCatching {
      lenientJson.decodeFromString<UiBuilderPolicyFile>(policyFile.readText())
    }
      .getOrElse { failure ->
        logger.warn(
          "composePreview: ${policyFile.path} could not be read " +
            "(${failure.message ?: failure::class.simpleName}); no ui-builder.json written."
        )
        if (out.exists()) out.delete()
        return
      }
    val spec = specFile?.let {
      runCatching { lenientJson.decodeFromString<CatalogCoverSheet>(it.readText()) }.getOrNull()
    }
    // A policy with no readable cover sheet still publishes: `system` and `title` are the only two
    // fields wanted from it, the policy can name the id itself, and a catalog with no title is
    // worth more than no catalog at all.
    val cover =
      UiBuilderCatalogs.CoverSheet(
        system = spec?.system ?: policy.catalogId ?: record.module.trimStart(':'),
        title = spec?.title ?: policy.catalogId ?: record.module,
      )
    val catalog = UiBuilderCatalogs.generate(record, cover, policy) ?: return
    out.parentFile.mkdirs()
    out.writeText(json.encodeToString(catalog))
    // The designs this catalog advertises, copied beside it. `compose-preview-server ui` reads this
    // directory and has no delivery branch to fall back on, so a template that is not here is a
    // template the local builder cannot open — the same 404 the branch lane would have, arriving
    // for the consumer this contract most wanted to serve.
    val declared = catalog.statusSemantics.templates
    val found = templateFiles(declared)
    // Emptied first: a design a policy has stopped naming must stop being published, and a stale
    // one left behind is advertised by nothing and opened by accident.
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
  }

  /** The two `catalog.spec.json` fields a builder catalog wants; the rest is the pipeline's. */
  @kotlinx.serialization.Serializable
  private data class CatalogCoverSheet(val system: String, val title: String)
}
