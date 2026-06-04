package ee.schimke.composeai.plugin

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * On-disk format for `compose-preview` bundles — portable, self-contained artefacts that record one
 * or more `@Preview` composables together with the **minimal** classpath needed to re-render them.
 *
 * # File shape
 *
 * The bundle is a **PNG + ZIP polyglot**:
 * 1. Bytes `0..n` are a valid PNG (the cover image — the first selected preview's rendered output,
 *    or a stub gray placeholder). Finder, Preview.app, browsers, GitHub, Slack — every PNG viewer
 *    renders the leading image. This is the bundle's **default** preview: the one thing every plain
 *    image viewer shows.
 * 2. Bytes `n+1..EOF` are a standard ZIP archive. ZIP parsers scan backwards from EOF for the
 *    End-Of-Central-Directory signature (`PK\x05\x06`), so the leading PNG bytes are invisible to
 *    them. `unzip foo.png` works.
 *
 * `file(1)` reports "PNG image data". The same file opened by `compose-preview bundle open` (or the
 * VS Code extension) extracts the appended zip and rehydrates the preview.
 *
 * # ZIP layout
 *
 * ```
 * bundle.json              — manifest (this file's [BundleManifest])
 * previews.json            — filtered to selected preview ids; same shape as the original
 * previews/<id>.png        — the rendered PNG for EACH selected preview (see [BUNDLE_PREVIEWS_DIR]).
 *                            The cover's leading-bytes PNG is mirrored here under its own id so
 *                            iterating the well-known directory yields every preview uniformly.
 *                            A preview with no render on disk is simply absent from this directory.
 * classes/app.jar          — consumer module bytecode, MINIMIZED to classes reachable from the
 *                            selected previews (plus all module resources). For an IR-backed
 *                            preview (see below) the enclosing class is NOT a closure seed, so its
 *                            bytecode is omitted unless some other preview reaches it.
 * libs/<name>.jar          — third-party / project jars carried IN the bundle. Present only for
 *                            [ClasspathEntry.Project] fallbacks and, since v3, [ClasspathEntry.Embedded]
 *                            entries (`resolution = "embedded"` / `"mixed"`). Absent for a pure
 *                            `coordinates` pack.
 * ir/<id>.<ext>            — (v5) the captured **intermediate representation** for a preview whose
 *                            flavour has one: a Remote Compose document byte stream
 *                            (`<id>.rcdoc`) or a Wear protolayout `Layout` proto (`<id>.tilelayout`,
 *                            with the companion resources proto as `<id>.tileresources`). A player
 *                            replays the IR directly through the Remote Compose / ProtoLayout
 *                            runtime — it needs neither the consumer's `@Preview` bytecode nor the
 *                            full Compose graph that produced it. See [BundleIr] and
 *                            [BundleManifest.intermediateRepresentations].
 * report.json              — [MinimizationReport]: which deps contributed reachable classes
 * ```
 *
 * # Multiple previews, detached from a project
 *
 * The leading PNG is a *single* default image, but a bundle can carry many previews. Their rendered
 * PNGs are baked into the well-known [BUNDLE_PREVIEWS_DIR] directory so a reader can show every
 * preview **without re-rendering and without the originating Gradle project on disk** — the bundle
 * is fully self-describing when opened from `~/Downloads`, a chat attachment, or a gist. The
 * `classes/app.jar` + classpath are still present for tooling that wants a *live* re-render (the VS
 * Code panel, the desktop daemon), but they are no longer required just to look at the images.
 *
 * **Dependency carriage.** In the default `resolution = "coordinates"` pack there is no `libs/`
 * directory: Maven / Google-resolvable dependencies are recorded as coordinates in
 * [BundleManifest.classpath] and the player (`compose-preview bundle open`, VS Code extension)
 * re-resolves them from the consumer's normal Gradle / Maven repos at open time. That keeps a
 * one-preview bundle ~100 KB instead of dragging the whole Compose graph in. A `resolution =
 * "embedded"` pack (and non-Gradle producers that can't emit coordinates) instead carries the
 * reachable jars in `libs/` as [ClasspathEntry.Embedded] so the bundle renders with no network and
 * no consumer build system — trading size for portability.
 *
 * For Android backends, [ClasspathEntry.Maven.type] = `"aar"` records that the player must resolve
 * the **unprocessed** AAR (not the extracted classes.jar) so AGP's artifact transforms run as they
 * would in a normal build.
 *
 * # Intermediate-representation previews (v5)
 *
 * Some preview flavours don't need their producing code re-executed to render again — they declare
 * a serialisable **intermediate representation** that a small runtime can replay on its own:
 * - **Remote Compose** (`@PreviewWrapper(RemotePreviewWrapper::class)` composables) captures a
 *   `RemoteDocument` byte stream — the "RC doc". A `RemoteDocumentPlayer` paints it back with no
 *   reference to the Kotlin that authored it.
 * - **Wear Tiles / ProtoLayout** (`@androidx.wear.tiles.tooling.preview.Preview`) produces a
 *   `LayoutElementBuilders.Layout` protobuf plus a `ResourceBuilders.Resources` proto. A
 *   `TileRenderer` inflates the proto with no reference to the `fun foo(): TilePreviewData` that
 *   built it.
 *
 * For such previews the bundle carries the IR bytes under `ir/<id>.<ext>` and records a [BundleIr]
 * in [BundleManifest.intermediateRepresentations]; the enclosing class is dropped from the
 * minimisation closure seed, so the consumer bytecode that produced it is **not** packed. The
 * player dispatches on the recorded [BundleIr.format] and replays through the Remote Compose /
 * ProtoLayout library instead of loading consumer classes. A bundle can mix IR-backed and
 * classpath-backed previews; each preview is independently either listed in
 * `intermediateRepresentations` (replayed from IR) or seeded into `classes/app.jar` (replayed by
 * re-running its composable).
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  /** Backend the bundle was packed for. v1 = "desktop"; "android" follows. */
  val backend: String,
  /** Selected preview ids (matches `previews.json[].id`). First entry = cover. */
  val previewIds: List<String>,
  /** Preview id whose PNG forms the polyglot's leading bytes. Usually `previewIds[0]`. */
  val coverPreviewId: String?,
  /**
   * Classpath in load order. First entry is always [ClasspathEntry.Module] for the inlined
   * `classes/app.jar`; remaining entries are [ClasspathEntry.Maven] coordinates the player resolves
   * at open time, [ClasspathEntry.Embedded] jars carried inside the bundle's `libs/` (no resolution
   * needed), or [ClasspathEntry.Project] fallbacks for local artifacts that had to be inlined
   * alongside the app jar.
   */
  val classpath: List<ClasspathEntry>,
  /** Source Gradle path that produced the bundle, e.g. `:samples:cmp`. */
  val modulePath: String,
  /** `BUNDLE_VERSION`-shaped identifier of the producer for diagnostics. */
  val producedBy: String,
  /**
   * Build system that produced the bundle: `"gradle"` (this plugin), `"amper"`, or `"bazel"` (the
   * contrib drivers). Informational — lets a player report provenance and pick heuristics without
   * sniffing the classpath. Defaults to `"gradle"` so a v2 bundle (which omits the field) decodes
   * as Gradle-produced.
   */
  val producer: String = PRODUCER_GRADLE,
  /**
   * How the player is expected to assemble the third-party classpath:
   * - [RESOLUTION_COORDINATES] — resolve [ClasspathEntry.Maven] entries from the consumer's repos
   *   (small bundle; the Gradle default, and the only mode a v2 bundle could express).
   * - [RESOLUTION_EMBEDDED] — everything reachable is carried in `libs/` as
   *   [ClasspathEntry.Embedded] (larger bundle, but renders with no network / no consumer build
   *   system — the portable hand-off mode).
   * - [RESOLUTION_MIXED] — coordinate-less deps embedded, the rest referenced by coordinate.
   *
   * Defaults to [RESOLUTION_COORDINATES] for v2 back-compat.
   */
  val resolution: String = RESOLUTION_COORDINATES,
  /**
   * (v5) Per-preview intermediate-representation records. Each entry names a preview that is
   * replayed from a captured IR ([BundleIr.format] = [IR_FORMAT_REMOTECOMPOSE] /
   * [IR_FORMAT_PROTOLAYOUT]) rather than by re-running its composable. A preview appears here OR
   * has its enclosing class in `classes/app.jar`, never both. Empty (the default) on a classic
   * all-classes bundle, so a v4 reader that ignores this field still decodes a v5 classpath bundle
   * correctly. See the "Intermediate-representation previews" section above.
   */
  val intermediateRepresentations: List<BundleIr> = emptyList(),
  /**
   * (v6) Android resource carriage for IR replay. Present only when the bundle carries protolayout
   * (Wear tile) IR: replaying a tile drives `TileRenderer`, which resolves the library theme
   * `androidx.wear.protolayout.renderer.R.style.ProtoLayoutBaseTheme` through `getResources()` and
   * links the non-final library `R$style` *class*. A detached daemon has neither the merged
   * resource table (no AGP build) nor the generated R classes (an AAR's published `classes.jar`
   * omits them), so without this carriage tile replay dies with `NoClassDefFoundError` on `R$style`
   * and then `Unknown resource value type 0`. The record points at the AGP-built merged resource
   * APK + manifest and the generated R classes packed under `android/`; the player rebuilds a
   * Robolectric `com/android/tools/test_config.properties` from them. `null` for desktop bundles
   * and for Android bundles with no protolayout IR (classic / Remote-Compose-only previews need
   * none). Additive — a pre-v6 reader ignores the field and the `android/` entries.
   */
  val androidResources: BundleAndroidResources? = null,
)

/**
 * (v6) Android resource artefacts carried for protolayout IR replay. See
 * [BundleManifest.androidResources]. All paths are posix zip paths inside the bundle.
 */
@Serializable
data class BundleAndroidResources(
  /**
   * Zip path of the merged resource APK (AAPT2 `apk-for-local-test.ap_`), e.g.
   * `android/resources.ap_`.
   */
  val resourceApkPath: String,
  /** Zip path of the merged `AndroidManifest.xml` Robolectric reads the package + theme from. */
  val mergedManifestPath: String,
  /**
   * Zip path of the jar holding the generated library R classes (`androidx.wear.protolayout.*.R$*`
   * etc.) the tile renderer links against, e.g. `android/r-classes.jar`. `null` when no R classes
   * were found to carry (the renderer then relies on whatever is already reachable).
   */
  val rClassesJarPath: String? = null,
  /**
   * Consumer application package (`android_custom_package`), recorded for the synthesized config.
   */
  val applicationPackage: String? = null,
)

/**
 * One preview replayed from a captured intermediate representation rather than from its consumer
 * bytecode. The player keys on [format] to pick the replay library and reads the IR bytes from
 * [path] (and [resourcesPath] for protolayout).
 */
@Serializable
data class BundleIr(
  /** Preview id this IR renders; matches an entry in [BundleManifest.previewIds]. */
  val previewId: String,
  /** IR flavour: [IR_FORMAT_REMOTECOMPOSE], [IR_FORMAT_PROTOLAYOUT], or [IR_FORMAT_LOTTIE]. */
  val format: String,
  /** Posix zip path of the IR bytes, e.g. `ir/<id>.rcdoc` or `ir/<id>.tilelayout`. */
  val path: String,
  /**
   * Posix zip path of a companion artefact the format needs, e.g. the protolayout
   * `ResourceBuilders.Resources` proto (`ir/<id>.tileresources`). `null` for formats that carry
   * everything in [path] (Remote Compose).
   */
  val resourcesPath: String? = null,
)

/** Discriminator field `kind`, values: `module`, `maven`, `project`. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ClasspathEntry {
  /** The minimized consumer-module jar inlined inside the bundle. */
  @Serializable
  @kotlinx.serialization.SerialName("module")
  data class Module(
    /** Posix relative path inside the bundle zip, e.g. `classes/app.jar`. */
    val path: String
  ) : ClasspathEntry

  /**
   * A Maven (or Google Maven, JitPack, …) coordinate the player resolves at open time. This is the
   * **canonical** way a bundle carries a third-party dependency: the bytes stay *detached* and the
   * player re-attaches them from wherever they live (Maven Central, the colleague's local Gradle /
   * Coursier cache, an internal mirror, a future content-addressable store). Embedding
   * ([ClasspathEntry.Embedded]) is only an offline fallback.
   *
   * Encoded as separate fields rather than a `group:artifact:version` string so consumers can pick
   * a subset (e.g. only allow certain groups) without re-parsing.
   */
  @Serializable
  @kotlinx.serialization.SerialName("maven")
  data class Maven(
    val group: String,
    val artifact: String,
    val version: String,
    /**
     * Packaging the player must resolve. `"jar"` for pure-JVM deps (desktop), `"aar"` for Android
     * library archives — the player resolves the unprocessed AAR so AGP can run its normal
     * artifact-transform pipeline.
     */
    val type: String,
    /**
     * Lowercase hex SHA-256 of the resolved artifact's bytes at pack time, or null when the
     * producer couldn't compute it (older bundles, non-Gradle producers). Lets a player that
     * re-attaches a *detached* dep from **any** source (Maven, a local cache, a mirror, a CAS)
     * check the fetched bytes against the bytes the bundle was built with.
     *
     * **Mismatch policy: warn, never fail.** A player MUST NOT refuse to render on a hash mismatch.
     * A different artifact for the same coordinate is usually *almost* compatible — a point-release
     * skew, a repackaged-but-equivalent jar, a stripped vs. full variant — and a preview that
     * renders slightly off is far more useful than no preview at all. So a mismatch (or a
     * missing/unverifiable hash) is a **noisy warning**: surface it loudly (which coordinate,
     * expected vs. actual hash) and proceed with the resolved bytes. There is no strict mode that
     * hard-fails. Verification is a fidelity signal, not a gate.
     */
    val sha256: String? = null,
  ) : ClasspathEntry

  /**
   * Project-local dep that had no Maven coordinate. The bundle inlines it alongside the consumer
   * jar so the artefact stays self-contained even when consumed offline.
   */
  @Serializable
  @kotlinx.serialization.SerialName("project")
  data class Project(
    /** Gradle path of the producing project, e.g. `:my-lib`. Informational. */
    val path: String,
    /** Posix relative path inside the bundle zip, e.g. `libs/my-lib.jar`. */
    val inlinedAs: String,
  ) : ClasspathEntry

  /**
   * A third-party dependency carried **inside** the bundle's `libs/` directory rather than
   * referenced by coordinate — no resolution, no network, no consumer build system needed to put it
   * on the classpath. Emitted by `--embed-deps` / `resolution = "embedded"` packs and by non-Gradle
   * producers that can't (or don't want to) express resolvable Maven coordinates. Unlike [Project],
   * an embedded entry carries no Gradle path — it's just "this jar, here".
   */
  @Serializable
  @kotlinx.serialization.SerialName("embedded")
  data class Embedded(
    /** Posix relative path inside the bundle zip, e.g. `libs/coil-compose-2.6.0.jar`. */
    val inlinedAs: String
  ) : ClasspathEntry
}

/** [BundleManifest.producer] values. */
const val PRODUCER_GRADLE: String = "gradle"

/** [BundleManifest.resolution] values. */
const val RESOLUTION_COORDINATES: String = "coordinates"

const val RESOLUTION_EMBEDDED: String = "embedded"

const val RESOLUTION_MIXED: String = "mixed"

/** [BundleIr.format] values. */
const val IR_FORMAT_REMOTECOMPOSE: String = "remotecompose"

const val IR_FORMAT_PROTOLAYOUT: String = "protolayout"

/**
 * [BundleIr.format] for a Lottie animation asset. Unlike Remote Compose / protolayout — whose IR is
 * *captured* by running a composable — a Lottie preview's IR is the asset file itself, read
 * straight off the module resources at pack time. The zip entry keeps the asset's own extension
 * (`ir/<id>.json` or `ir/<id>.lottie`); `format` is what the replayer keys on.
 */
const val IR_FORMAT_LOTTIE: String = "lottie"

/** Well-known directory inside the bundle zip holding per-preview IR bytes (`ir/<id>.<ext>`). */
const val BUNDLE_IR_DIR: String = "ir"

/** File extension for a captured Remote Compose document byte stream. */
const val IR_EXT_REMOTECOMPOSE: String = "rcdoc"

/** File extension for a captured Wear protolayout `Layout` proto. */
const val IR_EXT_PROTOLAYOUT_LAYOUT: String = "tilelayout"

/** File extension for the companion protolayout `Resources` proto. */
const val IR_EXT_PROTOLAYOUT_RESOURCES: String = "tileresources"

/** Well-known directory inside the bundle zip holding Android resource carriage (v6). */
const val BUNDLE_ANDROID_DIR: String = "android"

/** Zip path of the carried merged resource APK (v6). See [BundleAndroidResources]. */
const val ANDROID_RESOURCE_APK_PATH: String = "android/resources.ap_"

/** Zip path of the carried merged `AndroidManifest.xml` (v6). */
const val ANDROID_MERGED_MANIFEST_PATH: String = "android/AndroidManifest.xml"

/** Zip path of the carried generated R-class jar (v6). */
const val ANDROID_R_CLASSES_JAR_PATH: String = "android/r-classes.jar"

/**
 * Schema version stamped into [BundleManifest.schemaVersion].
 * - v1 — `bundle.json` + `previews.json` + `classes/app.jar` + `report.json`, cover PNG as the
 *   polyglot's leading bytes only.
 * - v2 — adds the [BUNDLE_PREVIEWS_DIR] directory: a baked PNG per selected preview so the bundle
 *   renders detached from its project. Readers gate on `>= 2` before looking for
 *   `previews/<id>.png` (v1 bundles simply have no such directory); the additive zip entries are
 *   otherwise ignored by `ignoreUnknownKeys` readers, so a v1 reader opening a v2 bundle still
 *   works.
 * - v3 — adds [BundleManifest.producer] / [BundleManifest.resolution] and the
 *   [ClasspathEntry.Embedded] kind for `libs/`-carried third-party deps (the `--embed-deps` /
 *   `resolution = "embedded"` portable-hand-off mode and non-Gradle producers). Both new manifest
 *   fields default, and `ignoreUnknownKeys` readers skip the `embedded` discriminator they don't
 *   recognise, so a v2 reader opening a v3 *coordinate* bundle still works; only the embedded jars
 *   need a v3-aware player.
 * - v4 — adds [ClasspathEntry.Maven.sha256], the content hash that makes a *detached* coordinate
 *   safe to re-attach from any source (Maven, a local cache, a mirror, a CAS): the player resolves
 *   the coordinate however it can, then verifies the bytes against the hash. Purely additive —
 *   `sha256` defaults to null, so a v3 reader opening a v4 bundle just ignores it and an older
 *   bundle reads as "unverifiable coordinate".
 * - v5 — adds [BundleManifest.intermediateRepresentations] and the `ir/` directory: previews with a
 *   serialisable IR (Remote Compose doc, Wear protolayout proto) are replayed from the IR via the
 *   matching runtime library instead of by re-running their consumer bytecode, which is then
 *   dropped from `classes/app.jar`. Additive — the field defaults to empty and `ignoreUnknownKeys`
 *   readers skip the `ir/` entries, so a v4 reader opening a v5 *classpath* bundle still works;
 *   only the IR previews need a v5-aware player.
 * - v6 — adds [BundleManifest.androidResources] and the `android/` directory: an Android bundle
 *   carrying protolayout (Wear tile) IR also carries the AGP-built merged resource APK + manifest
 *   and the generated library R classes, so a detached daemon can resolve the tile renderer's theme
 *   resource and link its `R$style` class on replay. Additive — the field defaults to null and
 *   `ignoreUnknownKeys` readers skip the `android/` entries, so a v5 reader opening a v6 bundle
 *   still works; only protolayout IR replay on a detached Android daemon needs a v6-aware player.
 */
const val BUNDLE_SCHEMA_VERSION: Int = 6

/**
 * Well-known directory inside the bundle zip holding one rendered PNG per selected preview, keyed
 * by preview id: `previews/<previewId>.png`. The cover (the polyglot's leading bytes) is mirrored
 * here under its own id so a reader can iterate this single directory to enumerate every preview.
 */
const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Diagnostic record describing how aggressive the minimization was. Always written into the bundle
 * as `report.json` so users can audit whether the closure walk was effective.
 */
@Serializable
data class MinimizationReport(
  val entryClassFqns: List<String>,
  val reachableClassCount: Int,
  val totalScannedClassCount: Int,
  val moduleClasses: ModuleClassesStats,
  /** One entry per resolved runtime dep — kept ones list as [ClasspathEntry] in the manifest. */
  val dependencies: List<DependencyDecision>,
)

@Serializable
data class ModuleClassesStats(
  val totalClasses: Int,
  val reachableClasses: Int,
  val packedBytes: Long,
)

@Serializable
data class DependencyDecision(
  /** Original absolute path the dep resolved to (jar file). Useful for forensic comparison. */
  val sourcePath: String,
  /**
   * Maven coordinate string `group:artifact:version[:type]` when known; `null` for project deps.
   */
  val coordinate: String?,
  /** Gradle project path for project deps; `null` for Maven deps. */
  val projectPath: String?,
  val totalClasses: Int,
  val reachableClasses: Int,
  val originalBytes: Long,
  /** `true` when the dep contributed at least one class to the closure (and is in `classpath`). */
  val kept: Boolean,
)

/**
 * Writes a PNG + ZIP polyglot. The leading bytes are [coverPng] verbatim; the appended bytes are
 * [zipBytes] verbatim. Both inputs must already be valid in their respective formats; this writer
 * does not reframe chunks or rewrite the zip's central directory.
 *
 * Most image viewers and zip readers tolerate trailing/leading extra bytes respectively, so the raw
 * concatenation is enough to satisfy both formats. ZIP's End-Of-Central-Directory record is
 * searched from EOF (which is in the appended zip), and PNG's chunk loop terminates at the IEND
 * record (which is inside [coverPng]). See: <https://en.wikipedia.org/wiki/Polyglot_(computing)>.
 */
internal fun writePngZipPolyglot(coverPng: ByteArray, zipBytes: ByteArray, out: File) {
  out.parentFile?.mkdirs()
  out.outputStream().use { stream ->
    stream.write(coverPng)
    stream.write(zipBytes)
  }
}

/**
 * Reads a bundle file produced by [writePngZipPolyglot] (or a plain `.zip`) and returns the zip
 * bytes. Detects the PNG signature on the leading bytes and seeks past the IEND chunk to find the
 * zip start; plain zips (signature `PK\x03\x04`) are returned as-is.
 *
 * Throws [IllegalArgumentException] when neither signature is found.
 */
internal fun extractZipBytes(file: File): ByteArray {
  val bytes = file.readBytes()
  if (bytes.size < 8) {
    throw IllegalArgumentException("not a bundle: ${file.path} is too small (${bytes.size}B)")
  }
  if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
    return bytes
  }
  if (isPngSignature(bytes)) {
    val zipStart = pngLength(bytes)
    return bytes.copyOfRange(zipStart, bytes.size)
  }
  throw IllegalArgumentException(
    "not a bundle: ${file.path} — leading bytes match neither PNG (\\x89PNG…) nor ZIP (PK\\x03\\x04)"
  )
}

private val PNG_SIGNATURE: ByteArray =
  byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 P N G \r \n SUB \n

private fun isPngSignature(bytes: ByteArray): Boolean {
  if (bytes.size < PNG_SIGNATURE.size) return false
  for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
  return true
}

/**
 * Returns the byte offset of the first byte past the PNG's IEND chunk — equivalently, the length of
 * the leading PNG in the polyglot. Each chunk is `[length:4][type:4][data:length][crc:4]`; the
 * stream ends after IEND's CRC.
 */
private fun pngLength(bytes: ByteArray): Int {
  var offset = PNG_SIGNATURE.size
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
