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
 * Pack a portable preview bundle — a PNG+ZIP polyglot containing the selected previews' metadata,
 * the minimal set of consumer classes reachable from those previews, a **list** of Maven
 * coordinates the player resolves at open time, and a minimization report. See
 * [PreviewBundleFormat] for the on-disk layout.
 *
 * # Dependency strategy
 *
 * Only consumer-module bytecode is inlined into the bundle (`classes/app.jar`, minimized to classes
 * reachable from the selected previews). In the default `resolution = "coordinates"` mode every
 * Maven-resolved runtime dependency is recorded as a `ClasspathEntry.Maven` coordinate — not
 * bundled — so the bundle stays small enough to share over chat / paste into a gist, and the player
 * resolves the coordinates from the consumer's normal Gradle repos at open time. Project deps that
 * have no Maven coordinate (transitive `:my-lib` references inside the consumer's build) fall back
 * to being inlined as `ClasspathEntry.Project(inlinedAs = "libs/<name>.jar")` so the bundle is
 * still self-contained for offline use.
 *
 * With [embedDeps] (`-PbundleEmbedDeps=true`, schema-v3 `resolution = "embedded"`) the kept
 * Maven-resolved deps are instead carried inside the bundle's `libs/` as `ClasspathEntry.Embedded`,
 * producing a larger but fully self-contained artefact that a player can render with no network and
 * no consumer build system — the "pass it to a colleague" mode.
 *
 * # Closure walk
 *
 * Classpath minimization is driven by ClassGraph's inter-class dependency map. We BFS from each
 * selected preview's enclosing class FQN through every class the closure references. Module classes
 * are repacked per-class (small, ours, safe to surgically prune); third-party deps appear in
 * `report.json` with their reachability counts but only the kept ones are recorded in the
 * manifest's classpath (deps with no reachable class don't make it to the player at all).
 */
@org.gradle.api.tasks.CacheableTask
abstract class BundlePreviewTask : DefaultTask() {

  /**
   * `previews.json` produced by [DiscoverPreviewsTask]. The task reads it to (a) resolve the
   * selected ids' enclosing class FQNs (the closure entry points) and (b) write a filtered copy
   * into the bundle.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val previewsJson: RegularFileProperty

  /**
   * Consumer module's class dirs (`build/classes/kotlin/jvm/main`, etc.). Walked for per-class
   * minimization — only `.class` files for reachable FQNs land in `classes/app.jar`. Module
   * resources directory is taken separately via [moduleResourcesDir] when present.
   */
  @get:Classpath abstract val moduleClassDirs: ConfigurableFileCollection

  /**
   * The module's own compiled classes laid out as directories, sourced from AGP's scoped `PROJECT`
   * `CLASSES` artifact (`variant.artifacts.forScope(PROJECT).use(bundleTask).toGet(CLASSES, …)`).
   * Wired by the Android backend *in addition to* [moduleClassDirs], for the same reason
   * [DiscoverPreviewsTask.projectClassDirs] is: under AGP 9.x built-in Kotlin (`built_in_kotlinc`)
   * the compiled classes never land in the hardcoded `build/tmp/kotlin-classes/<variant>` directory
   * the desktop/legacy path keys off. Discovery already consumes the scoped artifact (issue #1924),
   * so packing it here keeps the bundle's class set from being *narrower* than what discovery wrote
   * into `previews.json` — a preview whose class is resolved only from a scoped dir would otherwise
   * appear in the manifest but be missing from `classes/app.jar` (issue #1926). These dirs are
   * scanned for the closure walk and per-class minimization exactly like [moduleClassDirs]; any
   * overlap dedupes by relative class path. Optional / empty on non-Android backends.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassDirs: ListProperty<Directory>

  /**
   * The module's own compiled classes packaged as jars, the jar half of AGP's scoped `PROJECT`
   * `CLASSES` artifact (see [projectClassDirs]). Packed as *module* classes (minimized into
   * `classes/app.jar`), NOT recorded as third-party [dependencyJars] coordinates — they are the
   * consumer's own bytecode and can't be re-resolved from Maven. Mirrors
   * [DiscoverPreviewsTask.projectClassJars], which method-walks the same jars, so bundling sees the
   * identical class set discovery does. `PROJECT`-scope `CLASSES` are directories in practice, so
   * this is usually empty, but it's wired and packed so the two paths never diverge (issue #1926).
   * Optional / empty on non-Android backends.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val projectClassJars: ListProperty<RegularFile>

  /**
   * Consumer module's processed resources directory (e.g. `build/processedResources/jvm/main`).
   * Bundled wholesale alongside the minimized classes — resources are typically small, and
   * string-id references in bytecode make them hard to prune deterministically.
   */
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourcesDir: DirectoryProperty

  /**
   * Additional module resource ROOT dirs to resolve data-driven asset IR (`kind=LOTTIE` /
   * `kind=SVG`) against, tried after [moduleResourcesDir]. The Android path wires its source
   * resource roots here (`src/main/resources`, `src/commonMain/resources`,
   * `src/androidMain/resources` — the same dirs discovery scans and the JVM render classpath
   * links), because AGP does not stage java resources into the JVM `build/resources/main` dir
   * [moduleResourcesDir] probes — so without this an Android bundle drops the raw `.svg` / `.json`
   * asset even though it was discovered and rendered. Empty on desktop (the processed-resources dir
   * handles it), so this is a no-op there. `@InputFiles @Optional` so absent roots snapshot as
   * empty rather than failing the build.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourceRoots: ConfigurableFileCollection

  /**
   * Third-party runtime classpath jars. Used to drive the ClassGraph closure walk and to look up
   * source coordinates via [dependencyCoordinates] — jars themselves are NOT inlined into the
   * bundle (apart from project-dep fallbacks).
   */
  @get:Classpath abstract val dependencyJars: ConfigurableFileCollection

  /**
   * Map of `dependencyJar absolute path → coordinate string`. Encoded as a single string so it
   * round-trips through Gradle's MapProperty serialization. Format:
   * - `"maven:<group>:<artifact>:<version>:<type>"` for Maven-resolved deps (`type` is `jar` /
   *   `aar`).
   * - `"project:<gradle path>"` for local project deps (these get inlined into the bundle).
   *
   * Jars not present in this map are treated as anonymous file-collection inputs (rare — typically
   * a JBR / boot-classpath jar). They're inlined as `project`-style with a synthetic `:anon` path
   * so the player can still load them, but the manifest entry is flagged.
   */
  @get:Input abstract val dependencyCoordinates: MapProperty<String, String>

  /**
   * (v6 Android) AGP's `unit_test_config_directory` contents — specifically
   * `com/android/tools/test_config.properties`. Read at pack time **only when the bundle carries
   * protolayout (Wear tile) IR** to locate the merged resource APK + manifest the tile renderer
   * resolves its theme against on a detached daemon. Empty on desktop and on Android bundles
   * without a prior render; absent input is treated as "no Android resource carriage". See
   * [resolveAndroidResources].
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val androidUnitTestConfig: ConfigurableFileCollection

  /**
   * (v6 Android) The variant's `${variant}UnitTestRuntimeClasspath`, resolved through a **lenient**
   * `artifactView` (so AGP's `AmbiguousArtifactsFailure` on project deps exposing secondary
   * variants is skipped rather than fatal). Scanned at pack time **only when protolayout IR is
   * present** for the generated library R classes (`…/R.class`, `…/R$*.class`). With non-transitive
   * R classes, the tile renderer's `androidx.wear.protolayout.renderer.R$style` is generated only
   * into the unit-test **merged** R.jar — added to this classpath as a raw file dep *without* the
   * `artifactType=jar` attribute, so the bundle's `dependencyJars` (attribute-filtered) view drops
   * it. The collected R classes are repacked under `android/r-classes.jar` so the daemon's parent
   * (renderer) classloader can link them. Unused by the desktop path.
   */
  @get:Classpath abstract val androidUnitTestRuntimeClasspath: ConfigurableFileCollection

  /**
   * (v6 Android) The consumer module's project directory. AGP writes the `android_resource_apk` /
   * `android_merged_manifest` entries in `test_config.properties` as paths **relative to the module
   * dir** (e.g. `build/intermediates/apk_for_local_test/…`); Robolectric resolves them against the
   * unit-test working directory (the module dir). [resolveAndroidResources] resolves them the same
   * way. `@Internal` — it's only a resolution base; the carried bytes are content-tracked via
   * [androidUnitTestConfig] / [androidUnitTestRuntimeClasspath].
   */
  @get:Internal abstract val moduleProjectDir: DirectoryProperty

  /**
   * Renders directory from the preceding `composePreviewRender` task. Each selected preview's PNG
   * is read from here: the cover is prepended to the polyglot, and every selected preview is baked
   * into `previews/<id>.png`. When missing or empty, the cover falls back to a stub gray PNG so the
   * bundle is still well-formed (and `file(1)` still reports PNG).
   *
   * Marked `@Internal` because this is the *root* used for path resolution in the action, and the
   * dir may legitimately not exist (bundling without a prior render is a supported flow); tracking
   * it as an `@InputDirectory @Optional` errors out when Gradle resolves the property to a path on
   * disk that doesn't yet exist. The render *contents* are tracked separately via [renderFiles] so
   * the task's up-to-date / cache keys do change when the PNGs appear or change.
   */
  @get:Internal abstract val rendersDir: DirectoryProperty

  /**
   * The render PNGs under [rendersDir], tracked as a proper input so up-to-date checks and the
   * build cache key reflect them. Without this, the bundle could be skipped/restored stale: someone
   * packs before rendering (or restores such a cached bundle), then renders and re-packs with the
   * same manifest/classes, and `composePreviewBundle` would otherwise see unchanged inputs and keep
   * the render-less bundle despite fresh PNGs on disk (Codex review, PR #1627).
   *
   * Modelled as an `@InputFiles` collection rather than `@InputDirectory` precisely so an absent
   * `renders/` dir snapshots as empty instead of failing the build — the reason [rendersDir] itself
   * had to stay `@Internal`.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val renderFiles: ConfigurableFileCollection

  /**
   * The per-sheet catalog-token sidecars under `<rendersDir>/../data/catalog-tokens/`, tracked as a
   * real input for the same reason as [renderFiles]: they live OUTSIDE the `renders/` tree, so
   * without this the task could be UP-TO-DATE / restored FROM-CACHE and keep emitting a bundle with
   * a missing or stale `previews/<id>.catalog.json` when a sidecar is created or updated after a
   * render-less pack, with no PNG change to invalidate the cache key (Codex review, PR #2172).
   * `@InputFiles @Optional` so an absent `data/catalog-tokens/` dir snapshots as empty rather than
   * failing the build.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val catalogTokenFiles: ConfigurableFileCollection

  /**
   * Preview ids to include. First entry is the cover. Empty means "all previews in the manifest";
   * passing the empty list intentionally — most callers will populate this from CLI input.
   */
  @get:Input abstract val previewIds: ListProperty<String>

  /** Gradle module path, recorded into the bundle for forensics. */
  @get:Input abstract val modulePath: Property<String>

  /** Producer-version string for diagnostics, e.g. "compose-preview $BUNDLE_VERSION". */
  @get:Input abstract val producedBy: Property<String>

  /** Backend identifier. v1 = "desktop". */
  @get:Input abstract val backend: Property<String>

  /**
   * Embed-deps mode (`-PbundleEmbedDeps=true`, schema-v3 `resolution = "embedded"`). When true,
   * every *kept* third-party dependency jar is carried **inside** the bundle under `libs/` as a
   * [ClasspathEntry.Embedded] entry instead of being referenced by Maven coordinate. The result is
   * a larger but fully self-contained bundle that a player can render with no network and no
   * consumer build system — the "pass it to a colleague" mode. Project-local deps (no coordinate)
   * are inlined regardless; this flag only changes how Maven-resolved deps are carried. Defaults to
   * false so the normal pack stays small and `resolution = "coordinates"`.
   */
  @get:Input @get:Optional abstract val embedDeps: Property<Boolean>

  /**
   * Include-data-extensions mode (`-PbundleIncludeDataExtensions=true`, schema-v7
   * [BundleManifest.dataExtensions]). When true, the per-extension data reports (a11y findings,
   * theme tokens, drawn strings, …) — those named by `previews.json`'s `dataExtensionReports` plus
   * a conventional-path fallback for registered extensions that write a report without stamping the
   * manifest ([CONVENTIONAL_DATA_EXTENSION_REPORTS], e.g. a11y's `accessibility.json`) — are packed
   * under `extensions/<id>.json`, sliced to the cover preview, so a detached reader can surface
   * them without re-rendering. Defaults to false: the normal pack carries no reports and stays
   * small. A no-op when no report is found on disk.
   */
  @get:Input @get:Optional abstract val includeDataExtensions: Property<Boolean>

  /**
   * The data-extension report sidecars named by `previews.json`'s `dataExtensionReports`, tracked
   * as a real input so the bundle re-packs (and its cache key changes) when a report's *content*
   * changes, not just when the manifest pointer does. Wired to the top-level `*.json` files under
   * the preview output dir (where the render task writes the aggregated reports); the paths are
   * resolved from the manifest at pack time against [previewsJson]'s parent. `@Optional` because a
   * pack with no extension reports — the common case — snapshots this as empty. Only read when
   * [includeDataExtensions] is set.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val dataExtensionFiles: ConfigurableFileCollection

  /** Output `.png` polyglot file. */
  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun pack() {
    val manifestFile = previewsJson.get().asFile
    val manifest = JSON.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
    val selected = resolveSelection(manifest, previewIds.get())
    val coverId = selected.first().id

    // Previews whose flavour emitted a serialisable intermediate representation (Remote Compose doc
    // / Wear protolayout proto) during the render step are replayed from that IR, not by re-running
    // their composable, so their consumer *bytecode* must not be packed. But the player still needs
    // their third-party dependencies on the replay classpath (the Compose / protolayout / Remote
    // Compose runtime the IR inflates against). So the minimisation runs two closures from one
    // scan:
    //  - the DEP closure seeds from every selected preview, so deps reachable from an IR preview
    // are
    //    still recorded as Maven coordinates (carriage — the bundle stays replayable);
    //  - the PACK closure seeds only from non-IR previews, so only their module classes land in
    //    `classes/app.jar`. An IR preview contributes no module bytecode.
    // With no IR previews the two seeds are identical and this is byte-for-byte the old behaviour.
    val irByPreview: Map<String, ResolvedIr> =
      selected.mapNotNull { p -> resolvePreviewIr(p)?.let { p.id to it } }.toMap()

    // An IR preview replays through a *player* its own bytecode never references — a protolayout
    // tile through `TileRenderer`, a Remote Compose doc through `RemoteDocumentPlayer` — so the
    // preview's closure alone wouldn't keep the renderer/player libs. Seed the dep closure from
    // each
    // format's player entry points: the BFS then reaches those libs + their transitive runtime, and
    // since deps are kept as whole coordinates whenever any class is reachable, they're recorded as
    // carriage coordinates. The libs are on the consumer's runtime classpath already
    // (`AndroidPreviewSupport` injects `tiles-renderer`; an RC consumer depends on the player), so
    // their jars are in the scan; without these seeds they'd be pruned (reachable == 0) and the
    // daemon replay would `NoClassDefFoundError`. A missing entry FQN (jar absent) seeds nothing,
    // so
    // each is a no-op unless that format's IR is actually present.
    val replayEntrySeeds = buildSet {
      if (irByPreview.values.any { it.format == IR_FORMAT_PROTOLAYOUT })
        addAll(PROTOLAYOUT_REPLAY_ENTRY_FQNS)
      if (irByPreview.values.any { it.format == IR_FORMAT_REMOTECOMPOSE })
        addAll(REMOTECOMPOSE_REPLAY_ENTRY_FQNS)
    }

    val depSeedFqns = selected.map { it.className }.toSet() + replayEntrySeeds
    val packSeedFqns = selected.filter { it.id !in irByPreview }.map { it.className }.toSet()

    // Union the legacy hardcoded `moduleClassDirs` with AGP's scoped PROJECT CLASSES (dirs + jars)
    // so the packed class set matches discovery's, which already consumes the scoped artifact
    // (#1924). Without this, a preview whose class is resolved only from a scoped element would be
    // in `previews.json` but absent from `classes/app.jar` (#1926). Scoped project jars are the
    // module's OWN bytecode, so they're packed as module classes — not recorded as dependency
    // coordinates the way `dependencyJars` are.
    val scopedClassDirs = projectClassDirs.getOrElse(emptyList()).map { it.asFile }
    val scopedClassJars =
      projectClassJars
        .getOrElse(emptyList())
        .map { it.asFile }
        .filter { it.isFile && it.name.endsWith(".jar") }
    val classDirsList =
      (moduleClassDirs.files + scopedClassDirs).filter { it.exists() && it.isDirectory }.distinct()
    val jarsList = dependencyJars.files.filter { it.isFile && it.name.endsWith(".jar") }
    // Scoped project jars join the closure scan so reachability is computed over them too, but they
    // are kept separate from `jarsList` (dependency jars) so `buildDepDecisions` never mistakes the
    // module's own jar for a third-party coordinate.
    val scanPaths = (classDirsList + jarsList + scopedClassJars).map { it.absolutePath }

    val closure = closureWalk(scanPaths, depSeed = depSeedFqns, packSeed = packSeedFqns)

    val moduleClassFqns =
      collectClassFqns(classDirsList) + collectClassFqnsFromJars(scopedClassJars)
    val reachableModuleClasses = moduleClassFqns intersect closure.packReachable
    val keptModuleClassFiles =
      packModuleClasses(classDirsList, reachableModuleClasses) +
        packModuleClassesFromJars(scopedClassJars, reachableModuleClasses)
    // Pack the module's runtime resources into the jar so a bundle can be *re-rendered* live (the
    // daemon composes the real `@Preview`, which may load a classpath resource — e.g.
    // `/fonts/*.ttf`
    // in a theme's static initializer). [moduleResourcesDir] is a single processed-resources dir
    // resolved by a config-time filesystem probe, so on a clean configuration-cached build it can
    // snapshot as null before `processResources` runs; [moduleResourceRoots] is an execution-time
    // [ConfigurableFileCollection] wired to those same dirs, so it reliably carries them. Pack both
    // (deduped) — either alone would leave the bundle classes-only and break live re-render.
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

    // Lay out the IR artefacts under `ir/<id>.<ext>` and record a manifest entry per IR-backed
    // preview. The companion resources proto (protolayout) lands beside the layout proto.
    val irEntries = mutableListOf<BundleIr>()
    val irZipFiles = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      val ir = irByPreview[preview.id] ?: continue
      val irPath = "$BUNDLE_IR_DIR/${preview.id}.${ir.ext}"
      irZipFiles[irPath] = ir.bytes
      val resourcesPath =
        ir.resourcesBytes?.let { rb ->
          val rp = "$BUNDLE_IR_DIR/${preview.id}.${ir.resourcesExt}"
          irZipFiles[rp] = rb
          rp
        }
      irEntries +=
        BundleIr(
          previewId = preview.id,
          format = ir.format,
          path = irPath,
          resourcesPath = resourcesPath,
        )
    }

    // Android resource carriage: pack the AGP-built merged resource APK + manifest (+ generated
    // library R classes) under `android/` for ANY Android bundle. Both a Wear-tile IR replay (via
    // `TileRenderer`, which links the library `R$style`) AND a classic `@Preview` that calls
    // `stringResource(R.string.…)` need the app's `0x7f` resource table at detached-render time — a
    // detached daemon has neither the merged table nor those R classes. Added to `irZipFiles`,
    // written verbatim by `buildZip`. `resolveAndroidResources` no-ops (returns null) when there's
    // no
    // prior render / no binary resources, so desktop and render-less packs stay unchanged.
    val androidResources =
      if (backend.get() == "android") resolveAndroidResources(irZipFiles) else null

    // v7 optional data-extension carriage: when asked, pack the per-extension report sidecars (a11y
    // findings, theme tokens, …) so a detached reader can surface that data without re-rendering.
    // The
    // report is sliced down to the COVER (default) preview — the one shown as the bundle's leading
    // PNG — so the headline image carries its detailed results and the bundle doesn't drag along
    // data
    // for previews it doesn't even show (see [scopeReportToCoverPreview]). The set of reports is
    // the
    // manifest's `dataExtensionReports` pointers plus a conventional-path fallback for any
    // registered
    // extension the manifest names no report for ([CONVENTIONAL_DATA_EXTENSION_REPORTS]) — the
    // standard a11y flow writes `accessibility.json` but leaves the manifest map empty, so without
    // the fallback the flag would carry nothing. Paths are module-relative from the manifest's
    // parent
    // dir (where the render task writes the reports). A manifest-named report that's missing warns
    // and
    // skips; a fallback only contributes when its file actually exists. Sorted by id for a
    // deterministic (reproducible) zip order. No-op when the flag is off or no report is found.
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
        dataExtensionZipFiles[zipPath] = scopeReportToCoverPreview(src.readBytes(), coverId)
        dataExtensionEntries += BundleDataExtension(extensionId = id, path = zipPath)
      }
    }

    val bundle =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = backend.get(),
        previewIds = selected.map { it.id },
        coverPreviewId = coverId,
        classpath = classpathEntries,
        modulePath = modulePath.get(),
        producedBy = producedBy.get(),
        producer = PRODUCER_GRADLE,
        resolution = classpath.resolution,
        intermediateRepresentations = irEntries,
        androidResources = androidResources,
        dataExtensions = dataExtensionEntries,
      )

    // Bake one PNG per selected preview into `previews/<id>.png` so the bundle renders detached
    // from its project (see [PreviewBundleFormat]). Previews whose render is missing on disk are
    // simply omitted — the reader treats an absent entry as "not rendered yet".
    val previewPngs = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewPng(preview)?.let { previewPngs[preview.id] = it }
    }

    // (v8) Per-preview override sidecars: the editable knobs a preview declared via
    // `previewOverride*`,
    // captured during the render as `renders/<stem>.overrides.json`. Packed verbatim under
    // `previews/<id>.overrides.json` so a detached viewer can present the controls. Absent for
    // previews
    // that declared none.
    val overrideFiles = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewOverrides(preview)?.let {
        overrideFiles["$BUNDLE_PREVIEWS_DIR/${preview.id}.$BUNDLE_OVERRIDES_SIDECAR_EXT"] = it
      }
    }

    // Per-sheet catalog-token sidecars (issue #2167): the resolved `@ColorCatalog` /
    // `@TypographyCatalog` values — and, per #2179, each `@ThemeCatalog` theme's live resolved
    // role/type table keyed by theme — the renderer wrote under
    // `data/catalog-tokens/<id>.catalog.json`,
    // packed by convention under `previews/<id>.catalog.json` — same shape as the override sidecars
    // so a detached reader (design-parity's `catalog-export`) can import the palette / type scale
    // without re-rendering. Only `PreviewKind.CATALOG` and `THEME_CATALOG` sheets carry one (the
    // gate lives in `resolvePreviewCatalogTokens`).
    val catalogTokenEntries = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewCatalogTokens(preview)?.let {
        catalogTokenEntries[
          "$BUNDLE_PREVIEWS_DIR/${preview.id}.$BUNDLE_CATALOG_TOKENS_SIDECAR_EXT"] = it
      }
    }

    val filteredManifest =
      if (includeDataExtensions.getOrElse(false)) {
        // When carrying extension data, rewrite the bundled manifest's `dataExtensionReports` to
        // the
        // in-bundle `extensions/<id>.json` paths (bundle-root-relative, the same convention the map
        // documents). Otherwise the bundled `previews.json` would still name the producer's
        // module-relative paths (e.g. `accessibility.json`) — pointers that don't resolve inside a
        // detached bundle and disagree with `bundle.json`'s [BundleManifest.dataExtensions].
        // Reports
        // that were skipped (source missing) drop out of the map so nothing dangles. Left untouched
        // in the default (non-carrying) pack, preserving the historical on-disk pointers.
        manifest.copy(
          previews = selected,
          dataExtensionReports = dataExtensionEntries.associate { it.extensionId to it.path },
        )
      } else {
        manifest.copy(previews = selected)
      }
    val zipBytes =
      buildZip(
        bundleJson = JSON.encodeToString(BundleManifest.serializer(), bundle),
        previewsJson = JSON.encodeToString(PreviewManifest.serializer(), filteredManifest),
        appJar = appJarBytes,
        inlinedProjectJars = inlinedJars,
        report = JSON.encodeToString(MinimizationReport.serializer(), report),
        previewPngs = previewPngs,
        irFiles = irZipFiles,
        dataExtensionFiles = dataExtensionZipFiles,
        overrideFiles = overrideFiles,
        catalogTokenFiles = catalogTokenEntries,
      )

    // The cover (first selected preview) forms the polyglot's leading bytes. Reuse its baked PNG
    // when present so the front image and `previews/<coverId>.png` are byte-identical; otherwise a
    // stub gray placeholder keeps the file a well-formed PNG.
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

    // Bundles are meant to be small and shareable — a detached `coordinates` pack is typically
    // ~100 KB. Embedding (`--embed-deps`) trades that for offline self-containment, but a fat
    // bundle
    // defeats the "paste it into a chat" point. Warn past a soft threshold so embedding stays a
    // deliberate, rare choice rather than an accidental 50 MB artefact.
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
   * The rendered PNG bytes for [preview]'s primary capture, or null when no render exists on disk
   * (bundling without a prior `composePreviewRender`, or a preview that failed to render). Used
   * both for the cover (first selected) and to bake every selected preview into
   * `previews/<id>.png`.
   *
   * Only **PNG** bytes are ever returned: the result is used verbatim as the polyglot's leading
   * cover and as `previews/<id>.png`, and [extractZipBytes] rejects a file whose leading signature
   * is neither PNG nor ZIP. A preview whose primary capture is a GIF (`@AnimatedPreview`,
   * `@FocusedPreview(gif = true)`) therefore must NOT have its `.gif` bytes read as the cover —
   * that would produce an unreadable bundle. Such a preview falls through to the PNG-sibling search
   * and, failing that, to the stub gray cover.
   */
  private fun resolvePreviewPng(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    // `renderOutput` is relative to the compose-previews ROOT (the parent of `renders/`), e.g.
    // `renders/<id>.png` — or `svg-renders/<id>.png` / `lottie-renders/<id>.png` for the JVM asset
    // passes whose disjoint output dirs keep them build-cacheable. Resolve against that root (not
    // the
    // `renders/` leaf), the same way the renderer resolves `renderOutput`, so a cover living in a
    // sibling subdir isn't missed and silently replaced by the stub gray cover.
    val previewsRoot = rendersRoot.parentFile ?: rendersRoot
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val primary = File(previewsRoot, rel)
    val subdir = primary.parentFile ?: rendersRoot
    val name = primary.name
    val base = name.substringBeforeLast('.')

    // Only read the primary-capture file directly when it's a PNG. A GIF (or any non-PNG) primary
    // capture is skipped here so its bytes never become the cover; the sibling search below is
    // already PNG-filtered.
    if (name.endsWith(".png") && primary.isFile && primary.length() > 0) return primary.readBytes()

    // No usable PNG at the primary capture's path: @PreviewParameter / multi-variant previews fan
    // out into siblings (`<base>_<param>.png`, `<base>--<dimension>.png`) in the same subdir. Bake
    // the first sibling as a representative cover so the preview isn't silently dropped.
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

  /** A captured intermediate representation resolved off disk for one preview. */
  private data class ResolvedIr(
    val format: String,
    val ext: String,
    val bytes: ByteArray,
    val resourcesExt: String? = null,
    val resourcesBytes: ByteArray? = null,
  )

  /**
   * Look for a captured IR sidecar emitted by the render step next to [preview]'s PNG. The render
   * path writes the IR alongside the rendered image using the same stem: `<stem>.rcdoc` for a
   * Remote Compose document, or `<stem>.tilelayout` (+ optional `<stem>.tileresources`) for a Wear
   * protolayout proto. Returns `null` when the preview's flavour has no IR (the common case — every
   * plain `@Composable @Preview`), in which case the preview stays on the class-minimisation path.
   *
   * Resolution mirrors [resolvePreviewPng]: the stem comes from the primary capture's
   * `renderOutput`, and the file lives under [rendersDir]. IR sidecars are NOT fanned out across
   * `@PreviewParameter` variants (a tile / remote document is a single artefact), so unlike the PNG
   * path there's no sibling search.
   */
  /**
   * Look for the per-preview override sidecar the render step wrote next to [preview]'s PNG
   * (`renders/<stem>.overrides.json`) — the serialized `compose/overrides` payload of the editable
   * knobs the preview declared via `previewOverride*`. Resolution mirrors [resolvePreviewIr]: the
   * stem comes from the primary capture's `renderOutput`, the file lives under [rendersDir].
   * Returns the raw bytes (copied verbatim into the bundle — the producer never parses them) or
   * `null` when the preview declared no knobs (the common case).
   */
  private fun resolvePreviewOverrides(preview: PreviewInfo): ByteArray? {
    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val stem = rel.substringAfterLast('/').removeSuffix(".png")
    val f = File(rendersRoot, "$stem.$BUNDLE_OVERRIDES_SIDECAR_EXT")
    return if (f.isFile && f.length() > 0) f.readBytes() else null
  }

  /**
   * Look for the per-sheet catalog-token sidecar the render step wrote for a `PreviewKind.CATALOG`
   * (issue #2167) or `PreviewKind.THEME_CATALOG` (issue #2179) [preview]
   * (`<rendersRoot>/../data/catalog-tokens/<id>.catalog.json`). Unlike the override / IR sidecars,
   * it lives under the `data/` tree keyed by the sheet id (not the PNG stem), so resolution mirrors
   * the renderer's `CatalogTokenSidecar` path + sanitize. Returns the raw bytes (copied verbatim —
   * the producer never parses them) or `null` for previews of any other kind and sheets that
   * resolved no tokens.
   */
  private fun resolvePreviewCatalogTokens(preview: PreviewInfo): ByteArray? {
    if (
      preview.params.kind != PreviewKind.CATALOG && preview.params.kind != PreviewKind.THEME_CATALOG
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
   * Resolve a data-driven asset preview's IR — the raw asset file itself ([PreviewKind.LOTTIE] /
   * [PreviewKind.SVG]), read off the module resources by the path discovery recorded on
   * [PreviewParams.assetPath]. Tries [moduleResourcesDir] (the desktop processed-resources dir)
   * first, then each root in [moduleResourceRoots] (the Android source resource dirs), returning
   * the first that holds a non-empty file at the asset path. Returns `null` when the asset has no
   * path or isn't found under any root — the bundle then omits the IR rather than failing.
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
    // kind=LOTTIE / kind=SVG: the IR is the discovered asset file itself (no render-time capture).
    // Read it straight off the module resources by the path discovery recorded, so the bundle
    // carries the animation / artwork and replays it with zero consumer bytecode — same
    // self-contained shape as a captured Remote Compose document.
    when (preview.params.kind) {
      PreviewKind.LOTTIE -> return resolveAssetIr(preview, IR_FORMAT_LOTTIE, defaultExt = "json")
      PreviewKind.SVG -> return resolveAssetIr(preview, IR_FORMAT_SVG, defaultExt = "svg")
      else -> {}
    }

    val rendersRoot = rendersDir.orNull?.asFile ?: return null
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val stem = rel.substringAfterLast('/').removeSuffix(".png")

    fun read(ext: String): ByteArray? {
      val f = File(rendersRoot, "$stem.$ext")
      return if (f.isFile && f.length() > 0) f.readBytes() else null
    }

    read(IR_EXT_REMOTECOMPOSE)?.let {
      return ResolvedIr(format = IR_FORMAT_REMOTECOMPOSE, ext = IR_EXT_REMOTECOMPOSE, bytes = it)
    }
    read(IR_EXT_PROTOLAYOUT_LAYOUT)?.let { layout ->
      return ResolvedIr(
        format = IR_FORMAT_PROTOLAYOUT,
        ext = IR_EXT_PROTOLAYOUT_LAYOUT,
        bytes = layout,
        resourcesExt = IR_EXT_PROTOLAYOUT_RESOURCES,
        resourcesBytes = read(IR_EXT_PROTOLAYOUT_RESOURCES),
      )
    }
    return null
  }

  /**
   * Build the v6 Android resource carriage for a protolayout-IR bundle and add its artefacts to
   * [zipFiles] (which [buildZip] writes verbatim). Mirrors the render path's reliance on AGP's
   * generated `com/android/tools/test_config.properties` (see `AndroidPreviewSupport`): we read it
   * to locate the merged resource APK (`apk-for-local-test.ap_`) + merged manifest the tile
   * renderer resolves its theme against, and pack the generated library R classes so
   * `androidx.wear.protolayout.renderer.R$style` links on the detached daemon.
   *
   * Returns null (adding nothing) when the inputs aren't available — bundling without a prior
   * render, a project that doesn't emit binary resources, etc. — so the bundle stays well-formed
   * and the daemon simply falls back to its pre-v6 behaviour (tile replay then fails the same way
   * it did before this carriage existed) rather than the pack crashing.
   */
  private fun resolveAndroidResources(
    zipFiles: LinkedHashMap<String, ByteArray>
  ): BundleAndroidResources? {
    // `com/android/tools/test_config.properties` lives nested inside AGP's unit-test config
    // directory, so we must walk the input as a file *tree* — a plain `ConfigurableFileCollection`
    // `.files` returns the registered directory entry (`…/out`), never the nested file. Try the
    // dedicated config input first (small), then fall back to the unit-test runtime classpath,
    // which
    // AGP also puts the config directory on and which is guaranteed built (it's a `@Classpath`
    // input with real task dependencies).
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
    // AGP writes these paths **relative to the module dir** (e.g.
    // `build/intermediates/apk_for_local_test/…`); Robolectric resolves them against the unit-test
    // working directory (the module dir). Resolve the same way — a plain `File(path)` resolves
    // against the build's CWD and misses. Absolute paths (older AGP) pass through; fall back to the
    // test_config's own module root (ancestor before `/build/`).
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
    zipFiles[ANDROID_RESOURCE_APK_PATH] = apkFile.readBytes()
    zipFiles[ANDROID_MERGED_MANIFEST_PATH] = manifestFile.readBytes()
    val rClassesJar = packAndroidRClasses()
    if (rClassesJar != null) zipFiles[ANDROID_R_CLASSES_JAR_PATH] = rClassesJar
    logger.lifecycle(
      "composePreviewBundle — carried Android resources for detached render " +
        "(apk=${apkFile.length()}B, manifest=${manifestFile.length()}B, " +
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
   * Collect the generated R classes from the unit-test runtime classpath (+ [dependencyJars]) and
   * repack them into a single jar's bytes. AGP generates these (a library's R fields are non-final,
   * so `R.style.X` compiles to a real `getstatic` on the `R$style` *class*, which an AAR's
   * published `classes.jar` does not contain). With non-transitive R classes the tile renderer's
   * `androidx.wear.protolayout.renderer.R$style` is generated only into the unit-test **merged**
   * R.jar — a raw file dep on `…UnitTestRuntimeClasspath` *without* the `artifactType=jar`
   * attribute, so the attribute-filtered [dependencyJars] view drops it; hence we also scan
   * [androidUnitTestRuntimeClasspath] (resolved leniently to dodge AGP's
   * `AmbiguousArtifactsFailure`). We keep every `…/R.class` and `…/R$*.class` across all jars
   * (deduped by entry name) — R classes are tiny leaf data holders, so carrying the lot is cheaper
   * than guessing which library the renderer needs. Returns null when none are found.
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

  /**
   * Jar-form counterpart of [collectClassFqns] for scoped PROJECT-CLASSES jars
   * ([projectClassJars]). Returns the FQN of every `.class` entry so the caller can intersect
   * against the closure's pack-reachable set, exactly as it does for the directory-backed module
   * classes.
   */
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
   * Jar-form counterpart of [packModuleClasses]: extracts the `.class` entries whose FQN is in
   * [reachable] from each scoped PROJECT-CLASSES jar, keyed by entry name (the same `pkg/Foo.class`
   * relative path the directory packer emits) so they merge cleanly into `classes/app.jar`.
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
  private data class AssembledClasspath(
    val entries: List<ClasspathEntry>,
    val inlinedJars: Map<String, File>,
    val resolution: String,
  )

  /**
   * Build the manifest classpath from the kept dependency decisions.
   * - Project-local deps (no Maven coordinate) are always inlined under `libs/` as
   *   [ClasspathEntry.Project] — they can't be re-resolved.
   * - Maven-resolved deps are referenced by [ClasspathEntry.Maven] coordinate (the small default),
   *   OR, when [embed] is true, inlined under `libs/` as [ClasspathEntry.Embedded] so the bundle
   *   needs no resolver at open time.
   *
   * `resolution` is derived honestly from the result: `embedded` when every kept Maven dep was
   * carried in `libs/`, `mixed` when some Maven deps are embedded and others referenced (it isn't
   * today, but the field stays accurate if that changes), else `coordinates`.
   */
  private fun assembleClasspath(
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
        // Maven-resolved dep, default mode: reference by coordinate (with a content hash so the
        // detached bytes can be re-attached and verified from any source).
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
   * Parse `"<group>:<artifact>:<version>:<type>"` (the post-prefix shape produced by the plugin
   * registration's `ResolvedArtifactResult` → coord encoder). Falls back to `type = "jar"` when the
   * coordinate omits the trailing packaging. When [src] (the resolved jar on disk) is provided, its
   * SHA-256 is recorded so a player can verify the bytes after re-resolving the coordinate from any
   * source; [src] = null leaves the entry resolvable-but-unverifiable.
   */
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
      // Track written entries so a resource present in more than one root dir (or already a class)
      // is packed exactly once — a duplicate zip entry is invalid.
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
    appJar: ByteArray,
    inlinedProjectJars: Map<String, File>,
    report: String,
    previewPngs: Map<String, ByteArray>,
    irFiles: Map<String, ByteArray>,
    dataExtensionFiles: Map<String, ByteArray>,
    overrideFiles: Map<String, ByteArray>,
    catalogTokenFiles: Map<String, ByteArray>,
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.writeFile("bundle.json", bundleJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("previews.json", previewsJson.toByteArray(Charsets.UTF_8))
      // One baked PNG per selected preview under the well-known `previews/` directory.
      previewPngs.forEach { (id, bytes) -> zip.writeFile("$BUNDLE_PREVIEWS_DIR/$id.png", bytes) }
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
   * Slice an aggregated per-extension report down to just the cover (default) preview — the one
   * shown as the bundle's leading PNG — so the carried `extensions/<id>.json` describes the
   * headline image and not every preview the module rendered.
   *
   * Applied uniformly to every report: any top-level array whose elements are all JSON objects
   * carrying a string `previewId` is filtered to the entries whose `previewId` equals [coverId];
   * everything else in the document is left untouched. This keys on the common
   * `entries[].previewId` convention (e.g. the a11y report's `entries`) as a generic structural
   * transform — it is NOT per-extension logic, so a report that doesn't follow the convention is
   * carried whole. Returns the input bytes unchanged on a parse failure, a non-object root, or when
   * no array matched the shape (so a report with nothing to scope is byte-identical to the source).
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
    // Pin every entry to a fixed epoch so the bundle is byte-identical across builds. Without
    // this, `ZipEntry.time` defaults to `System.currentTimeMillis()` and same-inputs-same-bundle
    // produces different bytes every run — useless for the build cache and noisy under content
    // hashing (e.g. when CI compares an uploaded bundle against a baseline). 1980-01-01 is the
    // DOS-epoch floor that the ZIP format can represent; matching what most reproducible-build
    // tooling (Bazel, mvn-shade, gradle-shadow) uses.
    val entry = ZipEntry(path)
    entry.time = ZIP_DOS_EPOCH_MS
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  /**
   * Two project deps can resolve to jars with the same basename; dedupe by suffixing a counter.
   * Used only for the rare project-dep inline path — Maven coords don't collide because the
   * resolver guarantees a unique (group, artifact, version) per file.
   */
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
        // Dependency reachability (which jars contributed a reachable class) is computed against
        // the
        // dep closure so an IR preview's third-party deps are still recorded — see the carriage
        // note
        // in `pack`.
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
   * BFS over ClassGraph's inter-class dependency map from [seed]'s compilation units. Kotlin
   * top-level functions live on `FooKt` and their generated companions
   * (`ComposableSingletons$FooKt`, `FooKt$lambda-1`, …) share the prefix, so we seed every class
   * whose name equals or is `$`-prefixed by an entry FQN — cheap insurance against a missing
   * inner-class edge. Returns every reachable class name.
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
    val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
      classDiscriminator = "kind"
    }

    /**
     * Player entry points seeded into the dependency closure when a bundle carries protolayout IR,
     * so the renderer runtime the daemon replays through ([TilePreviewRenderer]'s `TileRenderer`
     * path) is carried as coordinates even though no tile preview references it. `TileRenderer`
     * pulls `protolayout-renderer` + the proto runtime transitively, and whole coordinates are kept
     * when any class is reachable, so this single entry carries the lot.
     */
    val PROTOLAYOUT_REPLAY_ENTRY_FQNS = setOf("androidx.wear.tiles.renderer.TileRenderer")

    /**
     * Player entry points seeded when a bundle carries Remote Compose IR, so the alpha player the
     * daemon replays through (`:data-remotecompose-connector`'s `RemoteComposeIrReplay`) is carried
     * as coordinates. The RC preview's bytecode references the creation/tooling APIs, not the
     * player; `RemoteDocument` (remote-player-core) + `RemoteDocumentPlayer`
     * (remote-player-compose) pull the rest of the player runtime transitively.
     */
    val REMOTECOMPOSE_REPLAY_ENTRY_FQNS =
      setOf(
        "androidx.compose.remote.player.core.RemoteDocument",
        "androidx.compose.remote.player.compose.RemoteDocumentPlayerKt",
      )

    /**
     * Soft size ceiling above which an embed-deps pack warns. 25 MB is comfortably above a normal
     * embedded Compose graph (a few MB) but well under the "nobody pastes this into a chat" range —
     * enough to flag an accidental fat bundle without failing the build.
     */
    const val EMBED_SIZE_WARN_BYTES: Long = 25_000_000L

    /**
     * Fixed timestamp stamped onto every ZIP entry produced by [writeFile]. 1980-01-01T00:00:00
     * local time is the DOS-epoch floor the ZIP format can represent (anything earlier round-trips
     * through `ZipEntry` as a different value); matching the reproducible-build floor used by
     * Bazel, gradle-shadow, and mvn-shade. Hardcoded rather than `0` because the ZIP format
     * silently clamps timestamps below the DOS epoch, which would silently make the chosen constant
     * a lie.
     */
    val ZIP_DOS_EPOCH_MS: Long =
      java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis

    /**
     * 1×1 gray PNG built on demand. Used as the cover when no rendered PNG is available — file(1)
     * still reports PNG and viewers render a single neutral pixel, conveying "no render yet".
     */
    val STUB_GRAY_PNG: ByteArray by lazy {
      val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, 0x808080)
      val baos = ByteArrayOutputStream()
      ImageIO.write(img, "png", baos)
      baos.toByteArray()
    }
  }
}
