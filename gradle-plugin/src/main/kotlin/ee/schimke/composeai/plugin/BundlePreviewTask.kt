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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
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
   * Consumer module's processed resources directory (e.g. `build/processedResources/jvm/main`).
   * Bundled wholesale alongside the minimized classes — resources are typically small, and
   * string-id references in bytecode make them hard to prune deterministically.
   */
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourcesDir: DirectoryProperty

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

    val classDirsList = moduleClassDirs.files.filter { it.exists() && it.isDirectory }
    val jarsList = dependencyJars.files.filter { it.isFile && it.name.endsWith(".jar") }
    val scanPaths = (classDirsList + jarsList).map { it.absolutePath }

    val closure = closureWalk(scanPaths, depSeed = depSeedFqns, packSeed = packSeedFqns)

    val moduleClassFqns = collectClassFqns(classDirsList)
    val reachableModuleClasses = moduleClassFqns intersect closure.packReachable
    val keptModuleClassFiles = packModuleClasses(classDirsList, reachableModuleClasses)
    val appJarBytes = buildJar(keptModuleClassFiles, moduleResourcesDir.orNull?.asFile)

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

    // v6 Android resource carriage: a protolayout (Wear tile) IR replays through `TileRenderer`,
    // which resolves a library theme resource and links the non-final library `R$style` class —
    // neither of which a detached daemon has. When the bundle carries protolayout IR, pack the
    // AGP-built merged resource APK + manifest and the generated library R classes under `android/`
    // (added to `irZipFiles`, written verbatim by `buildZip`). No-op for desktop / non-protolayout.
    val androidResources =
      if (irByPreview.values.any { it.format == IR_FORMAT_PROTOLAYOUT })
        resolveAndroidResources(irZipFiles)
      else null

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
      )

    // Bake one PNG per selected preview into `previews/<id>.png` so the bundle renders detached
    // from its project (see [PreviewBundleFormat]). Previews whose render is missing on disk are
    // simply omitted — the reader treats an absent entry as "not rendered yet".
    val previewPngs = LinkedHashMap<String, ByteArray>()
    for (preview in selected) {
      resolvePreviewPng(preview)?.let { previewPngs[preview.id] = it }
    }

    val filteredManifest = manifest.copy(previews = selected)
    val zipBytes =
      buildZip(
        bundleJson = JSON.encodeToString(BundleManifest.serializer(), bundle),
        previewsJson = JSON.encodeToString(PreviewManifest.serializer(), filteredManifest),
        appJar = appJarBytes,
        inlinedProjectJars = inlinedJars,
        report = JSON.encodeToString(MinimizationReport.serializer(), report),
        previewPngs = previewPngs,
        irFiles = irZipFiles,
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
    // `renderOutput` is module-relative under `build/compose-previews/`, e.g. `renders/<id>.png`.
    // The rendersDir input points at the `renders/` dir itself.
    val rel =
      preview.captures.firstOrNull()?.renderOutput?.takeIf { it.isNotEmpty() } ?: return null
    val name = rel.substringAfterLast('/')
    val base = name.substringBeforeLast('.')

    // Only read the primary-capture file directly when it's a PNG. A GIF (or any non-PNG) primary
    // capture is skipped here so its bytes never become the cover; the sibling search below is
    // already PNG-filtered.
    if (name.endsWith(".png")) {
      val exact = File(rendersRoot, name)
      if (exact.isFile && exact.length() > 0) return exact.readBytes()
    }

    // No usable PNG at the primary capture's path: @PreviewParameter / multi-variant previews fan
    // out into siblings (`<base>_<param>.png`, `<base>--<dimension>.png`). Bake the first sibling
    // as
    // a representative cover so the preview isn't silently dropped from the bundle.
    if (!rendersRoot.isDirectory) return null
    return rendersRoot
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
  private fun resolvePreviewIr(preview: PreviewInfo): ResolvedIr? {
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
    // directory, so we must walk the input as a file *tree* — a plain
    // `ConfigurableFileCollection.files`
    // returns the registered directory entry (`…/out`), never the nested file, which silently
    // produced an empty carriage (issue: tile replay still ClassNotFound'd `R$style`). Try the
    // dedicated config input first (small), then fall back to the unit-test runtime classpath,
    // which
    // AGP also puts the config directory on and which is guaranteed built (it's a `@Classpath`
    // input with real task dependencies).
    // Pack-time diagnostic threaded into the bundle (android/diag.txt) and surfaced by the player —
    // the pack runs inside Gradle whose CI console is truncated, so this is the only reliable way
    // to
    // see WHY the carriage did or didn't happen. Records input shape + the resolved paths.
    val diag = StringBuilder()
    val configEntries =
      runCatching { androidUnitTestConfig.files.map { it.name } }
        .getOrElse { listOf("<error:${it.message}>") }
    val runtimeCpCount = runCatching { androidUnitTestRuntimeClasspath.files.size }.getOrElse { -1 }
    diag.appendLine("protolayout=true")
    diag.appendLine("androidUnitTestConfig.entries=$configEntries")
    diag.appendLine("androidUnitTestRuntimeClasspath.entryCount=$runtimeCpCount")
    fun emitDiag() {
      zipFiles[ANDROID_DIAG_PATH] = diag.toString().toByteArray(Charsets.UTF_8)
    }

    val configFile =
      sequenceOf(androidUnitTestConfig, androidUnitTestRuntimeClasspath)
        .flatMap { it.asFileTree.files.asSequence() }
        .firstOrNull { it.isFile && it.name == "test_config.properties" }
    diag.appendLine("testConfigFound=${configFile != null} path=${configFile?.absolutePath}")
    if (configFile == null) {
      emitDiag()
      logger.warn(
        "composePreviewBundle: protolayout IR present but no test_config.properties on the " +
          "unit-test config / runtime-classpath inputs — tile replay on a detached daemon can't " +
          "resolve resources. Run composePreviewRender first so AGP generates it."
      )
      return null
    }
    val props = Properties().apply { configFile.inputStream().use { load(it) } }
    val apkPath = props.getProperty("android_resource_apk")?.trim().orEmpty()
    val manifestPath = props.getProperty("android_merged_manifest")?.trim().orEmpty()
    val pkg = props.getProperty("android_custom_package")?.trim()?.takeIf { it.isNotEmpty() }
    val apkFile = apkPath.takeIf { it.isNotEmpty() }?.let { File(it) }
    val manifestFile = manifestPath.takeIf { it.isNotEmpty() }?.let { File(it) }
    diag.appendLine("android_resource_apk='$apkPath' exists=${apkFile?.isFile == true}")
    diag.appendLine(
      "android_merged_manifest='$manifestPath' exists=${manifestFile?.isFile == true}"
    )
    diag.appendLine("android_custom_package=$pkg")
    if (apkFile == null || !apkFile.isFile || manifestFile == null || !manifestFile.isFile) {
      emitDiag()
      logger.warn(
        "composePreviewBundle: protolayout IR present but the merged resource APK / manifest from " +
          "test_config.properties is missing (apk='$apkPath', manifest='$manifestPath') — tile " +
          "replay on a detached daemon can't resolve resources."
      )
      return null
    }
    zipFiles[ANDROID_RESOURCE_APK_PATH] = apkFile.readBytes()
    zipFiles[ANDROID_MERGED_MANIFEST_PATH] = manifestFile.readBytes()
    val rClassesJar = packAndroidRClasses()
    if (rClassesJar != null) zipFiles[ANDROID_R_CLASSES_JAR_PATH] = rClassesJar
    diag.appendLine("rClassesBytes=${rClassesJar?.size ?: 0}")
    emitDiag()
    logger.lifecycle(
      "composePreviewBundle — carried Android resources for protolayout replay " +
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

  private fun buildJar(classes: Map<String, ByteArray>, resourcesDir: File?): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      classes.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      if (resourcesDir != null && resourcesDir.isDirectory) {
        resourcesDir
          .walkTopDown()
          .filter { it.isFile }
          .forEach { f ->
            val rel = f.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
            if (rel !in classes) zip.writeFile(rel, f.readBytes())
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
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.writeFile("bundle.json", bundleJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("previews.json", previewsJson.toByteArray(Charsets.UTF_8))
      // One baked PNG per selected preview under the well-known `previews/` directory.
      previewPngs.forEach { (id, bytes) -> zip.writeFile("$BUNDLE_PREVIEWS_DIR/$id.png", bytes) }
      // Captured IR bytes (Remote Compose doc / protolayout proto) under `ir/`.
      irFiles.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      zip.writeFile("classes/app.jar", appJar)
      inlinedProjectJars.forEach { (path, file) -> zip.writeFile(path, file.readBytes()) }
      zip.writeFile("report.json", report.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
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
