package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import io.github.classgraph.ClassGraph
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Packs a portable preview bundle: a PNG+ZIP polyglot with the selected previews' metadata, the
 * minimal reachable consumer classes, the Maven coordinates the player resolves at open time, and a
 * minimization report. See [PreviewBundleFormat].
 *
 * # Dependency strategy
 *
 * Only module bytecode is inlined (`classes/app.jar`). By default (`resolution = "coordinates"`)
 * Maven deps are recorded as `ClasspathEntry.Maven`, keeping bundles small; project deps without a
 * coordinate are inlined as `ClasspathEntry.Project`. With [embedDeps] kept deps are carried in
 * `libs/` as `ClasspathEntry.Embedded` for fully offline rendering.
 *
 * # Closure walk
 *
 * BFS over ClassGraph's class dependency map from each selected preview's enclosing class. Module
 * classes are repacked per class; third-party deps appear in `report.json` with reachability
 * counts, and only reachable ones are recorded.
 */
@org.gradle.api.tasks.CacheableTask
abstract class BundlePreviewTask : DefaultTask() {

  /**
   * Discovery's `previews.json`: source of the closure entry points and of the filtered copy in the
   * bundle.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val previewsJson: RegularFileProperty

  /**
   * Module class dirs, minimized per class into `classes/app.jar`. Resources come via
   * [moduleResourcesDir].
   */
  @get:Classpath abstract val moduleClassDirs: ConfigurableFileCollection

  /**
   * The module's classes as directories from AGP's scoped `PROJECT` `CLASSES` artifact, in addition
   * to [moduleClassDirs], matching what discovery consumes (#1924). Without it, AGP 9 built-in
   * Kotlin previews would be in `previews.json` but missing from `classes/app.jar` (#1926). Overlap
   * dedupes by class path. Empty off Android.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassDirs: ListProperty<Directory>

  /**
   * The jar half of the same scoped artifact, packed as module classes (not dependency
   * coordinates), mirroring [DiscoverPreviewsTask.projectClassJars]. Usually empty. Empty off
   * Android.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassJars: ListProperty<RegularFile>

  /**
   * Processed resources dir, bundled wholesale: small, and string-id references make them hard to
   * prune.
   */
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourcesDir: DirectoryProperty

  /**
   * Extra resource roots for resolving asset IR (`kind=LOTTIE` / `kind=SVG`) after
   * [moduleResourcesDir]. Android wires its source resource roots, since AGP doesn't stage java
   * resources into `build/resources/main`. Empty on desktop.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourceRoots: ConfigurableFileCollection

  /**
   * Third-party runtime jars for the closure walk and coordinate lookup; not inlined except
   * project-dep fallbacks.
   */
  @get:Classpath abstract val dependencyJars: ConfigurableFileCollection

  /**
   * `dependencyJar absolute path → coordinate`:
   * - `"maven:<group>:<artifact>:<version>:<jar|aar>"` for Maven deps.
   * - `"project:<gradle path>"` for project deps (inlined).
   *
   * Jars missing from the map (e.g. boot-classpath jars) are inlined under a synthetic `:anon`
   * path.
   */
  @get:Input abstract val dependencyCoordinates: MapProperty<String, String>

  /**
   * (v6 Android) AGP's unit-test config dir (`test_config.properties`), read only for protolayout
   * IR bundles to locate the merged resource APK + manifest. Empty means no Android resource
   * carriage. See [resolveAndroidResources].
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val androidUnitTestConfig: ConfigurableFileCollection

  /**
   * (v6 Android) The unit-test runtime classpath (lenient view), scanned only for protolayout IR to
   * collect library R classes: with non-transitive R, the tile renderer's `R$style` is only in the
   * merged unit-test R.jar, which the attribute-filtered [dependencyJars] drops. Repacked as
   * `android/r-classes.jar`.
   */
  @get:Classpath abstract val androidUnitTestRuntimeClasspath: ConfigurableFileCollection

  /**
   * (v6 Android) Base for the module-relative paths in `test_config.properties`, as Robolectric
   * resolves them. `@Internal`: the carried bytes are tracked via [androidUnitTestConfig] /
   * [androidUnitTestRuntimeClasspath].
   */
  @get:Internal abstract val moduleProjectDir: DirectoryProperty

  /**
   * The project's directory relative to the repository root (`bundle/format` for `:bundle-format`;
   * empty for root). An `@Input` because it's carried content ([BundleManifest.moduleDirectory]),
   * unlike [moduleProjectDir]. Recorded because [modulePath] is a logical name.
   */
  @get:Input @get:Optional abstract val moduleDirectory: Property<String>

  /**
   * (v6 Android) The configured build directory, where [MergedResourceOwnership] finds AGP's blame
   * files. From `project.layout.buildDirectory` because consumers relocate it; a wrong guess
   * silently prunes sibling drawables. `@Internal`, like [moduleProjectDir].
   */
  @get:Internal abstract val moduleBuildDir: DirectoryProperty

  /**
   * Renders from `composePreviewRender`: the cover and every `previews/<id>.png` come from here;
   * missing falls back to a stub gray cover. `@Internal` because the dir may not exist (an
   * `@InputDirectory` would fail); contents are tracked by [renderFiles].
   */
  @get:Internal abstract val rendersDir: DirectoryProperty

  /**
   * Render products under [rendersDir], tracked so caching can't keep a render-less bundle after
   * renders appear. `@InputFiles` so an absent dir snapshots as empty.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val renderFiles: ConfigurableFileCollection

  /**
   * Catalog-token sidecars under `data/catalog-tokens/`, outside `renders/`, tracked for the same
   * reason as [renderFiles].
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val catalogTokenFiles: ConfigurableFileCollection

  /**
   * `build/compose-previews/guidelines.json` from `compose-preview guidelines`, carried as
   * [GUIDELINE_RESULTS_ENTRY] so a host can serve verdicts beside renders. Overlay PNGs aren't
   * carried.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val guidelineResultsFiles: ConfigurableFileCollection

  /** Preview ids to include; first is the cover. Empty means all. */
  @get:Input abstract val previewIds: ListProperty<String>

  /** Gradle module path, recorded into the bundle for forensics. */
  @get:Input abstract val modulePath: Property<String>

  /** Producer-version string for diagnostics, e.g. "compose-preview $BUNDLE_VERSION". */
  @get:Input abstract val producedBy: Property<String>

  /** Backend identifier. v1 = "desktop". */
  @get:Input abstract val backend: Property<String>

  /**
   * Embed-deps mode (`-PbundleEmbedDeps=true`, v3 `resolution = "embedded"`): kept third-party jars
   * go in `libs/` as [ClasspathEntry.Embedded] instead of coordinates. Project deps are inlined
   * regardless. Default false.
   */
  @get:Input @get:Optional abstract val embedDeps: Property<Boolean>

  /**
   * Repository URLs beyond Central and Google, recorded as [BundleManifest.repositories] so players
   * can re-resolve coordinates those don't serve. Empty for most modules.
   */
  @get:Input @get:Optional abstract val extraMavenRepositories: ListProperty<String>

  /**
   * Include-data-extensions mode (`-PbundleIncludeDataExtensions=true`, v7): pack reports named by
   * `dataExtensionReports`, plus [CONVENTIONAL_DATA_EXTENSION_REPORTS] fallbacks, under
   * `extensions/<id>.json`, sliced to the cover preview. Default false.
   */
  @get:Input @get:Optional abstract val includeDataExtensions: Property<Boolean>

  /**
   * Extension report files, tracked so content changes re-pack. Paths are resolved from the
   * manifest at pack time; only read with [includeDataExtensions].
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val dataExtensionFiles: ConfigurableFileCollection

  /** Output `.png` polyglot file. */
  /**
   * `ui-builder.policy.json` candidates, module first then repository root; a file collection
   * because "no policy" is normal.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderPolicyCandidates: ConfigurableFileCollection

  /**
   * `ui-builder.guidelines.json` candidates; see
   * [DiscoverPreviewsTask.uiBuilderGuidelinesCandidates].
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderGuidelinesCandidates: ConfigurableFileCollection

  /** `catalog.spec.json` candidates, in the same order and for the same reason. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val catalogSpecCandidates: ConfigurableFileCollection

  /**
   * `ui-builder/designs/` trees for a policy's `templates`, module first. The bundle must carry the
   * designs it advertises, since `catalog-ui-builder.mjs` publishes only from bundle entries.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val uiBuilderTemplateCandidates: ConfigurableFileCollection

  /**
   * Directories `templates` paths resolve against. `@Internal`: these are project dirs; change
   * detection comes from [uiBuilderTemplateCandidates]. Exists so execution needn't touch
   * `project`.
   */
  @get:Internal abstract val uiBuilderTemplateRoots: ConfigurableFileCollection

  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun pack() {
    val manifestFile = previewsJson.get().asFile
    val manifest = JSON.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
    val selected = resolveSelection(manifest, previewIds.get())
    // Raw ids address renderer output on disk; bundle ids are used for everything inside the
    // bundle. Computed once over the selection so it's collision-free (see [assignBundleEntryIds]).
    val bundleIds = assignBundleEntryIds(selected.map { it.id })
    val coverIdRaw = selected.first().id
    val coverId = bundleIds.getValue(coverIdRaw)

    // IR-backed previews replay without their bytecode, but still need their third-party runtime.
    // So two closures from one scan:
    // - the DEP closure seeds from every selected preview, keeping coordinates for IR previews;
    // - the PACK closure seeds only from non-IR previews, so only their classes enter
    //   `classes/app.jar`.
    // Without IR previews the two are identical.
    val irByPreview: Map<String, ResolvedIr> =
      selected.mapNotNull { p -> resolvePreviewIr(p)?.let { p.id to it } }.toMap()

    // IR replays through a player its bytecode never references (`TileRenderer`,
    // `RemoteDocumentPlayer`), so seed the dep closure from each format's player entry points;
    // otherwise those libs are pruned and replay fails with `NoClassDefFoundError`. Absent entry
    // FQNs seed nothing.
    val replayEntrySeeds = buildSet {
      if (irByPreview.values.any { it.format == IR_FORMAT_PROTOLAYOUT })
        addAll(PROTOLAYOUT_REPLAY_ENTRY_FQNS)
      if (irByPreview.values.any { it.format == IR_FORMAT_REMOTECOMPOSE })
        addAll(REMOTECOMPOSE_REPLAY_ENTRY_FQNS)
    }

    val depSeedFqns = selected.map { it.className }.toSet() + replayEntrySeeds
    val packSeedFqns = selected.filter { it.id !in irByPreview }.map { it.className }.toSet()

    // Union `moduleClassDirs` with AGP's scoped project classes so the packed set matches
    // discovery's (#1924, #1926). Scoped jars are module classes, not dependency coordinates.
    val scopedClassDirs = projectClassDirs.getOrElse(emptyList()).map { it.asFile }
    val scopedClassJars =
      projectClassJars
        .getOrElse(emptyList())
        .map { it.asFile }
        .filter { it.isFile && it.name.endsWith(".jar") }
    val classDirsList =
      (moduleClassDirs.files + scopedClassDirs).filter { it.exists() && it.isDirectory }.distinct()
    val jarsList = dependencyJars.files.filter { it.isFile && it.name.endsWith(".jar") }
    // Scoped jars join the scan but stay out of `jarsList`, so they're never treated as third-party
    // coordinates.
    val scanPaths = (classDirsList + jarsList + scopedClassJars).map { it.absolutePath }

    val closure = closureWalk(scanPaths, depSeed = depSeedFqns, packSeed = packSeedFqns)

    val moduleClassFqns =
      collectClassFqns(classDirsList) + collectClassFqnsFromJars(scopedClassJars)
    val reachableModuleClasses = moduleClassFqns intersect closure.packReachable
    val keptModuleClassFiles =
      packModuleClasses(classDirsList, reachableModuleClasses) +
        packModuleClassesFromJars(scopedClassJars, reachableModuleClasses)
    // Pack runtime resources so the bundle can be re-rendered live (e.g. a theme loading fonts).
    // [moduleResourcesDir] can snapshot as null on a clean config-cached build;
    // [moduleResourceRoots] resolves at execution. Pack both, deduped.
    val appJarBytes =
      buildJar(
        keptModuleClassFiles,
        (listOfNotNull(moduleResourcesDir.orNull?.asFile) + moduleResourceRoots.files).distinct(),
      )

    val coordMap = dependencyCoordinates.getOrElse(emptyMap())
    val depDecisions = buildDepDecisions(jarsList, closure.perElement, coordMap)
    val classpath = assembleClasspath(jarsList, depDecisions, embed = embedDeps.getOrElse(false))
    val classpathEntries = classpath.entries
    val inlinedJars = classpath.inlinedJars
    failOnAndroidClasspathInDesktopBundle(backend.get(), depDecisions)

    val report =
      MinimizationReport(
        entryClassFqns = depSeedFqns.sorted(),
        reachableClassCount = closure.depReachable.size,
        totalScannedClassCount = closure.totalScanned,
        moduleClasses =
          ModuleClassesStats(
            totalClasses = moduleClassFqns.size,
            reachableClasses = reachableModuleClasses.size,
            packedBytes = appJarBytes.size.toLong(),
          ),
        dependencies = depDecisions,
      )

    // IR artefacts go under `ir/<id>.<ext>` with a manifest entry each; protolayout's resources
    // proto sits beside it.
    val irEntries = mutableListOf<BundleIr>()
    val irZipFiles = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      val ir = irByPreview[preview.id] ?: continue
      val bundleId = bundleIds.getValue(preview.id)
      val irPath = "$BUNDLE_IR_DIR/$bundleId.${ir.ext}"
      irZipFiles[irPath] = ir.bytes
      val resourcesPath =
        ir.resourcesBytes?.let { rb ->
          val rp = "$BUNDLE_IR_DIR/$bundleId.${ir.resourcesExt}"
          irZipFiles[rp] = rb
          rp
        }
      irEntries +=
        BundleIr(
          previewId = bundleId,
          format = ir.format,
          path = irPath,
          resourcesPath = resourcesPath,
        )
    }

    // Android resource carriage under `android/` for any Android bundle: both tile IR replay and
    // `stringResource(R.string.…)` need the app's resource table and R classes on a detached
    // daemon. Null without a prior render or binary resources.
    val androidResources =
      if (backend.get() == "android") resolveAndroidResources(irZipFiles) else null

    // v7 data-extension carriage (when enabled): reports from `dataExtensionReports` plus
    // conventional fallbacks ([CONVENTIONAL_DATA_EXTENSION_REPORTS]), since the standard a11y flow
    // writes `accessibility.json` without stamping the manifest. Each is sliced to the cover
    // preview ([scopeReportToCoverPreview]). A missing named report warns and skips; fallbacks only
    // count if present. Sorted by id for a reproducible zip.
    val dataExtensionEntries = mutableListOf<BundleDataExtension>()
    val dataExtensionZipFiles = LinkedHashMap<String, ByteArray>()
    if (includeDataExtensions.getOrElse(false)) {
      val reportBaseDir = manifestFile.parentFile
      val effectiveReports = LinkedHashMap<String, String>()
      effectiveReports.putAll(manifest.dataExtensionReports)
      for ((id, conventionalName) in CONVENTIONAL_DATA_EXTENSION_REPORTS) {
        if (id !in effectiveReports && File(reportBaseDir, conventionalName).isFile) {
          effectiveReports[id] = conventionalName
        }
      }
      for ((id, relPath) in effectiveReports.toSortedMap()) {
        val src = File(reportBaseDir, relPath)
        if (!src.isFile) {
          logger.warn(
            "composePreviewBundle: data-extension '$id' report '$relPath' not found at " +
              "${src.path} — skipping (the bundle omits this extension's data)."
          )
          continue
        }
        val zipPath = "$BUNDLE_EXTENSIONS_DIR/$id.json"
        dataExtensionZipFiles[zipPath] = scopeReportToCoverPreview(src.readBytes(), coverIdRaw)
        dataExtensionEntries += BundleDataExtension(extensionId = id, path = zipPath)
      }
    }

    val bundle =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = backend.get(),
        previewIds = selected.map { bundleIds.getValue(it.id) },
        rawPreviewIds = selected.map { it.id },
        coverPreviewId = coverId,
        classpath = classpathEntries,
        modulePath = modulePath.get(),
        // Slash-separated regardless of host OS.
        moduleDirectory = moduleDirectory.getOrElse("").replace('\\', '/'),
        producedBy = producedBy.get(),
        producer = PRODUCER_GRADLE,
        resolution = classpath.resolution,
        intermediateRepresentations = irEntries,
        androidResources = androidResources,
        dataExtensions = dataExtensionEntries,
        // Only coordinate packs need repositories.
        repositories =
          if (classpathEntries.any { it is ClasspathEntry.Maven })
            extraMavenRepositories.getOrElse(emptyList())
          else emptyList(),
      )

    // One PNG per selected preview in `previews/<id>.png`; previews without a render are omitted.
    val previewPngs = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewPng(preview)?.let { previewPngs[bundleIds.getValue(preview.id)] = it }
    }

    // Motion captures at `previews/<id>[_interaction|_anim].<ext>`, in the stills' id space so
    // downstream can join them by name (see [resolvePreviewMotion]; #3922).
    val motionFiles = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      for ((path, bytes) in resolvePreviewMotion(preview, bundleIds.getValue(preview.id))) {
        val previous = motionFiles.put(path, bytes)
        check(previous == null || previous.contentEquals(bytes)) {
          "composePreviewBundle: two motion captures resolve to '$path' with different bytes"
        }
      }
    }

    // (v8) Knob sidecars (`renders/<stem>.overrides.json`), packed verbatim as
    // `previews/<id>.overrides.json`.
    val overrideFiles = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      val bundleId = bundleIds.getValue(preview.id)
      overrideFiles.putAll(spatialBundleEntries(rendersDir.orNull?.asFile, preview.id, bundleId))
      resolvePreviewRenderError(preview)?.let {
        overrideFiles["$BUNDLE_PREVIEWS_DIR/$bundleId.$BUNDLE_RENDER_ERROR_SIDECAR_EXT"] = it
      }
      resolvePreviewOverrides(preview)?.let {
        overrideFiles["$BUNDLE_PREVIEWS_DIR/$bundleId.$BUNDLE_OVERRIDES_SIDECAR_EXT"] = it
      }
      // Remote Compose knob sidecars, packed as `previews/<id>.remotecompose.json` through the same
      // verbatim map.
      resolvePreviewRemoteCompose(preview)?.let {
        overrideFiles["$BUNDLE_PREVIEWS_DIR/$bundleId.$BUNDLE_REMOTECOMPOSE_SIDECAR_EXT"] = it
      }
    }

    // Catalog-token sidecars (#2167, theme tables per #2179), packed as
    // `previews/<id>.catalog.json` for detached importers. Only CATALOG / THEME_CATALOG sheets have
    // one.
    val catalogTokenEntries = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewCatalogTokens(preview)?.let {
        catalogTokenEntries[
          "$BUNDLE_PREVIEWS_DIR/${bundleIds.getValue(preview.id)}.$BUNDLE_CATALOG_TOKENS_SIDECAR_EXT"] =
          it
      }
    }

    // The bundled `previews.json` uses bundle ids too, matching entry names and [BundleManifest];
    // producer-side paths are left as-is.
    val bundlePreviews = selected.map { it.copy(id = bundleIds.getValue(it.id)) }
    val filteredManifest =
      if (includeDataExtensions.getOrElse(false)) {
        // When carrying extension data, point `dataExtensionReports` at the in-bundle
        // `extensions/<id>.json` paths (dropping skipped ones) so it agrees with
        // [BundleManifest.dataExtensions]. Untouched otherwise.
        manifest.copy(
          previews = bundlePreviews,
          dataExtensionReports = dataExtensionEntries.associate { it.extensionId to it.path },
        )
      } else {
        manifest.copy(previews = bundlePreviews)
      }
    // Generated before the zip so the advertised designs can be carried. Each component's overload
    // is chosen against its builder policy (never a deprecated one), and both artifacts publish
    // that choice.
    val overloadNames = overloadNames(manifest.module)
    val fullSelection =
      overloadNames?.let { ComponentRecords.select(manifest, it) }
        ?: ComponentRecords.Selection(ComponentRecords.from(manifest))
    val fullRecord = fullSelection.record
    // One carried record for both artifacts. Policy is component-wide, declared by whichever
    // preview carries the annotation, so `builder` is merged from the full record — otherwise
    // selecting a different preview silently reverts to defaults. Everything else stays the
    // filtered view, so bindings only name previews this bundle contains.
    //
    // The merged policy's `declaredBy` / `conflicting` preview ids are remapped through `bundleIds`
    // for the same invariant; unselected previews are dropped rather than guessed.
    val carriedRecord =
      (overloadNames?.let { ComponentRecords.select(filteredManifest, it).record }
          ?: ComponentRecords.from(filteredManifest))
        .let { carried ->
          val policyByComponent =
            fullRecord.components.associate {
              it.canonicalId to remapPolicyPreviewIds(it.builder, bundleIds)
            }
          carried
            .newBuilder()
            .also { b3 ->
              b3.components =
                carried.components.map {
                  it
                    .newBuilder()
                    .also { b -> b.builder = policyByComponent[it.canonicalId] ?: it.builder }
                    .build()
                }
            }
            .build()
        }
    val uiBuilderJson = uiBuilderJsonFor(fullRecord, carriedRecord, fullSelection.diagnostics)
    val zipBytes =
      buildZip(
        bundleJson = JSON.encodeToString(BundleManifest.serializer(), bundle),
        previewsJson = JSON.encodeToString(PreviewManifest.serializer(), filteredManifest),
        // From the filtered manifest, so every binding's previewId is in the bundled manifest by
        // construction.
        componentsJson = JSON.encodeToString(ComponentRecordFile.serializer(), carriedRecord),
        // Generated here from both records: the full one supplies policy declarations, the filtered
        // one decides which components are carried.
        uiBuilderJson = uiBuilderJson,
        uiBuilderTemplates = uiBuilderTemplatesFor(uiBuilderJson),
        appJar = appJarBytes,
        inlinedProjectJars = inlinedJars,
        report = JSON.encodeToString(MinimizationReport.serializer(), report),
        previewPngs = previewPngs,
        motionFiles = motionFiles,
        irFiles = irZipFiles,
        dataExtensionFiles = dataExtensionZipFiles,
        overrideFiles = overrideFiles,
        catalogTokenFiles = catalogTokenEntries,
        guidelineResults = guidelineResultsBytes(bundleIds),
      )

    // The cover reuses its baked PNG so the front image and `previews/<coverId>.png` are identical;
    // else a stub.
    val coverPng = previewPngs[coverId] ?: STUB_GRAY_PNG
    val outFile = output.get().asFile
    writePngZipPolyglot(coverPng, zipBytes, outFile)

    val mavenKept = classpathEntries.count { it is ClasspathEntry.Maven }
    val embeddedKept = classpathEntries.count { it is ClasspathEntry.Embedded }
    val projectInlined = classpathEntries.count { it is ClasspathEntry.Project }
    val depsDropped = depDecisions.count { !it.kept }
    logger.lifecycle(
      "composePreviewBundle — wrote ${outFile.name} (${outFile.length()} bytes)\n" +
        "  resolution:           ${classpath.resolution}\n" +
        "  previews baked:       ${previewPngs.size} / ${selected.size} (cover=$coverId)\n" +
        "  motion captures:      ${motionFiles.size}\n" +
        "  IR-backed previews:   ${irEntries.size} (replayed from ir/, classes dropped)\n" +
        "  data extensions:      ${dataExtensionEntries.size} (carried under extensions/)\n" +
        "  entry classes:        ${report.entryClassFqns.size}\n" +
        "  reachable classes:    ${report.reachableClassCount} / ${report.totalScannedClassCount}\n" +
        "  module classes kept:  ${report.moduleClasses.reachableClasses} / ${report.moduleClasses.totalClasses}\n" +
        "  Maven deps listed:    $mavenKept\n" +
        "  Embedded deps:        $embeddedKept (carried in libs/)\n" +
        "  Project deps inlined: $projectInlined\n" +
        "  deps dropped:         $depsDropped (no reachable classes)"
    )

    // Snapshot coordinates age out of their repository (androidx.dev keeps builds for weeks),
    // making the bundle unrenderable later; warn and name the escape hatch.
    val snapshotCoords =
      classpathEntries.filterIsInstance<ClasspathEntry.Maven>().filter {
        it.version.endsWith("-SNAPSHOT")
      }
    if (snapshotCoords.isNotEmpty()) {
      logger.warn(
        "composePreviewBundle: ${snapshotCoords.size} classpath coordinate(s) are -SNAPSHOT " +
          "(${snapshotCoords.take(3).joinToString { "${it.group}:${it.artifact}" }}" +
          "${if (snapshotCoords.size > 3) ", …" else ""}). The bundle records the repositories " +
          "they resolve from, but a snapshot publication is perishable — pack with --embed-deps " +
          "if this bundle has to keep rendering after the snapshot build ages out."
      )
    }

    // Warn past a soft size threshold so embedding stays deliberate (coordinate packs are ~100 KB).
    val sizeBytes = outFile.length()
    if (sizeBytes > EMBED_SIZE_WARN_BYTES && embeddedKept > 0) {
      logger.warn(
        "composePreviewBundle: ${outFile.name} is ${sizeBytes / 1_000_000}MB with $embeddedKept " +
          "embedded dep(s). Embedding is an offline fallback — prefer the default detached " +
          "`coordinates` pack (drop `--embed-deps` / `-PbundleEmbedDeps`) so the bundle stays small " +
          "and resolves its deps at open time."
      )
    }
  }

  private fun resolveSelection(manifest: PreviewManifest, ids: List<String>): List<PreviewInfo> {
    if (ids.isEmpty()) {
      if (manifest.previews.isEmpty()) {
        throw GradleException(
          "composePreviewBundle: previews.json is empty — nothing to bundle. Run composePreviewDiscover first."
        )
      }
      return manifest.previews
    }
    val byId = manifest.previews.associateBy { it.id }
    val resolved = ids.map { id ->
      byId[id]
        ?: throw GradleException(
          "composePreviewBundle: preview id not found: $id\nAvailable: ${byId.keys.sorted().joinToString()}"
        )
    }
    return resolved
  }

  /**
   * PNG bytes for [preview]'s primary capture, or null when not rendered. Used for the cover and
   * `previews/<id>.png`. Only PNG bytes are returned: [extractZipBytes] rejects other leading
   * signatures, so a GIF primary capture falls through to the sibling search, then the stub.
   */
  private fun resolvePreviewPng(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    // `renderOutput` is relative to the compose-previews root (covering `svg-renders/` /
    // `lottie-renders/`), as the renderer resolves it.
    val previewsRoot = rendersRoot.parentFile ?: rendersRoot
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val primary = File(previewsRoot, rel)
    val subdir = primary.parentFile ?: rendersRoot
    val name = primary.name
    val base = name.substringBeforeLast('.')

    // Only PNG primaries are read directly; the sibling search is PNG-filtered.
    if (name.endsWith(".png") && primary.isFile && primary.length() > 0) return primary.readBytes()

    // Parameterised / multi-variant previews fan out into siblings in the same subdir; use the
    // first as a representative cover.
    if (!subdir.isDirectory) return null
    return subdir
      .listFiles { f ->
        f.isFile &&
          f.length() > 0 &&
          f.name.endsWith(".png") &&
          (f.name.startsWith("${base}_") || f.name.startsWith("$base--"))
      }
      ?.minByOrNull { it.name }
      ?.readBytes()
  }

  /**
   * Motion capture bytes for [preview], keyed `previews/<bundleId>[_interaction|_anim].<ext>`.
   *
   * Named from the preview id like the still: renders are `<readable>-<digest>` on disk, and
   * downstream (`catalog-motion.mjs`, `catalog-motion-publish.mjs`) joins capture and still by
   * name, so leaf names left captures themeless and misnamed. The structural suffix is carried
   * through; see [motionBundleEntryPath].
   */
  private fun resolvePreviewMotion(
    preview: PreviewInfo,
    bundleId: String,
  ): Map<String, ByteArray> {
    val rendersRoot = rendersDir.orNull?.asFile ?: return emptyMap()
    val previewsRoot = rendersRoot.parentFile ?: rendersRoot
    val files = LinkedHashMap<String, ByteArray>()
    for (capture in preview.captures) {
      if (capture.interaction == null && capture.animation == null) continue
      val rel = capture.renderOutput.takeIf { it.isNotEmpty() } ?: continue
      val source = File(previewsRoot, rel)
      val path = motionBundleEntryPath(bundleId, source.name) ?: continue
      if (!source.isFile || source.length() <= 0) continue
      files[path] = source.readBytes()
    }
    return files
  }

  /**
   * The structured failure for [preview]'s primary capture, if any; first parameterised sidecar
   * wins, as in [resolvePreviewPng]. Bytes stay verbatim.
   */
  private fun resolvePreviewRenderError(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val previewsRoot = rendersRoot.parentFile ?: rendersRoot
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val primary = File(previewsRoot, "$rel.error.json")
    if (primary.isFile && primary.length() > 0) return primary.readBytes()

    val output = File(previewsRoot, rel)
    val dir = output.parentFile ?: rendersRoot
    val base = output.name.substringBeforeLast('.')
    if (!dir.isDirectory) return null
    return dir
      .listFiles { f ->
        f.isFile &&
          f.length() > 0 &&
          f.name.endsWith(".error.json") &&
          (f.name.startsWith("${base}_") || f.name.startsWith("$base--"))
      }
      ?.minByOrNull { it.name }
      ?.readBytes()
  }

  /** A captured intermediate representation resolved off disk for one preview. */
  private data class ResolvedIr(
    val format: String,
    val ext: String,
    val bytes: ByteArray,
    val resourcesExt: String? = null,
    val resourcesBytes: ByteArray? = null,
  )

  /**
   * The IR sidecar written beside [preview]'s render: `<stem>.rc` (Remote Compose) or
   * `<stem>.tilelayout` (+ `<stem>.tileresources`). `null` for ordinary previews. Includes
   * [resolvePreviewPng]'s `@PreviewParameter` sibling search, packing the IR of the same
   * representative sibling.
   */
  /**
   * The knob sidecar (`renders/<stem>.overrides.json`) beside [preview]'s render, verbatim, or
   * `null`.
   */
  private fun resolvePreviewOverrides(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val stem = rel.substringAfterLast('/').removeSuffix(".png")
    val f = File(rendersRoot, "$stem.$BUNDLE_OVERRIDES_SIDECAR_EXT")
    return if (f.isFile && f.length() > 0) f.readBytes() else null
  }

  /** The Remote Compose knob sidecar (`renders/<stem>.remotecompose.json`), verbatim, or `null`. */
  private fun resolvePreviewRemoteCompose(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    // Strip the primary capture's actual extension, as the writer does, so a non-PNG primary still
    // finds its sidecar.
    val leaf = rel.substringAfterLast('/')
    val stem = if ('.' in leaf) leaf.substringBeforeLast('.') else leaf
    val f = File(rendersRoot, "$stem.$BUNDLE_REMOTECOMPOSE_SIDECAR_EXT")
    return if (f.isFile && f.length() > 0) f.readBytes() else null
  }

  /**
   * The catalog-token sidecar for a CATALOG (#2167) / THEME_CATALOG (#2179) [preview]
   * (`data/catalog-tokens/<id>.catalog.json`, keyed by sheet id, mirroring the renderer's
   * `CatalogTokenSidecar`). Verbatim bytes, or `null`.
   */
  private fun resolvePreviewCatalogTokens(preview: PreviewInfo): ByteArray? {
    if (
      preview.params.kind !in
        setOf(
          PreviewKind.CATALOG,
          PreviewKind.THEME_CATALOG,
          // Wear theme sheets write the same sidecar.
          PreviewKind.WEAR_THEME_CATALOG,
        )
    ) {
      return null
    }
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val dataDir = File(rendersRoot.parentFile ?: rendersRoot, "data/catalog-tokens")
    val name = sanitizeCatalogTokenId(preview.id) + ".$BUNDLE_CATALOG_TOKENS_SIDECAR_EXT"
    val f = File(dataDir, name)
    return if (f.isFile && f.length() > 0) f.readBytes() else null
  }

  // Mirror of the renderer's `CatalogTokenSidecar.sanitize` so the on-disk filename matches.
  private fun sanitizeCatalogTokenId(id: String): String =
    id.replace(Regex("""[/\\:*?"<>|\s]"""), "_")

  /**
   * An asset preview's IR ([PreviewKind.LOTTIE] / [PreviewKind.SVG]): the file at
   * [PreviewParams.assetPath], looked up in [moduleResourcesDir] then each of
   * [moduleResourceRoots]. `null` when not found; the bundle then omits the IR.
   */
  private fun resolveAssetIr(
    preview: PreviewInfo,
    format: String,
    defaultExt: String,
  ): ResolvedIr? {
    val assetPath = preview.params.assetPath ?: return null
    val roots = buildList {
      moduleResourcesDir.orNull?.asFile?.let(::add)
      addAll(moduleResourceRoots.files)
    }
    for (root in roots) {
      val assetFile = File(root, assetPath)
      if (assetFile.isFile && assetFile.length() > 0L) {
        return ResolvedIr(
          format = format,
          ext = assetPath.substringAfterLast('.', missingDelimiterValue = defaultExt),
          bytes = assetFile.readBytes(),
        )
      }
    }
    return null
  }

  private fun resolvePreviewIr(preview: PreviewInfo): ResolvedIr? {
    // Asset previews: the IR is the asset itself, replayable with no consumer bytecode.
    when (preview.params.kind) {
      PreviewKind.LOTTIE -> return resolveAssetIr(preview, IR_FORMAT_LOTTIE, defaultExt = "json")
      PreviewKind.SVG -> return resolveAssetIr(preview, IR_FORMAT_SVG, defaultExt = "svg")
      else -> {}
    }

    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val stem = rel.substringAfterLast('/').removeSuffix(".png")

    fun resolveIrFile(ext: String): File? = resolveIrSidecar(rendersRoot, stem, ext)

    resolveIrFile(IR_EXT_REMOTECOMPOSE)?.let {
      return ResolvedIr(
        format = IR_FORMAT_REMOTECOMPOSE,
        ext = IR_EXT_REMOTECOMPOSE,
        bytes = it.readBytes(),
      )
    }
    resolveIrFile(IR_EXT_PROTOLAYOUT_LAYOUT)?.let { layout ->
      // The resources proto shares the layout's exact stem.
      val layoutStem = layout.name.removeSuffix(".$IR_EXT_PROTOLAYOUT_LAYOUT")
      val resources =
        File(rendersRoot, "$layoutStem.$IR_EXT_PROTOLAYOUT_RESOURCES").takeIf {
          it.isFile && it.length() > 0
        }
      return ResolvedIr(
        format = IR_FORMAT_PROTOLAYOUT,
        ext = IR_EXT_PROTOLAYOUT_LAYOUT,
        bytes = layout.readBytes(),
        resourcesExt = IR_EXT_PROTOLAYOUT_RESOURCES,
        resourcesBytes = resources?.readBytes(),
      )
    }
    return null
  }

  /**
   * Builds the v6 Android resource carriage into [zipFiles]: reads AGP's `test_config.properties`
   * (as the render path does) to find the merged resource APK + manifest, and packs the library R
   * classes. Returns null when inputs are missing (no prior render, no binary resources), leaving
   * the bundle well-formed.
   */
  private fun resolveAndroidResources(
    zipFiles: LinkedHashMap<String, ByteArray>
  ): BundleAndroidResources? {
    // `test_config.properties` is nested in AGP's config directory, so walk the input as a file
    // tree (`.files` only returns the directory). Try the dedicated input, then the unit-test
    // runtime classpath, which also carries it.
    val configFile =
      sequenceOf(androidUnitTestConfig, androidUnitTestRuntimeClasspath)
        .flatMap { it.asFileTree.files.asSequence() }
        .firstOrNull { it.isFile && it.name == "test_config.properties" }
    if (configFile == null) {
      logger.warn(
        "composePreviewBundle: no test_config.properties on the unit-test config / runtime-classpath " +
          "inputs — a detached daemon can't resolve app resources (stringResource / tile themes). " +
          "Run composePreviewRender first so AGP generates it."
      )
      return null
    }
    val props = Properties().apply { configFile.inputStream().use { load(it) } }
    val apkPath = props.getProperty("android_resource_apk")?.trim().orEmpty()
    val manifestPath = props.getProperty("android_merged_manifest")?.trim().orEmpty()
    val pkg = props.getProperty("android_custom_package")?.trim()?.takeIf { it.isNotEmpty() }
    // AGP writes these relative to the module dir, as Robolectric resolves them; a plain
    // `File(path)` would use the build's CWD. Absolute paths pass through; fall back to the module
    // root before `/build/`.
    val baseDir =
      moduleProjectDir.asFile.orNull
        ?: configFile.absolutePath.substringBeforeLast("/build/").let(::File).takeIf {
          it.isDirectory
        }
    fun resolveModulePath(p: String): File? {
      if (p.isEmpty()) return null
      val f = File(p)
      return when {
        f.isAbsolute -> f
        baseDir != null -> File(baseDir, p)
        else -> f
      }
    }
    val apkFile = resolveModulePath(apkPath)
    val manifestFile = resolveModulePath(manifestPath)
    if (apkFile == null || !apkFile.isFile || manifestFile == null || !manifestFile.isFile) {
      logger.warn(
        "composePreviewBundle: the merged resource APK / manifest from test_config.properties is " +
          "missing (apk='$apkPath', manifest='$manifestPath') — a detached daemon can't resolve app " +
          "resources (stringResource / tile themes)."
      )
      return null
    }
    // Drop merged-AAR file resources a render never inflates; `resources.arsc` stays byte-identical
    // and resources authored in this build are retained. See [AndroidResourcePruner] and
    // [MergedResourceOwnership].
    val apkBytes = apkFile.readBytes()
    val prunableFileResources = prunableFileResourceKeys()
    val prunedApk =
      if (prunableFileResources.isNotEmpty()) {
        AndroidResourcePruner.prune(apkBytes, prunableFileResources)
      } else {
        // Nothing attributed to a dependency (or no blame data): keep the full APK; a dangling
        // entry breaks the live render for a saving of tens of KB.
        AndroidResourcePruner.Result(apkBytes, droppedEntries = 0, bytesSaved = 0)
      }
    zipFiles[ANDROID_RESOURCE_APK_PATH] = prunedApk.bytes
    zipFiles[ANDROID_MERGED_MANIFEST_PATH] = manifestFile.readBytes()
    val rClassesJar = packAndroidRClasses()
    if (rClassesJar != null) zipFiles[ANDROID_R_CLASSES_JAR_PATH] = rClassesJar
    logger.lifecycle(
      "composePreviewBundle — carried Android resources for detached render " +
        "(apk=${prunedApk.bytes.size}B, was ${apkFile.length()}B — dropped " +
        "${prunedApk.droppedEntries} unused file resources / ${prunedApk.bytesSaved}B; " +
        "manifest=${manifestFile.length()}B, " +
        "rClasses=${if (rClassesJar != null) "${rClassesJar.size}B" else "none"})"
    )
    return BundleAndroidResources(
      resourceApkPath = ANDROID_RESOURCE_APK_PATH,
      mergedManifestPath = ANDROID_MERGED_MANIFEST_PATH,
      rClassesJarPath = if (rClassesJar != null) ANDROID_R_CLASSES_JAR_PATH else null,
      applicationPackage = pkg,
    )
  }

  /**
   * Resource ids (`"<typeBase>/<name>"`) [AndroidResourcePruner] may drop: merged from a
   * third-party AAR and contributed by no project in this build. Computed as AAR-attributed
   * ([MergedResourceOwnership]) minus project-attributed minus this module's own
   * `src/<sourceSet>/res` (a floor that needs no blame data). `values` never appear (they live in
   * `resources.arsc`).
   *
   * Empty means drop nothing, the safe answer when blame data is unusable. Getting this wrong
   * pruned sibling modules' drawables and broke `painterResource` (#3260, #3299).
   */
  private fun prunableFileResourceKeys(): Set<String> {
    val buildDir =
      moduleBuildDir.asFile.orNull ?: moduleProjectDir.asFile.orNull?.let { File(it, "build") }
    val ownership =
      buildDir?.let { MergedResourceOwnership.fileResourceOwnership(it) } ?: return emptySet()
    return ownership.prunable - moduleOwnSourceSetResourceKeys()
  }

  /** Resource ids this module authors in its own `src/<sourceSet>/res`, never dropped. */
  private fun moduleOwnSourceSetResourceKeys(): Set<String> {
    val srcDir = moduleProjectDir.asFile.orNull?.let { File(it, "src") } ?: return emptySet()
    if (!srcDir.isDirectory) return emptySet()
    val keys = mutableSetOf<String>()
    srcDir.listFiles()?.forEach { sourceSet ->
      val resDir = File(sourceSet, "res")
      if (!resDir.isDirectory) return@forEach
      resDir.listFiles()?.forEach { typeDir ->
        val typeBase = typeDir.name.substringBefore('-')
        if (!typeDir.isDirectory || typeBase == "values") return@forEach
        typeDir.listFiles()?.forEach { f ->
          if (f.isFile) keys += "$typeBase/${AndroidResourcePruner.resourceNameOf(f.name)}"
        }
      }
    }
    return keys
  }

  /**
   * Collects generated `R.class` / `R$*.class` files from the unit-test runtime classpath and
   * [dependencyJars] into one jar. Library R fields are non-final, so code links against the
   * `R$style` class, which AAR `classes.jar`s don't contain; with non-transitive R the tile
   * renderer's is only in the merged unit-test R.jar (see [androidUnitTestRuntimeClasspath]). All R
   * classes are kept (deduped) since they're tiny. Null when none.
   */
  private fun packAndroidRClasses(): ByteArray? {
    val jars =
      (androidUnitTestRuntimeClasspath.files + dependencyJars.files)
        .filter { it.isFile && it.name.endsWith(".jar") }
        .distinct()
    if (jars.isEmpty()) return null
    val collected = LinkedHashMap<String, ByteArray>()
    for (jar in jars) {
      try {
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
          while (true) {
            val entry = zin.nextEntry ?: break
            val name = entry.name
            val leaf = name.substringAfterLast('/')
            val isR = leaf == "R.class" || (leaf.startsWith("R$") && leaf.endsWith(".class"))
            if (!entry.isDirectory && isR && name !in collected) {
              collected[name] = zin.readBytes()
            }
            zin.closeEntry()
          }
        }
      } catch (e: Exception) {
        logger.warn("composePreviewBundle: couldn't scan $jar for R classes: ${e.message}")
      }
    }
    if (collected.isEmpty()) return null
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      collected.forEach { (name, bytes) -> zip.writeFile(name, bytes) }
    }
    return baos.toByteArray()
  }

  private fun packModuleClasses(
    classDirs: List<File>,
    reachable: Set<String>,
  ): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    for (root in classDirs) {
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { f ->
          val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
          val fqn = rel.removeSuffix(".class").replace('/', '.')
          if (fqn in reachable) {
            result[rel] = f.readBytes()
          }
        }
    }
    return result
  }

  private fun collectClassFqns(classDirs: List<File>): Set<String> {
    val result = mutableSetOf<String>()
    for (root in classDirs) {
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { f ->
          val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
          result += rel.removeSuffix(".class").replace('/', '.')
        }
    }
    return result
  }

  /** Jar-form [collectClassFqns] for scoped project-class jars. */
  private fun collectClassFqnsFromJars(jars: List<File>): Set<String> {
    val result = mutableSetOf<String>()
    for (jar in jars) {
      try {
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
          while (true) {
            val entry = zin.nextEntry ?: break
            val name = entry.name
            if (!entry.isDirectory && name.endsWith(".class")) {
              result += name.removeSuffix(".class").replace('/', '.')
            }
            zin.closeEntry()
          }
        }
      } catch (e: Exception) {
        logger.warn("composePreviewBundle: couldn't scan $jar for module classes: ${e.message}")
      }
    }
    return result
  }

  /**
   * Jar-form [packModuleClasses]: reachable `.class` entries, keyed by the same relative path as
   * the directory packer.
   */
  private fun packModuleClassesFromJars(
    jars: List<File>,
    reachable: Set<String>,
  ): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    for (jar in jars) {
      try {
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
          while (true) {
            val entry = zin.nextEntry ?: break
            val name = entry.name
            if (!entry.isDirectory && name.endsWith(".class")) {
              val fqn = name.removeSuffix(".class").replace('/', '.')
              if (fqn in reachable && name !in result) {
                result[name] = zin.readBytes()
              }
            }
            zin.closeEntry()
          }
        }
      } catch (e: Exception) {
        logger.warn("composePreviewBundle: couldn't pack module classes from $jar: ${e.message}")
      }
    }
    return result
  }

  private fun buildDepDecisions(
    jars: List<File>,
    perElement: Map<String, PerElementCount>,
    coordMap: Map<String, String>,
  ): List<DependencyDecision> = jars.map { jar ->
    val totals = perElement[jar.absolutePath]
    val reachable = totals?.reachable ?: 0
    val total = totals?.total ?: 0
    val rawCoord = coordMap[jar.absolutePath]
    val mavenCoord = rawCoord?.takeIf { it.startsWith("maven:") }?.removePrefix("maven:")
    val projectPath = rawCoord?.takeIf { it.startsWith("project:") }?.removePrefix("project:")
    DependencyDecision(
      sourcePath = jar.absolutePath,
      coordinate = mavenCoord,
      projectPath = projectPath,
      totalClasses = total,
      reachableClasses = reachable,
      originalBytes = jar.length(),
      kept = reachable > 0,
    )
  }

  /** The classpath manifest entries, the jars to inline under `libs/`, and the resolution mode. */
  internal data class AssembledClasspath(
    val entries: List<ClasspathEntry>,
    val inlinedJars: Map<String, File>,
    val resolution: String,
  )

  /**
   * Builds the manifest classpath from kept dependency decisions:
   * - Project deps are inlined under `libs/` as [ClasspathEntry.Project].
   * - Maven deps are [ClasspathEntry.Maven] coordinates, or [ClasspathEntry.Embedded] in `libs/`
   *   when [embed].
   *
   * `resolution` reflects the result: `embedded`, `mixed`, or `coordinates`.
   */
  internal fun assembleClasspath(
    jars: List<File>,
    deps: List<DependencyDecision>,
    embed: Boolean,
  ): AssembledClasspath {
    val byPath = jars.associateBy { it.absolutePath }
    val entries = mutableListOf<ClasspathEntry>(ClasspathEntry.Module(path = "classes/app.jar"))
    val inlinedJars = LinkedHashMap<String, File>()
    var mavenReferenced = 0
    var mavenEmbedded = 0
    seenJarNames.clear()
    for (dep in deps) {
      if (!dep.kept) continue
      val coord = dep.coordinate
      val src = byPath[dep.sourcePath]
      when {
        // Reference by coordinate, with a content hash for verification after re-resolution.
        coord != null && !embed -> {
          entries += parseMavenCoord(coord, src)
          mavenReferenced++
        }
        // Maven-resolved dep, embed mode: carry the jar in `libs/` (skip if its file is missing).
        coord != null -> {
          if (src != null) {
            val inlined = "libs/${dedupeJarName(src.name)}"
            inlinedJars[inlined] = src
            entries += ClasspathEntry.Embedded(inlinedAs = inlined)
            mavenEmbedded++
          } else {
            // No file to embed — fall back to a coordinate reference rather than dropping the dep.
            entries += parseMavenCoord(coord, src = null)
            mavenReferenced++
          }
        }
        // Project-local dep (no coordinate): always inline.
        else -> {
          val name = src?.name ?: File(dep.sourcePath).name
          val inlined = "libs/${dedupeJarName(name)}"
          if (src != null) inlinedJars[inlined] = src
          entries += ClasspathEntry.Project(path = dep.projectPath ?: ":anon", inlinedAs = inlined)
        }
      }
    }
    seenJarNames.clear()
    val resolution =
      when {
        mavenEmbedded > 0 && mavenReferenced > 0 -> RESOLUTION_MIXED
        mavenEmbedded > 0 -> RESOLUTION_EMBEDDED
        else -> RESOLUTION_COORDINATES
      }
    return AssembledClasspath(entries = entries, inlinedJars = inlinedJars, resolution = resolution)
  }

  /**
   * Parses `"<group>:<artifact>:<version>[:<type>]"` (type defaults to `jar`). With [src], records
   * its SHA-256 so players can verify re-resolved bytes.
   */
  /**
   * Fails a `backend: desktop` pack carrying Android-only artifacts, which would fail every render
   * on a host JVM (`NoClassDefFoundError: android/os/Parcelable`) only after publication — as
   * happened when the runtime config was resolved eagerly on KMP-Android.
   *
   * Checked against resolved Maven coordinates, since transformed `classes.jar` paths lose the
   * artifact id. Takes [DependencyDecision]s so it also works under `--embed-deps`, where entries
   * carry no coordinates.
   */
  internal fun failOnAndroidClasspathInDesktopBundle(
    backendId: String,
    decisions: List<DependencyDecision>,
  ) {
    if (backendId != "desktop") return
    val offenders =
      decisions
        .filter { it.kept }
        .mapNotNull { it.coordinate }
        .mapNotNull { coord ->
          // "<group>:<artifact>:<version>[:<type>]" — the `maven:`-stripped shape.
          val parts = coord.split(':')
          if (parts.size < 3) return@mapNotNull null
          val (group, artifact, version) = parts
          val type = parts.getOrNull(3) ?: "jar"
          if (type.equals("aar", ignoreCase = true) || artifact.endsWith("-android")) {
            "$group:$artifact:$version ($type)"
          } else null
        }
        .distinct()
        .sorted()
    if (offenders.isEmpty()) return
    throw GradleException(
      buildString {
        appendLine(
          "composePreviewBundle: refusing to pack a desktop bundle whose classpath carries " +
            "${offenders.size} Android-only artifact(s). A desktop bundle replays on a host JVM " +
            "with no android.jar, so every render would fail with " +
            "NoClassDefFoundError: android/os/Parcelable."
        )
        offenders.take(8).forEach { appendLine(" - $it") }
        if (offenders.size > 8) appendLine(" - (+${offenders.size - 8} more)")
        appendLine()
        appendLine(
          "This means the module's desktop runtime classpath resolved to androidRuntimeClasspath. " +
            "If it is a com.android.kotlin.multiplatform.library (:shared) module, add a " +
            "`jvm(\"desktop\")` target to its `kotlin { }` block. See " +
            "compose-preview/references/cmp-shared.md."
        )
      }
    )
  }

  private fun parseMavenCoord(coord: String, src: File?): ClasspathEntry.Maven {
    val parts = coord.split(':')
    require(parts.size >= 3) { "composePreviewBundle: malformed Maven coordinate: $coord" }
    return ClasspathEntry.Maven(
      group = parts[0],
      artifact = parts[1],
      version = parts[2],
      type = parts.getOrNull(3) ?: "jar",
      sha256 = src?.let { sha256Hex(it) },
    )
  }

  /**
   * Lowercase hex SHA-256 of [file]'s bytes, streamed so large jars don't load fully into memory.
   */
  private fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun buildJar(classes: Map<String, ByteArray>, resourceDirs: List<File>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      classes.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // A duplicate zip entry is invalid; pack each resource once.
      val written = HashSet(classes.keys)
      for (resourcesDir in resourceDirs) {
        if (!resourcesDir.isDirectory) continue
        resourcesDir
          .walkTopDown()
          .filter { it.isFile }
          .forEach { f ->
            val rel = f.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
            if (written.add(rel)) zip.writeFile(rel, f.readBytes())
          }
      }
    }
    return baos.toByteArray()
  }

  private fun buildZip(
    bundleJson: String,
    previewsJson: String,
    componentsJson: String,
    uiBuilderJson: String?,
    uiBuilderTemplates: Map<String, ByteArray>,
    appJar: ByteArray,
    inlinedProjectJars: Map<String, File>,
    report: String,
    previewPngs: Map<String, ByteArray>,
    motionFiles: Map<String, ByteArray>,
    irFiles: Map<String, ByteArray>,
    dataExtensionFiles: Map<String, ByteArray>,
    overrideFiles: Map<String, ByteArray>,
    catalogTokenFiles: Map<String, ByteArray>,
    guidelineResults: ByteArray? = null,
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      // Guideline results, when a run produced them.
      guidelineResults?.let { zip.writeFile(GUIDELINE_RESULTS_ENTRY, it) }
      zip.writeFile("bundle.json", bundleJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("previews.json", previewsJson.toByteArray(Charsets.UTF_8))
      // `components.json` travels with its manifest, since detached consumers read only the bundle.
      zip.writeFile("components.json", componentsJson.toByteArray(Charsets.UTF_8))
      // `ui-builder.json` only when the module authors a builder policy; absence means no builder
      // catalog.
      uiBuilderJson?.let { zip.writeFile("ui-builder.json", it.toByteArray(Charsets.UTF_8)) }
      // The catalog's own design guidance, beside the catalog it belongs to, when there is one.
      if (uiBuilderJson != null) {
        uiBuilderGuidelinesBytes(uiBuilderJson)?.let {
          zip.writeFile(UiBuilderGuidelinesFile.FILE_NAME, it)
        }
      }
      // The advertised template designs, at the paths the catalog names; `catalog-ui-builder.mjs`
      // publishes only from bundle entries.
      uiBuilderTemplates.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // One baked PNG per selected preview under the well-known `previews/` directory.
      previewPngs.forEach { (id, bytes) -> zip.writeFile("$BUNDLE_PREVIEWS_DIR/$id.png", bytes) }
      // Renderer-named APNG/GIF siblings consumed by catalog motion publishing and bundle readers.
      motionFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // (v8) Per-preview override sidecars under `previews/<id>.overrides.json`.
      overrideFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // Per-sheet catalog-token sidecars under `previews/<id>.catalog.json` (issue #2167).
      catalogTokenFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // Captured IR bytes (Remote Compose doc / protolayout proto) under `ir/`.
      irFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      // (v7) Optional per-extension data reports under `extensions/<id>.json`.
      dataExtensionFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      zip.writeFile("classes/app.jar", appJar)
      inlinedProjectJars.forEach { (path, file) -> zip.writeFile(path, file.readBytes()) }
      zip.writeFile("report.json", report.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
  }

  /**
   * Slices a report to the cover preview: any top-level array of objects with a string `previewId`
   * is filtered to [coverId]; everything else is untouched. A generic structural convention, not
   * per-extension logic. Returns the input unchanged on parse failure or when nothing matched.
   */
  private fun scopeReportToCoverPreview(reportBytes: ByteArray, coverId: String): ByteArray {
    val root =
      try {
        Json.parseToJsonElement(reportBytes.toString(Charsets.UTF_8))
      } catch (_: Exception) {
        return reportBytes
      }
    if (root !is JsonObject) return reportBytes
    var changed = false
    val scoped = root.mapValues { (_, value) ->
      if (
        value is JsonArray &&
          value.isNotEmpty() &&
          value.all { it is JsonObject && (it["previewId"] as? JsonPrimitive)?.isString == true }
      ) {
        val kept = value.filter {
          (it as JsonObject)["previewId"]!!.jsonPrimitive.content == coverId
        }
        if (kept.size != value.size) changed = true
        JsonArray(kept)
      } else {
        value
      }
    }
    return if (changed) JsonObject(scoped).toString().toByteArray(Charsets.UTF_8) else reportBytes
  }

  private fun ZipOutputStream.writeFile(path: String, bytes: ByteArray) {
    // Fixed timestamps make bundles byte-identical across builds (build cache, content hashing).
    val entry = ZipEntry(path)
    entry.time = ZIP_DOS_EPOCH_MS
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  /** Dedupes project-dep jar basenames with a counter; Maven coordinates can't collide. */
  private val seenJarNames = mutableMapOf<String, Int>()

  private fun dedupeJarName(name: String): String {
    val count = seenJarNames.getOrDefault(name, 0)
    seenJarNames[name] = count + 1
    return if (count == 0) name else name.removeSuffix(".jar") + "-$count.jar"
  }

  private data class PerElementCount(val reachable: Int, val total: Int)

  private data class Closure(
    /** Classes reachable from the dep seed (every preview) — drives which deps are kept. */
    val depReachable: Set<String>,
    /** Classes reachable from the pack seed (non-IR previews) — drives module-class packing. */
    val packReachable: Set<String>,
    val perElement: Map<String, PerElementCount>,
    val totalScanned: Int,
  )

  private fun closureWalk(
    scanPaths: List<String>,
    depSeed: Set<String>,
    packSeed: Set<String>,
  ): Closure {
    if (scanPaths.isEmpty()) {
      return Closure(
        depReachable = depSeed.toSet(),
        packReachable = packSeed.toSet(),
        perElement = emptyMap(),
        totalScanned = 0,
      )
    }
    ClassGraph()
      .enableAllInfo()
      .enableInterClassDependencies()
      .overrideClasspath(scanPaths)
      .ignoreParentClassLoaders()
      .scan()
      .use { scan ->
        val depReachable = bfsReachable(scan, depSeed)
        val packReachable = bfsReachable(scan, packSeed)
        // Dependency reachability uses the dep closure, so IR previews' deps are recorded (see
        // `pack`).
        val perElementReachable = HashMap<String, IntArray>() // [reachable, total]
        for (ci in scan.allClasses) {
          val file = ci.classpathElementFile?.absolutePath ?: continue
          val counts = perElementReachable.getOrPut(file) { IntArray(2) }
          counts[1]++
          if (ci.name in depReachable) counts[0]++
        }
        return Closure(
          depReachable = depReachable,
          packReachable = packReachable,
          perElement = perElementReachable.mapValues { (_, c) -> PerElementCount(c[0], c[1]) },
          totalScanned = scan.allClasses.size,
        )
      }
  }

  /**
   * BFS over ClassGraph's class dependency map from [seed]. Seeds include `$`-prefixed companions
   * of each entry (`ComposableSingletons$FooKt`, `FooKt$lambda-1`, …) as insurance against missing
   * inner-class edges.
   */
  private fun bfsReachable(scan: io.github.classgraph.ScanResult, seed: Set<String>): Set<String> {
    if (seed.isEmpty()) return emptySet()
    val depMap = scan.classDependencyMap
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    for (entry in seed) {
      for (ci in scan.allClasses) {
        if (ci.name == entry || ci.name.startsWith("$entry$")) {
          if (visited.add(ci.name)) queue += ci.name
        }
      }
    }
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val ci = scan.getClassInfo(current) ?: continue
      val deps = depMap[ci] ?: continue
      for (dep in deps) {
        if (visited.add(dep.name)) queue += dep.name
      }
    }
    return visited
  }

  private companion object {
    /** The bundle entry a guidelines run's results travel as; the CLI's file name. */
    const val GUIDELINE_RESULTS_ENTRY = "guidelines.json"

    val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
      classDiscriminator = "kind"
    }

    /**
     * Seed for protolayout IR, so the `TileRenderer` runtime the daemon replays through is carried
     * as coordinates; it pulls the rest transitively.
     */
    val PROTOLAYOUT_REPLAY_ENTRY_FQNS = setOf("androidx.wear.tiles.renderer.TileRenderer")

    /**
     * Seeds for Remote Compose IR, so the player runtime (`RemoteDocument`, `RemoteDocumentPlayer`)
     * is carried; the preview's bytecode only references creation APIs. The rc-players entries
     * cover players the connector loads via `Class.forName`, which the walk can't see. Each seeds
     * nothing when its jar is absent.
     */
    val REMOTECOMPOSE_REPLAY_ENTRY_FQNS =
      setOf(
        "androidx.compose.remote.player.core.RemoteDocument",
        "androidx.compose.remote.player.compose.RemoteDocumentPlayerKt",
        // rc-players: `third-party-rc-embedded-player` (androidx-embedded).
        "ee.schimke.composeai.rcembedded.player.RcPlayerKt",
        // rc-players: `rc-player-compose` (cmp-android).
        "ee.schimke.composeai.rcplayer.compose.RcComposePlayerKt",
      )

    /**
     * Soft size above which an embed-deps pack warns: well above a normal embedded Compose graph.
     */
    const val EMBED_SIZE_WARN_BYTES: Long = 25_000_000L

    /**
     * Fixed ZIP entry timestamp: 1980-01-01, the DOS-epoch floor ZIP can represent (earlier values
     * are clamped), as used by reproducible-build tools.
     */
    val ZIP_DOS_EPOCH_MS: Long =
      java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis

    /** 1×1 gray PNG cover for when nothing was rendered. */
    val STUB_GRAY_PNG: ByteArray by lazy {
      val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, 0x808080)
      val baos = ByteArrayOutputStream()
      ImageIO.write(img, "png", baos)
      baos.toByteArray()
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
   * The module's builder catalog JSON, or null without a policy. [full] covers every preview, so a
   * policy declared on another preview of the same component still applies (policy is
   * component-wide); [carried] limits entries to components the bundle contains.
   */
  /**
   * Template designs the generated catalog advertises, read back from the generated JSON so
   * carriage and advertisement can't drift.
   */
  private fun uiBuilderTemplatesFor(uiBuilderJson: String?): Map<String, ByteArray> {
    val catalog =
      uiBuilderJson?.let {
        runCatching { JSON.decodeFromString<UiBuilderCatalogFile>(it) }.getOrNull()
      } ?: return emptyMap()
    val declared = catalog.statusSemantics.templates
    val found =
      UiBuilderTemplateLookup.resolve(
        uiBuilderTemplateRoots.files,
        declared,
        moduleOwnsPolicy = authoredPair()?.moduleOwns ?: true,
      )
    (declared - found.keys).sorted().forEach {
      logger.warn(
        "composePreview: the builder catalog names template design '$it', which is not under " +
          "ui-builder/ in this module or the repository root; it will be missing from the bundle " +
          "and from the delivery branch."
      )
    }
    // Parse before carrying, so a broken design is named now rather than advertised and failing
    // later.
    val (usable, unreadable) =
      found.entries.partition { (_, file) ->
        runCatching { JSON.parseToJsonElement(file.readText()) }.isSuccess
      }
    unreadable.forEach { (path, file) ->
      logger.warn(
        "composePreview: template design '$path' (${file.path}) is not readable JSON, so it is " +
          "not carried in the bundle; the builder catalog names it and it will be missing."
      )
    }
    return usable.associate { (path, file) -> path to file.readBytes() }
  }

  /**
   * Authored names per component's policy for [ComponentRecords.select], or null; from the same
   * pair [uiBuilderJsonFor] uses.
   */
  private fun overloadNames(module: String): ((ComponentRecord) -> Set<String>)? {
    val authored = authoredPair() ?: return null
    val lenient = Json { ignoreUnknownKeys = true }
    val policy =
      runCatching { lenient.decodeFromString<UiBuilderPolicyFile>(authored.policy.readText()) }
        .getOrNull() ?: return null
    val spec =
      authored.spec?.let {
        runCatching { lenient.decodeFromString<BundleCoverSheet>(it.readText()) }.getOrNull()
      }
    val cover =
      UiBuilderCatalogs.CoverSheet(
        system = spec?.system ?: policy.catalogId ?: module.trimStart(':'),
        title = spec?.title ?: policy.catalogId ?: module,
      )
    return UiBuilderCatalogs.authoredNames(cover, policy)
  }

  private fun uiBuilderJsonFor(
    full: ComponentRecordFile,
    carried: ComponentRecordFile,
    overloadDiagnostics: List<UiBuilderDiagnostic> = emptyList(),
  ): String? {
    val carriedIds = carried.components.map { it.canonicalId }.toSet()
    // Components from `full` (bundling one preview doesn't shrink a component's record), orphans
    // from `carried` (an orphan is a property of a preview, so only selected previews' orphans
    // belong here).
    val record =
      full
        .newBuilder()
        .also { b ->
          b.components = full.components.filter { it.canonicalId in carriedIds }
          b.builderOrphans = carried.builderOrphans
        }
        .build()
    val authored = authoredPair() ?: return null
    val (policyFile, specFile) = authored.policy to authored.spec
    val lenient = Json { ignoreUnknownKeys = true }
    val policy = runCatching {
      lenient.decodeFromString<UiBuilderPolicyFile>(policyFile.readText())
    }
      .getOrElse { failure ->
        logger.warn(
          "composePreview: ${policyFile.path} could not be read " +
            "(${failure.message ?: failure::class.simpleName}); the bundle carries no " +
            "ui-builder.json."
        )
        return null
      }
    // The cover sheet from the same location as the policy; deriving it independently would
    // disagree with discovery's ui-builder.json.
    val spec = specFile?.let {
      runCatching { lenient.decodeFromString<BundleCoverSheet>(it.readText()) }.getOrNull()
    }
    val cover =
      UiBuilderCatalogs.CoverSheet(
        system = spec?.system ?: policy.catalogId ?: record.module.trimStart(':'),
        title = spec?.title ?: policy.catalogId ?: record.module,
      )
    val catalog =
      UiBuilderCatalogs.generate(
        record,
        cover,
        policy,
        overloadDiagnostics.filter { it.subject in carriedIds },
      ) ?: return null
    return JSON.encodeToString(UiBuilderCatalogFile.serializer(), catalog)
  }

  /**
   * The catalog's `ui-builder.guidelines.json` beside the policy, flattened, when well formed; else
   * null with a warning.
   */
  private fun guidelineResultsBytes(bundleIds: Map<String, String>): ByteArray? =
    guidelineResultsEntry(guidelineResultsFiles.files, bundleIds) { file ->
      logger.warn("compose-preview: ${file.name} is not a guidelines report; not bundled")
    }

  private fun uiBuilderGuidelinesBytes(uiBuilderJson: String): ByteArray? {
    val authored = authoredPair() ?: return null
    val source = UiBuilderGuidelinesFile.besidePolicy(authored.policy) ?: return null
    val catalogId =
      runCatching { JSON.decodeFromString<UiBuilderCatalogFile>(uiBuilderJson).catalog.id }
        .getOrNull() ?: return null
    val text = source.readText()
    val problems = UiBuilderGuidelinesFile.problems(text, catalogId)
    if (problems.isNotEmpty()) {
      logger.warn(
        "composePreview: ${source.path} is not carried in the bundle: ${problems.joinToString("; ")}."
      )
      return null
    }
    val flat = UiBuilderGuidelinesFile.flatten(text)
    flat.problem?.let {
      logger.warn(
        "composePreview: ${source.path}'s includes are not resolved ($it); bundled as written."
      )
    }
    return flat.text.toByteArray(Charsets.UTF_8)
  }

  /** The two `catalog.spec.json` fields a builder catalog wants; the rest is the pipeline's. */
  @Serializable private data class BundleCoverSheet(val system: String, val title: String)
}

/**
 * The IR sidecar `<stem>.<ext>` under [rendersRoot], else the min-named `@PreviewParameter` sibling
 * (`<stem>_<param>.<ext>` / `<stem>--<dim>.<ext>`), matching the representative cover PNG. `null`
 * when neither exists. Top-level for tests.
 */
internal fun resolveIrSidecar(rendersRoot: File, stem: String, ext: String): File? {
  val exact = File(rendersRoot, "$stem.$ext")
  if (exact.isFile && exact.length() > 0) return exact
  return rendersRoot
    .listFiles { f ->
      f.isFile &&
        f.length() > 0 &&
        f.name.endsWith(".$ext") &&
        (f.name.startsWith("${stem}_") || f.name.startsWith("$stem--"))
    }
    ?.minByOrNull { it.name }
}

/**
 * A preview id as used inside a bundle (entry names and both manifests), applying the renderer's
 * file-stem substitution (`[^A-Za-z0-9._-]` → `_`) so entries are shell- and URL-safe. Dots and
 * dashes are kept.
 */
internal fun sanitizeBundleEntryId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * Portable WebGL/WebXR scene entries for one XR render, renamed to the bundle id with a `.spatial/`
 * suffix. The allowlist mirrors the server's ingestion boundary so stray renderer files never
 * become public bundle content.
 */
internal fun spatialBundleEntries(
  rendersDir: File?,
  rawPreviewId: String,
  bundleId: String,
): Map<String, ByteArray> {
  val source = rendersDir?.resolve(sanitizeBundleEntryId(rawPreviewId)) ?: return emptyMap()
  val sceneFile = source.resolve(SPATIAL_SCENE_FILE).takeIf { it.isFile } ?: return emptyMap()
  val referencedTextures = runCatching {
    val scene = Json.parseToJsonElement(sceneFile.readText()) as JsonObject
    sequenceOf("panels", "orbiters")
      .flatMap { key -> (scene[key] as? JsonArray).orEmpty().asSequence() }
      .mapNotNull { panel ->
        (panel as? JsonObject)?.get("texture")?.jsonPrimitive?.content?.takeIf { texture ->
          texture.isNotBlank() && '/' !in texture && '\\' !in texture
        }
      }
      .toSet()
  }
    .getOrDefault(emptySet())
  return source
    .listFiles()
    .orEmpty()
    .asSequence()
    .filter { file ->
      file.isFile &&
        file.length() > 0 &&
        (file.name == SPATIAL_SCENE_FILE ||
          (file.name in referencedTextures &&
            SPATIAL_IMAGE_SUFFIXES.any { file.name.endsWith(it, ignoreCase = true) }))
    }
    .sortedBy { it.name }
    .associateTo(LinkedHashMap()) { file ->
      "$BUNDLE_PREVIEWS_DIR/$bundleId$SPATIAL_BUNDLE_SUFFIX/${file.name}" to file.readBytes()
    }
}

private const val SPATIAL_BUNDLE_SUFFIX = ".spatial"
private const val SPATIAL_SCENE_FILE = "scene.json"
private val SPATIAL_IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp")

/**
 * Collision-free bundle ids for [rawIds], in order: [sanitizeBundleEntryId] can merge distinct ids
 * (`"A B"` and `"A_B"`), which would overwrite entries and duplicate manifest ids. Later collisions
 * get `_<n>`. A repeated raw id maps to the same bundle id.
 */
/**
 * Rewrites [BuilderPolicy.declaredBy] / [BuilderPolicy.conflicting] (preview ids, merged from the
 * full record) into the bundle's id namespace, dropping previews the bundle doesn't carry. The
 * policy's other lists aren't preview ids. Extracted for testing.
 */
internal fun remapPolicyPreviewIds(
  policy: BuilderPolicy?,
  bundleIds: Map<String, String>,
): BuilderPolicy? = policy?.let { p ->
  p.newBuilder()
    .also { b ->
      b.declaredBy = p.declaredBy.mapNotNull(bundleIds::get)
      b.conflicting = p.conflicting.mapNotNull(bundleIds::get)
    }
    .build()
}

internal fun assignBundleEntryIds(rawIds: List<String>): Map<String, String> {
  val used = HashSet<String>()
  val result = LinkedHashMap<String, String>()
  for (raw in rawIds) {
    if (result.containsKey(raw)) continue
    val base = sanitizeBundleEntryId(raw)
    var candidate = base
    var n = 1
    while (!used.add(candidate)) {
      candidate = "${base}_$n"
      n++
    }
    result[raw] = candidate
  }
  return result
}

/**
 * The first of [candidates] that reads as a guidelines report (an object with a `results` array),
 * or null; unreadable files are reported via [onUnreadable].
 */
internal fun guidelineResultsEntry(
  candidates: Iterable<File>,
  bundleIds: Map<String, String>? = null,
  onUnreadable: (File) -> Unit = {},
): ByteArray? {
  val file = candidates.firstOrNull { it.isFile } ?: return null
  val bytes = file.readBytes()
  val root =
    runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
      as? kotlinx.serialization.json.JsonObject
  val results = root?.get("results") as? kotlinx.serialization.json.JsonArray
  if (root == null || results == null) {
    onUnreadable(file)
    return null
  }
  if (bundleIds == null) return bytes
  // Keyed by bundle id like everything else in the bundle; results for previews not carried are
  // dropped.
  val remapped = results.mapNotNull { element ->
    val result = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
    val raw =
      (result["previewId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: return@mapNotNull null
    val id = bundleIds[raw] ?: return@mapNotNull null
    kotlinx.serialization.json.JsonObject(
      result.mapValues { (key, value) ->
        when {
          key == "previewId" -> kotlinx.serialization.json.JsonPrimitive(id)
          key == "record" && value is kotlinx.serialization.json.JsonObject ->
            kotlinx.serialization.json.JsonObject(
              value.mapValues { (k, v) ->
                if (k == "previewId") kotlinx.serialization.json.JsonPrimitive(id) else v
              }
            )
          else -> value
        }
      }
    )
  }
  val rewritten =
    kotlinx.serialization.json.JsonObject(
      root + ("results" to kotlinx.serialization.json.JsonArray(remapped))
    )
  return Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), rewritten)
    .toByteArray(Charsets.UTF_8)
}
