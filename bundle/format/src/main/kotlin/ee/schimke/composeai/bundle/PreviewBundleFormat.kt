package ee.schimke.composeai.bundle

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
 * Bundle directory holding an optional self-contained web embed (`web/index.html`,
 * `web/compose-preview-embed.js`, and `web/previews/<id>.png` in external-image mode), written by
 * `bundle embed --in-bundle`. Ignored by older readers, the renderer and the daemon.
 */
public const val BUNDLE_WEB_DIR: String = "web"

/**
 * Well-known directory inside a bundle zip holding the per-preview baked PNGs (`previews/<id>.png`)
 * and, when packed with `--with-semantics`, each preview's semantics sidecar
 * (`previews/<id>.semantics.json`). Mirrors `BUNDLE_PREVIEWS_DIR` in `:gradle-plugin`.
 */
public const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Suffix for the per-preview `compose/semantics`
 * [ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload] beside `previews/<id>.png`
 * (issue #1843). The bundle copy uses the payload's stable refs instead of per-process node ids.
 */
public const val BUNDLE_SEMANTICS_SUFFIX: String = ".semantics.json"

/**
 * Suffix for the per-preview `layout/inspector`
 * [ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload] beside `previews/<id>.png`:
 * the full LayoutNode tree, for redlines the semantics tree can't express. Uses stable ids.
 */
public const val BUNDLE_LAYOUT_SUFFIX: String = ".layout.json"

/**
 * Suffix for the per-preview `fonts/used` [ee.schimke.composeai.data.fonts.FontsUsedPayload], from
 * which the design-catalog export generates the Wasm tier's `fonts.json`.
 */
public const val BUNDLE_FONTS_SUFFIX: String = ".fonts.json"

/**
 * Suffix for the per-preview editable `compose/figma-svg`
 * [ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct] export.
 */
public const val BUNDLE_FIGMA_SVG_SUFFIX: String = ".figma.svg"

/**
 * Directory suffix for a hybrid figma-svg's per-node raster crops
 * (`previews/<id>.figma-raster/<node>.png`), mirroring the SVG's `<image>` hrefs.
 */
public const val BUNDLE_FIGMA_RASTER_DIR_SUFFIX: String = ".figma-raster"

/**
 * Suffix for the figma-svg export's font-warning sidecar (`compose-figma-fonts.warnings.json`).
 * Only written for a degraded preview whose text shipped as missing-glyph boxes.
 */
public const val BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX: String = ".figma-fonts.warnings.json"

/** [injectSidecarsIntoBundle] for `previews/<id>.semantics.json`. */
public fun injectSemanticsIntoBundle(
  bundleFile: File,
  semanticsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, semanticsById, BUNDLE_SEMANTICS_SUFFIX, fileSystem)

/** [injectSidecarsIntoBundle] for `previews/<id>.layout.json`. */
public fun injectLayoutIntoBundle(
  bundleFile: File,
  layoutById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, layoutById, BUNDLE_LAYOUT_SUFFIX, fileSystem)

/** [injectSidecarsIntoBundle] for `previews/<id>.fonts.json`. */
public fun injectFontsIntoBundle(
  bundleFile: File,
  fontsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, fontsById, BUNDLE_FONTS_SUFFIX, fileSystem)

/** [injectSidecarsIntoBundle] for `previews/<id>.figma.svg`. */
public fun injectFigmaSvgIntoBundle(
  bundleFile: File,
  figmaSvgById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, figmaSvgById, BUNDLE_FIGMA_SVG_SUFFIX, fileSystem)

/** [injectSidecarsIntoBundle] for `previews/<id>.figma-fonts.warnings.json`. */
public fun injectFigmaFontWarningsIntoBundle(
  bundleFile: File,
  warningsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int =
  injectSidecarsIntoBundle(bundleFile, warningsById, BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX, fileSystem)

/**
 * Inject a hybrid figma-svg's per-node raster crops (preview id → crop filename → PNG bytes) as
 * `previews/<id>.figma-raster/<node>.png`. Returns the number of crops written.
 *
 * Crops are bounded to [maxEdgePx] (the serve host downsamples to that anyway, and full-size crops
 * can exceed the 25MiB fetch cap); already-bounded crops pass unchanged so re-packing is
 * byte-stable. Pass `Int.MAX_VALUE` to store crops verbatim.
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
 * Inject `previews/<id><suffix>` entries into [bundleFile]'s zip portion in place, keeping the
 * leading PNG cover and every other entry. Idempotent (same-name entries are replaced), byte-stable
 * (DOS-epoch timestamps) and atomic (temp sibling + move). Returns the number of entries written.
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
 * Inject top-level entries (posix zip path → bytes) into [bundleFile]'s zip portion in place, with
 * the same contract as [injectSidecarsIntoBundle] but verbatim paths (no `previews/` prefix) — the
 * carrier for whole-bundle sidecars like `signatures.json`. Returns the count written.
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
 * Return a copy of [existingZip] with [newEntries] (path → bytes) added or replaced, other entries
 * verbatim, new entries pinned to [ZIP_DOS_EPOCH_MS]. Operates on raw zip bytes; the caller
 * re-attaches the leading PNG.
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
 * Return a copy of [existingZip] with [webFiles] added, first dropping existing `$BUNDLE_WEB_DIR/`
 * entries so re-embedding is idempotent. DOS-epoch timestamps; raw zip bytes, as above.
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
 * The output path for `bundle embed --in-bundle`, or `null` when the caller must demand `-o`. An
 * explicit [outArg] wins; otherwise a local [inputPath] is rewritten in place. A URL input resolved
 * to a delete-on-exit temp file, so it requires an explicit output.
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
 * In-CLI mirror of the bundle's on-disk schema, re-declared rather than linking `:gradle-plugin`.
 * Keep field names in lockstep with `PreviewBundleFormat.kt` in `:gradle-plugin`.
 */
public object BundleReader {

  @Serializable
  public data class Manifest(
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
    /**
     * The producing project's directory relative to the repository root (`bundle/format`). Empty
     * for the root project and older bundles; can't be derived from the logical [modulePath].
     */
    val moduleDirectory: String = "",
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
     * re-resolve [ClasspathEntry.Maven] coordinates. See `BundleManifest.repositories` in
     * `PreviewBundleFormat.kt`.
     */
    val repositories: List<String> = emptyList(),
  )

  /** v8 mirror of `BundleExternalResource` in `PreviewBundleFormat.kt`. */
  @Serializable
  public data class ExternalResource(val path: String, val sha256: String, val size: Long)

  @Serializable
  public data class ExternalClasspath(val path: String, val sha256: String, val size: Long)

  /** v7+ mirror of `BundleDataExtension` in `PreviewBundleFormat.kt`. */
  @Serializable public data class DataExtension(val extensionId: String, val path: String)

  /** v6+ mirror of `BundleAndroidResources` in `PreviewBundleFormat.kt`. */
  @Serializable
  public data class AndroidResources(
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
  public data class BundleIr(
    val previewId: String,
    /** `remotecompose` (RC doc) or `protolayout` (Wear tile Layout proto). */
    val format: String,
    val path: String,
    val resourcesPath: String? = null,
  )

  @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
  @Serializable
  @JsonClassDiscriminator("kind")
  public sealed interface ClasspathEntry {
    @Serializable
    @kotlinx.serialization.SerialName("module")
    public data class Module(val path: String) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("maven")
    public data class Maven(
      val group: String,
      val artifact: String,
      val version: String,
      val type: String,
      /** v4+: hex SHA-256 of the artifact bytes; verify after re-resolving. Null = unverifiable. */
      val sha256: String? = null,
    ) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("project")
    public data class Project(val path: String, val inlinedAs: String) : ClasspathEntry

    /**
     * v3+: a third-party jar carried inside the bundle's `libs/` — no coordinate, no resolution.
     */
    @Serializable
    @kotlinx.serialization.SerialName("embedded")
    public data class Embedded(val inlinedAs: String) : ClasspathEntry
  }

  @Serializable
  public data class Report(
    val entryClassFqns: List<String>,
    val reachableClassCount: Int,
    val totalScannedClassCount: Int,
    val moduleClasses: ModuleClasses,
    val dependencies: List<DependencyDecision>,
  )

  @Serializable
  public data class ModuleClasses(
    val totalClasses: Int,
    val reachableClasses: Int,
    val packedBytes: Long,
  )

  @Serializable
  public data class DependencyDecision(
    val sourcePath: String,
    val coordinate: String?,
    val projectPath: String?,
    val totalClasses: Int,
    val reachableClasses: Int,
    val originalBytes: Long,
    val kept: Boolean,
  )

  public data class Metadata(val manifest: Manifest, val report: Report?)

  private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
  }

  public fun readMetadata(file: File): Metadata {
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
  public fun extractZipBytes(file: File, fileSystem: FileSystem = SystemFileSystem): ByteArray {
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
   * Extract every embedded jar under `libs/` from [zipBytes] into [libsDir], sorted by name. Only
   * embedded-mode bundles carry any.
   *
   * Entries are flattened to their basename and verified to resolve inside [libsDir] (defeats Zip
   * Slip); nested paths and directories are ignored. Shared by [BundleRenderer] and
   * [BundleDaemonCommand].
   */
  public fun extractEmbeddedLibs(
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
