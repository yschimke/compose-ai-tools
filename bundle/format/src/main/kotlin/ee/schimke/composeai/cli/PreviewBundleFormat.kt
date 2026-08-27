package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source

/**
 * Well-known directory inside a bundle zip holding an optional, self-contained web embed
 * (`web/index.html` + `web/compose-preview-embed.js`, and `web/previews/<id>.png` in external-image
 * mode). Written by `bundle embed --in-bundle`. Additive: an older reader, the renderer, and the
 * daemon all ignore it, so a bundle carrying a `web/` directory is still a valid polyglot.
 */
public const val BUNDLE_WEB_DIR: String = "web"

/**
 * Well-known directory inside a bundle zip holding the per-preview baked PNGs (`previews/<id>.png`)
 * and, when packed with `--with-semantics`, each preview's semantics sidecar
 * (`previews/<id>.semantics.json`). Mirrors `BUNDLE_PREVIEWS_DIR` in `:gradle-plugin`.
 */
public const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Suffix for the per-preview semantics blob carried beside `previews/<id>.png` (issue #1843). The
 * payload is the `compose/semantics`
 * [ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload] tree — per-node bounds,
 * label/text, and resolved foreground/background colours — the shape the design-parity static
 * bundle reader consumes as a sibling of the rendered PNG. The bundle copy replaces Compose's
 * per-process node ids with the payload's stable refs; the daemon's live data product keeps its
 * native ids for same-session joins.
 */
public const val BUNDLE_SEMANTICS_SUFFIX: String = ".semantics.json"

/**
 * Suffix for the per-preview layout-inspector blob carried beside `previews/<id>.png`. The payload
 * is the `layout/inspector` [ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload] tree
 * — the full LayoutNode walk with per-node bounds and resolved design tokens — so a consumer can
 * build slot-level redlines/wireframes the (a11y-shaped) semantics tree can't express. The bundle
 * copy uses stable structural ids for layout-only nodes and the matching stable semantics ref for
 * nodes shared with the semantics tree.
 */
public const val BUNDLE_LAYOUT_SUFFIX: String = ".layout.json"

/**
 * Suffix for the per-preview font-usage blob carried beside `previews/<id>.png`. The payload is the
 * `fonts/used` [ee.schimke.composeai.data.fonts.FontsUsedPayload] — every font resolution the
 * render made (requested vs resolved family, weight, style, fallback chain) — recorded by the
 * daemon's always-on FontsRecorderExtension. Carried so the design-catalog export can generate the
 * in-browser Wasm tier's `fonts.json` from what the previews actually resolved instead of a
 * hand-authored manifest.
 */
public const val BUNDLE_FONTS_SUFFIX: String = ".fonts.json"

/**
 * Suffix for the per-preview layered SVG carried beside `previews/<id>.png`. The payload is the
 * `compose/figma-svg` [ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct] export —
 * an editable vector (real fills/strokes/corner radii + editable text), the same bytes a
 * `data/fetch` for the figma-svg yields — baked by the daemon's always-on render. Carried so the
 * design-catalog export can ship an editable vector per sticker alongside the raster PNG.
 */
public const val BUNDLE_FIGMA_SVG_SUFFIX: String = ".figma.svg"

/**
 * Directory suffix for a hybrid figma-svg's per-node raster crops, carried beside its
 * `previews/<id>.figma.svg` as `previews/<id>.figma-raster/<node>.png`. Mirrors the
 * `figma-raster/<node>.png` hrefs the SVG's `<image>` layers reference so they resolve once the
 * design-catalog export copies the SVG (and rewrites those hrefs) onto the delivery branch. Absent
 * for a vector-only export.
 */
public const val BUNDLE_FIGMA_RASTER_DIR_SUFFIX: String = ".figma-raster"

/**
 * Inject `previews/<id>.semantics.json` entries (id → `compose-semantics.json` bytes) into
 * [bundleFile]'s zip portion **in place**, preserving the leading PNG cover and every existing
 * entry. Re-injecting replaces any prior semantics entry for the same id, so a second
 * `--with-semantics` pack is idempotent. New entries are pinned to the DOS epoch so the enriched
 * bundle stays byte-stable. Written via a temp sibling + atomic move so a failure never truncates
 * the bundle. Returns the number of entries written.
 */
public fun injectSemanticsIntoBundle(
  bundleFile: File,
  semanticsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, semanticsById, BUNDLE_SEMANTICS_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.layout.json` entries (id → `layout-inspector.json` bytes) into [bundleFile]
 * — the full LayoutNode tree (per-node bounds + resolved design tokens) the daemon bakes alongside
 * the semantics blob. Carried so consumers can build slot-level redlines/wireframes the a11y
 * semantics tree can't express. Same in-place, idempotent, byte-stable contract as
 * [injectSemanticsIntoBundle]. Returns the number of entries written.
 */
public fun injectLayoutIntoBundle(
  bundleFile: File,
  layoutById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, layoutById, BUNDLE_LAYOUT_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.fonts.json` entries (id → `fonts-used.json` bytes) into [bundleFile] — the
 * per-preview `fonts/used` record the daemon bakes alongside the semantics blob. Carried so the
 * design-catalog export can generate the in-browser tier's font manifest from recorded usage. Same
 * in-place, idempotent, byte-stable contract as [injectSemanticsIntoBundle]. Returns the number of
 * entries written.
 */
public fun injectFontsIntoBundle(
  bundleFile: File,
  fontsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, fontsById, BUNDLE_FONTS_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.figma.svg` entries (id → `compose-figma.svg` bytes) into [bundleFile] — the
 * layered editable `compose/figma-svg` export the daemon bakes alongside the semantics blob.
 * Carried so the design-catalog export can ship an editable vector per sticker next to the raster
 * PNG. Same in-place, idempotent, byte-stable contract as [injectSemanticsIntoBundle]. Returns the
 * number of entries written.
 */
public fun injectFigmaSvgIntoBundle(
  bundleFile: File,
  figmaSvgById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, figmaSvgById, BUNDLE_FIGMA_SVG_SUFFIX, fileSystem)

/**
 * Inject a hybrid figma-svg's per-node raster crops ([figmaRasterById]: preview id → (crop filename
 * → PNG bytes)) into [bundleFile] as `previews/<id>.figma-raster/<node>.png`, so the SVG's `<image
 * href="figma-raster/<node>.png">` layers resolve after the export carries them. Same in-place,
 * idempotent, byte-stable contract as the other injectors. Returns the number of crops written
 * across all previews.
 *
 * Each crop is bounded to [maxEdgePx] on its longest edge on the way in. Crops arrive at device
 * resolution, and the only consumer — the serve host inlining them into a self-contained figma-svg
 * — has always downsampled to exactly this bound before serving them, so the extra pixels were
 * carried across the network and then discarded. They are not free: a handful of full-screen photo
 * crops pushed Jetchat's live bundle to 27MB, past the serve host's 25MiB per-file fetch cap, and
 * the catalog silently degraded to baked PNGs. Pass `Int.MAX_VALUE` to store crops verbatim.
 *
 * Byte-stability is preserved: [downscaleRaster] returns the original bytes unchanged for a crop
 * already within the bound (the common case — component-sized crops), for one that fails to decode,
 * and for one whose re-encode wouldn't actually shrink it. So re-packing an already-bounded bundle
 * writes identical entries.
 */
public fun injectFigmaRasterIntoBundle(
  bundleFile: File,
  figmaRasterById: Map<String, Map<String, ByteArray>>,
  fileSystem: FileSystem = SystemFileSystem,
  maxEdgePx: Int = MAX_FIGMA_RASTER_EDGE_PX,
): Int {
  val entries =
    figmaRasterById.entries
      .flatMap { (id, crops) ->
        crops.map { (name, bytes) ->
          "$BUNDLE_PREVIEWS_DIR/$id$BUNDLE_FIGMA_RASTER_DIR_SUFFIX/$name" to
            downscaleRaster(bytes, maxEdgePx)
        }
      }
      .toMap()
  return injectRawZipEntries(bundleFile, entries, fileSystem)
}

/**
 * Inject `previews/<id><suffix>` entries (id → bytes) into [bundleFile]'s zip portion **in place**,
 * preserving the leading PNG cover and every existing entry. Re-injecting replaces any prior entry
 * for the same id+suffix, so a second pack is idempotent. New entries are pinned to the DOS epoch
 * so the enriched bundle stays byte-stable. Written via a temp sibling + atomic move so a failure
 * never truncates the bundle. Returns the number of entries written.
 */
public fun injectSidecarsIntoBundle(
  bundleFile: File,
  byId: Map<String, ByteArray>,
  suffix: String,
  fileSystem: FileSystem = SystemFileSystem,
): Int {
  if (byId.isEmpty()) return 0
  val full = fileSystem.read(bundleFile.path.toPath()) { readByteArray() }
  val zip = BundleReader.extractZipBytes(bundleFile)
  // The appended zip is a suffix of the file; everything before it is the leading PNG cover.
  val prefix = full.copyOfRange(0, full.size - zip.size)
  val entries = byId.entries.associate { (id, bytes) -> "$BUNDLE_PREVIEWS_DIR/$id$suffix" to bytes }
  val newZip = addOrReplaceZipEntries(zip, entries)

  val tmp = File(bundleFile.parentFile, "${bundleFile.name}.sidecar-tmp")
  fileSystem.write(tmp.path.toPath()) {
    write(prefix)
    write(newZip)
  }
  fileSystem.atomicMove(tmp.path.toPath(), bundleFile.path.toPath())
  return entries.size
}

/**
 * Inject arbitrary top-level entries (posix zip path → bytes) into [bundleFile]'s zip portion **in
 * place**, preserving the leading PNG cover and every existing entry; an entry with a colliding
 * path is replaced (idempotent). Unlike [injectSidecarsIntoBundle] the paths are used verbatim (no
 * `previews/` prefix), so this is the carrier for whole-bundle sidecars like `signatures.json`.
 * Same temp-sibling + atomic-move + DOS-epoch contract as the other injectors. Returns the count
 * written.
 */
public fun injectRawZipEntries(
  bundleFile: File,
  entries: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int {
  if (entries.isEmpty()) return 0
  val full = fileSystem.read(bundleFile.path.toPath()) { readByteArray() }
  val zip = BundleReader.extractZipBytes(bundleFile)
  val prefix = full.copyOfRange(0, full.size - zip.size)
  val newZip = addOrReplaceZipEntries(zip, entries)
  val tmp = File(bundleFile.parentFile, "${bundleFile.name}.rawentry-tmp")
  fileSystem.write(tmp.path.toPath()) {
    write(prefix)
    write(newZip)
  }
  fileSystem.atomicMove(tmp.path.toPath(), bundleFile.path.toPath())
  return entries.size
}

/** Replace and remove raw zip entries while preserving the bundle's leading PNG cover. */
public fun rewriteRawZipEntries(
  bundleFile: File,
  entries: Map<String, ByteArray>,
  removals: Set<String>,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val full = fileSystem.read(bundleFile.path.toPath()) { readByteArray() }
  val zip = BundleReader.extractZipBytes(bundleFile, fileSystem)
  val prefix = full.copyOfRange(0, full.size - zip.size)
  val newZip = addOrReplaceZipEntries(zip, entries, removals)
  val tmp = File(bundleFile.parentFile, "${bundleFile.name}.rewrite-tmp")
  fileSystem.write(tmp.path.toPath()) {
    write(prefix)
    write(newZip)
  }
  fileSystem.atomicMove(tmp.path.toPath(), bundleFile.path.toPath())
}

/**
 * Return a copy of [existingZip] with [newEntries] (path → bytes) added, replacing any existing
 * entry with the same name (so the operation is idempotent). Every other original entry is
 * preserved verbatim. New entries are pinned to [ZIP_DOS_EPOCH_MS] for reproducibility. Operates on
 * raw zip bytes — the caller re-attaches the polyglot's leading PNG.
 */
public fun addOrReplaceZipEntries(
  existingZip: ByteArray,
  newEntries: Map<String, ByteArray>,
  removedEntries: Set<String> = emptySet(),
): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zout ->
    ZipInputStream(ByteArrayInputStream(existingZip)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && entry.name !in newEntries && entry.name !in removedEntries) {
          zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
          zin.copyTo(zout)
          zout.closeEntry()
        }
        zin.closeEntry()
      }
    }
    for ((path, bytes) in newEntries) {
      zout.putNextEntry(ZipEntry(path).apply { time = ZIP_DOS_EPOCH_MS })
      zout.write(bytes)
      zout.closeEntry()
    }
  }
  return baos.toByteArray()
}

/**
 * Return a copy of [existingZip] with [webFiles] (path → bytes) added. Every original entry is
 * preserved except ones already under `$BUNDLE_WEB_DIR/`, which are dropped first so re-embedding
 * is idempotent (no duplicate `web/…` entries on a second run). New entries are pinned to the DOS
 * epoch so the result is reproducible. Operates on raw zip bytes — the caller re-attaches the
 * polyglot's leading PNG.
 */
public fun embedWebIntoZip(existingZip: ByteArray, webFiles: Map<String, ByteArray>): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zout ->
    ZipInputStream(ByteArrayInputStream(existingZip)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && !entry.name.startsWith("$BUNDLE_WEB_DIR/")) {
          zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
          zin.copyTo(zout)
          zout.closeEntry()
        }
        zin.closeEntry()
      }
    }
    for ((path, bytes) in webFiles) {
      zout.putNextEntry(ZipEntry(path).apply { time = ZIP_DOS_EPOCH_MS })
      zout.write(bytes)
      zout.closeEntry()
    }
  }
  return baos.toByteArray()
}

/**
 * 1980-01-01 DOS-epoch floor stamped on entries written by [embedWebIntoZip], matching the plugin's
 * reproducible-bundle writer so an enriched bundle stays byte-stable across runs.
 */
public val ZIP_DOS_EPOCH_MS: Long =
  java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis

/**
 * The output path for `bundle embed --in-bundle`, or `null` when the caller must error and demand
 * `-o`. An explicit [outArg] always wins. Otherwise we default to rewriting [inputPath] in place —
 * but only for a *local* input: a URL input ([sourceIsUrl]) resolved to a delete-on-exit temp file,
 * and rewriting that "in place" would lose the enriched bundle on exit, so we refuse and require an
 * explicit output instead.
 */
public fun resolveInBundleTarget(
  outArg: String?,
  inputPath: String,
  sourceIsUrl: Boolean,
): String? =
  when {
    outArg != null -> outArg
    sourceIsUrl -> null
    else -> inputPath
  }

/**
 * In-CLI mirror of the bundle's on-disk schema. We re-declare the data classes here (rather than
 * dragging the gradle-plugin module onto the CLI's compile classpath) because the CLI links against
 * a different module graph; the schema is tiny and rarely changes.
 *
 * Keep field names in lockstep with `PreviewBundleFormat.kt` in `:gradle-plugin`.
 */
public object BundleReader {

  @Serializable
  data class Manifest(
    val schemaVersion: Int,
    val backend: String,
    val previewIds: List<String>,
    val coverPreviewId: String?,
    /**
     * Raw discovery ids parallel to [previewIds] (schema ≥ the sanitised-entry-name change). Empty
     * on older bundles — fall back to [previewIds], correct whenever no sanitising happened.
     */
    val rawPreviewIds: List<String> = emptyList(),
    val classpath: List<ClasspathEntry>,
    val modulePath: String,
    val producedBy: String,
    /** v3+: producing build system (`gradle`|`amper`|`bazel`). Defaults for v2 bundles. */
    val producer: String = "gradle",
    /** v3+: classpath assembly strategy (`coordinates`|`embedded`|`mixed`). Defaults for v2. */
    val resolution: String = "coordinates",
    /**
     * v5+: previews replayed from a captured intermediate representation (`ir/<id>.<ext>`) rather
     * than by re-running their consumer bytecode. Empty for a classic all-classes bundle.
     */
    val intermediateRepresentations: List<BundleIr> = emptyList(),
    /**
     * v6+: Android resource carriage for protolayout (Wear tile) IR replay — the merged resource
     * APK + manifest + generated R classes under `android/`. Null for desktop / non-protolayout
     * bundles. See `BundleAndroidResources` in `PreviewBundleFormat.kt`.
     */
    val androidResources: AndroidResources? = null,
    /**
     * v7+: optional per-extension data reports carried under `extensions/<id>.json` (a11y findings,
     * theme tokens, …). Empty unless the bundle was packed with `--include-data-extensions`. See
     * `BundleDataExtension` in `PreviewBundleFormat.kt`.
     */
    val dataExtensions: List<DataExtension> = emptyList(),
    /**
     * v8 post-pack: resources lifted out of `classes/app.jar` by `bundle externalize` and fetched
     * on demand (fonts, …). Empty on a normal self-contained bundle. See `BundleExternalResource`
     * in `PreviewBundleFormat.kt`.
     */
    val externalResources: List<ExternalResource> = emptyList(),
    /** Whole classpath entries supplied by the sibling content-addressed pool. */
    val externalClasspath: List<ExternalClasspath> = emptyList(),
    /**
     * v9+: extra Maven repository base URLs (beyond Maven Central / Google Maven) needed to
     * re-resolve this bundle's [ClasspathEntry.Maven] coordinates — a JitPack fork, an internal
     * mirror, an androidx.dev snapshot build. Empty on a pre-v9 bundle and on any module whose deps
     * all live on the two defaults. See `BundleManifest.repositories` in `PreviewBundleFormat.kt`.
     */
    val repositories: List<String> = emptyList(),
  )

  /** v8 mirror of `BundleExternalResource` in `PreviewBundleFormat.kt`. */
  @Serializable data class ExternalResource(val path: String, val sha256: String, val size: Long)

  @Serializable data class ExternalClasspath(val path: String, val sha256: String, val size: Long)

  /** v7+ mirror of `BundleDataExtension` in `PreviewBundleFormat.kt`. */
  @Serializable data class DataExtension(val extensionId: String, val path: String)

  /** v6+ mirror of `BundleAndroidResources` in `PreviewBundleFormat.kt`. */
  @Serializable
  data class AndroidResources(
    val resourceApkPath: String,
    val mergedManifestPath: String,
    val rClassesJarPath: String? = null,
    /**
     * Consumer application package; written as `android_custom_package` in the synthesized config.
     */
    val applicationPackage: String? = null,
  )

  /** v5+ mirror of `BundleIr` in `PreviewBundleFormat.kt`. */
  @Serializable
  data class BundleIr(
    val previewId: String,
    /** `remotecompose` (RC doc) or `protolayout` (Wear tile Layout proto). */
    val format: String,
    val path: String,
    val resourcesPath: String? = null,
  )

  @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
  @Serializable
  @JsonClassDiscriminator("kind")
  sealed interface ClasspathEntry {
    @Serializable
    @kotlinx.serialization.SerialName("module")
    data class Module(val path: String) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("maven")
    data class Maven(
      val group: String,
      val artifact: String,
      val version: String,
      val type: String,
      /** v4+: hex SHA-256 of the artifact bytes; verify after re-resolving. Null = unverifiable. */
      val sha256: String? = null,
    ) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("project")
    data class Project(val path: String, val inlinedAs: String) : ClasspathEntry

    /**
     * v3+: a third-party jar carried inside the bundle's `libs/` — no coordinate, no resolution.
     */
    @Serializable
    @kotlinx.serialization.SerialName("embedded")
    data class Embedded(val inlinedAs: String) : ClasspathEntry
  }

  @Serializable
  data class Report(
    val entryClassFqns: List<String>,
    val reachableClassCount: Int,
    val totalScannedClassCount: Int,
    val moduleClasses: ModuleClasses,
    val dependencies: List<DependencyDecision>,
  )

  @Serializable
  data class ModuleClasses(val totalClasses: Int, val reachableClasses: Int, val packedBytes: Long)

  @Serializable
  data class DependencyDecision(
    val sourcePath: String,
    val coordinate: String?,
    val projectPath: String?,
    val totalClasses: Int,
    val reachableClasses: Int,
    val originalBytes: Long,
    val kept: Boolean,
  )

  data class Metadata(val manifest: Manifest, val report: Report?)

  private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
  }

  fun readMetadata(file: File): Metadata {
    val zipBytes = extractZipBytes(file)
    var manifest: Manifest? = null
    var report: Report? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        when (entry.name) {
          "bundle.json" ->
            manifest =
              json.decodeFromString(Manifest.serializer(), zin.readBytes().toString(Charsets.UTF_8))
          "report.json" ->
            report =
              json.decodeFromString(Report.serializer(), zin.readBytes().toString(Charsets.UTF_8))
        }
        zin.closeEntry()
      }
    }
    return Metadata(
      manifest = manifest ?: throw IllegalArgumentException("bundle.json missing in ${file.path}"),
      report = report,
    )
  }

  /** Polyglot-aware zip extraction; mirrors [extractZipBytes] in the plugin module. */
  fun extractZipBytes(file: File, fileSystem: FileSystem = SystemFileSystem): ByteArray {
    val bytes = fileSystem.read(file.path.toPath()) { readByteArray() }
    if (bytes.size < 8) {
      throw IllegalArgumentException("not a bundle: ${file.path} is too small (${bytes.size}B)")
    }
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    if (isPngSignature(bytes)) {
      val zipStart = pngLength(bytes)
      return bytes.copyOfRange(zipStart, bytes.size)
    }
    throw IllegalArgumentException(
      "not a bundle: ${file.path} — leading bytes match neither PNG nor ZIP"
    )
  }

  private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  private fun isPngSignature(bytes: ByteArray): Boolean {
    if (bytes.size < PNG_SIG.size) return false
    for (i in PNG_SIG.indices) if (bytes[i] != PNG_SIG[i]) return false
    return true
  }

  private fun pngLength(bytes: ByteArray): Int {
    var offset = PNG_SIG.size
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return offset
    }
    throw IllegalArgumentException("truncated PNG: IEND not found before EOF")
  }

  /**
   * Extract every embedded jar under `libs/` from a bundle's [zipBytes] into [libsDir], returning
   * the written jar files sorted by name (stable classpath order). Embedded-mode bundles (schema-v3
   * `resolution = "embedded"`) carry their reachable third-party deps here; coordinate bundles
   * carry none, so this returns an empty list.
   *
   * Each entry is flattened to its basename under [libsDir] and the resolved path is verified to
   * live inside [libsDir] — defeats Zip Slip (`../` traversal) on a hostile bundle. Nested paths
   * and directory entries are ignored. Shared by [BundleRenderer] and [BundleDaemonCommand] so the
   * two player paths extract identically.
   */
  fun extractEmbeddedLibs(
    zipBytes: ByteArray,
    libsDir: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<File> {
    libsDir.mkdirs()
    val canonicalLibs = libsDir.canonicalFile
    val written = mutableListOf<File>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        if (!entry.isDirectory && name.startsWith("libs/") && name.endsWith(".jar")) {
          val dest = File(libsDir, File(name).name).canonicalFile
          if (dest.path.startsWith(canonicalLibs.path + File.separator)) {
            fileSystem.write(dest.path.toPath()) { writeAll(zin.source()) }
            written += dest
          }
        }
        zin.closeEntry()
      }
    }
    return written.sortedBy { it.name }
  }
}
