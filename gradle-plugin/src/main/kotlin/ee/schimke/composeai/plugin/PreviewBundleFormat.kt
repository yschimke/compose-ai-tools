package ee.schimke.composeai.plugin

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * On-disk format for `compose-preview` bundles: portable artefacts that record one or more
 * `@Preview` composables plus the **minimal** classpath needed to re-render them.
 *
 * # File shape
 *
 * A **PNG + ZIP polyglot**: the leading bytes are a valid PNG (the cover — the first selected
 * preview's render, or a gray stub), so every image viewer shows it; the trailing bytes are a ZIP,
 * whose parsers scan back from EOF for the End-Of-Central-Directory record and never see the PNG.
 * `unzip foo.png` works.
 *
 * # ZIP layout
 *
 * ```
 * bundle.json                  — manifest ([BundleManifest])
 * previews.json                — filtered to the selected preview ids
 * previews/<id>.png            — rendered PNG per selected preview, cover included ([BUNDLE_PREVIEWS_DIR])
 * previews/<id>.apng|.gif      — motion capture, named from the same id ([motionBundleEntryPath])
 * previews/<id>.error.json     — render failure sidecar, copied verbatim, for a preview with no PNG
 * previews/<id>.overrides.json — (v8) verbatim `compose/overrides` knob payload, opt-in previews only
 * previews/<id>.catalog.json   — resolved catalog token values ([BUNDLE_CATALOG_TOKENS_SIDECAR_EXT])
 * classes/app.jar              — module bytecode minimized to classes reachable from the selected
 *                                previews (IR-backed previews are not closure seeds)
 * libs/<name>.jar              — [ClasspathEntry.Project] / [ClasspathEntry.Embedded] jars
 * ir/<id>.<ext>                — (v5) captured IR: `.rc`, `.tilelayout` + `.tileresources` ([BundleIr])
 * signatures.json              — (v8, optional) detached signatures, excluded from the signed digest
 * extensions/<id>.json         — (v7, optional) data-extension report sliced to the cover preview
 * report.json                  — [MinimizationReport]
 * web/                         — (optional) web embed added by `bundle embed --in-bundle`; never read
 * ```
 *
 * Baked PNGs let a reader show every preview without re-rendering or the source project;
 * `classes/app.jar` + classpath remain for live re-render. The default `resolution = "coordinates"`
 * pack records Maven coordinates that the player re-resolves (keeps a bundle ~100 KB); an
 * `"embedded"` pack carries jars in `libs/` instead, trading size for offline portability. Android
 * entries use `type = "aar"` so the player resolves the unprocessed AAR and AGP's transforms run.
 *
 * # Intermediate-representation previews (v5)
 *
 * Remote Compose documents and Wear ProtoLayout protos can be replayed by a small runtime without
 * re-running the producing Kotlin. Such previews carry their IR under `ir/` and a [BundleIr]
 * record, and their enclosing class is dropped from the minimization seed. A bundle can mix
 * IR-backed and classpath-backed previews.
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  /** Backend the bundle was packed for. v1 = "desktop"; "android" follows. */
  val backend: String,
  /**
   * Selected preview ids in their in-bundle (entry-name) form — sanitised by
   * `sanitizeBundleEntryId`, so no spaces or shell-hostile characters. First entry = cover.
   */
  val previewIds: List<String>,
  /** Preview id whose PNG forms the polyglot's leading bytes. Usually `previewIds[0]`. */
  val coverPreviewId: String?,
  /**
   * The raw discovery ids, parallel to [previewIds]. Sanitisation is lossy (`"A B"` and `"A_B"`
   * both become `A_B`), so consumers that address the producing daemon/renderer by raw id translate
   * through this list. Empty on older bundles; readers fall back to [previewIds].
   */
  val rawPreviewIds: List<String> = emptyList(),
  /**
   * Classpath in load order. First entry is always [ClasspathEntry.Module] for `classes/app.jar`;
   * the rest are [ClasspathEntry.Maven] coordinates, [ClasspathEntry.Embedded] jars in `libs/`, or
   * [ClasspathEntry.Project] fallbacks.
   */
  val classpath: List<ClasspathEntry>,
  /** Source Gradle path that produced the bundle, e.g. `:samples:cmp`. */
  val modulePath: String,
  /**
   * The producing project's directory relative to the repository root (`bundle/format` for
   * `:bundle-format`); empty for the root project and on older bundles.
   *
   * Recorded rather than derived because [modulePath] is a logical name and `projectDir` can be
   * remapped arbitrarily; only Gradle knows the real directory.
   */
  val moduleDirectory: String = "",
  /** `BUNDLE_VERSION`-shaped identifier of the producer for diagnostics. */
  val producedBy: String,
  /**
   * Build system that produced the bundle: `"gradle"`, `"amper"`, or `"bazel"`. Informational.
   * Defaults to `"gradle"` so a v2 bundle decodes as Gradle-produced.
   */
  val producer: String = PRODUCER_GRADLE,
  /**
   * How the player assembles the third-party classpath:
   * - [RESOLUTION_COORDINATES] — resolve [ClasspathEntry.Maven] entries from the consumer's repos
   *   (default).
   * - [RESOLUTION_EMBEDDED] — everything reachable is carried in `libs/` (no network / build system
   *   needed).
   * - [RESOLUTION_MIXED] — coordinate-less deps embedded, the rest by coordinate.
   */
  val resolution: String = RESOLUTION_COORDINATES,
  /**
   * (v5) Previews replayed from a captured IR rather than by re-running their composable. A preview
   * appears here OR has its enclosing class in `classes/app.jar`, never both.
   */
  val intermediateRepresentations: List<BundleIr> = emptyList(),
  /**
   * (v6) Android resources for protolayout IR replay: `TileRenderer` needs the merged resource
   * table and the library `R$style` class, neither of which a detached daemon has. Points at the
   * merged resource APK + manifest and R classes under `android/`, from which the player rebuilds
   * Robolectric's `test_config.properties`. `null` when there is no protolayout IR.
   */
  val androidResources: BundleAndroidResources? = null,
  /**
   * (v7) Per-extension data reports packed under `extensions/<id>.json`, sliced to the cover
   * preview. Empty unless the opt-in `--include-data-extensions` pack was requested. When present,
   * the bundled `previews.json`'s `dataExtensionReports` is realigned to these in-bundle paths.
   */
  val dataExtensions: List<BundleDataExtension> = emptyList(),
  /**
   * (v8, post-pack) Large resources (fonts, …) lifted out of `classes/app.jar` by `bundle
   * externalize`, published content-addressed by sha256 and rehydrated onto the daemon classpath at
   * their original path. Doesn't bump [schemaVersion]: it is a post-pack transform, like signing.
   */
  val externalResources: List<BundleExternalResource> = emptyList(),
  /**
   * (v8, post-split, opt-in) Whole classpath entries omitted from this bundle and published once in
   * the sibling content-addressed pool; restores the exact zip entry named by
   * [BundleExternalClasspath.path]. Only the shared-classpath split mode populates it.
   */
  val externalClasspath: List<BundleExternalClasspath> = emptyList(),
  /**
   * (v9) Extra Maven repository base URLs, beyond Maven Central and Google Maven, needed to
   * re-resolve this bundle's coordinates (e.g. an androidx.dev snapshot or a JitPack fork). Without
   * them a player stands a daemon up on an incomplete classpath.
   *
   * These URLs are producer-declared, so treat them with the same trust as the bundle's classes;
   * [ClasspathEntry.Maven.sha256] still governs whether fetched bytes are the ones packed.
   */
  val repositories: List<String> = emptyList(),
)

/** One whole bundle classpath entry stored at `bundle/res/<sha256>` instead of inline. */
@Serializable
data class BundleExternalClasspath(val path: String, val sha256: String, val size: Long)

/**
 * One resource lifted out of `classes/app.jar` by `bundle externalize`; a server rehydrates it onto
 * the daemon classpath at [path]. See [BundleManifest.externalResources].
 */
@Serializable
data class BundleExternalResource(
  /** Classpath-relative path inside the original jar, e.g. `fonts/Roboto-Regular.ttf`. */
  val path: String,
  /** Lowercase-hex SHA-256 of the bytes; the content-addressed key. */
  val sha256: String,
  /** Size of the resource in bytes, for diagnostics + a fetch sanity check. */
  val size: Long,
)

/**
 * One data-extension report carried in the bundle under `extensions/<id>.json`, sliced to the cover
 * preview. [extensionId] matches the key in `previews.json`'s `dataExtensionReports` (e.g.
 * `"a11y"`).
 */
@Serializable
data class BundleDataExtension(
  /** Stable extension id, e.g. `"a11y"`. Matches the `dataExtensionReports` map key. */
  val extensionId: String,
  /** Posix zip path of the carried report bytes, e.g. `extensions/a11y.json`. */
  val path: String,
)

/**
 * (v6) Android resource artefacts for protolayout IR replay; paths are posix zip paths. See
 * [BundleManifest.androidResources].
 */
@Serializable
data class BundleAndroidResources(
  /** Zip path of the merged resource APK, e.g. `android/resources.ap_`. */
  val resourceApkPath: String,
  /** Zip path of the merged `AndroidManifest.xml` Robolectric reads the package + theme from. */
  val mergedManifestPath: String,
  /**
   * Zip path of the jar holding generated library R classes, e.g. `android/r-classes.jar`; `null`
   * when none were found.
   */
  val rClassesJarPath: String? = null,
  /**
   * Consumer application package (`android_custom_package`), recorded for the synthesized config.
   */
  val applicationPackage: String? = null,
)

/**
 * One preview replayed from a captured IR. The player keys on [format] and reads bytes from [path]
 * (plus [resourcesPath] for protolayout).
 */
@Serializable
data class BundleIr(
  /** Preview id this IR renders; matches an entry in [BundleManifest.previewIds]. */
  val previewId: String,
  /**
   * IR flavour: [IR_FORMAT_REMOTECOMPOSE], [IR_FORMAT_PROTOLAYOUT], [IR_FORMAT_LOTTIE], or
   * [IR_FORMAT_SVG].
   */
  val format: String,
  /** Posix zip path of the IR bytes, e.g. `ir/<id>.rc` or `ir/<id>.tilelayout`. */
  val path: String,
  /**
   * Companion artefact path, e.g. the protolayout resources proto; `null` when [path] carries
   * everything.
   */
  val resourcesPath: String? = null,
)

/**
 * (v8) Detached producer signatures carried in `signatures.json`.
 *
 * Baked PNGs and IR are data, but re-rendering `classes/app.jar` runs the producer's code, so a
 * public server only re-renders bundles signed by a producer in its trust store.
 *
 * The canonical digest excludes `signatures.json` (so signatures can be appended independently):
 * 1. For every non-directory entry except `signatures.json`, form
 *    `"<posix-path>:<lowercase-hex-sha256>"`.
 * 2. Sort by path (UTF-8 byte order) and join with `"\n"`.
 * 3. SHA-256 the joined UTF-8 bytes.
 *
 * A signature is `Ed25519(privateKey, canonicalDigest)`, checked against the public key named by
 * `keyId`. Reference implementation: `:cli`'s `BundleSigning`.
 */
@Serializable
data class BundleSignatures(
  /** Schema id, pinned so a verifier can detect a format break. [BUNDLE_SIGNATURES_SCHEMA]. */
  val schema: String = BUNDLE_SIGNATURES_SCHEMA,
  /** One entry per producer that signed this bundle. At least one on a signed bundle. */
  val signatures: List<BundleSignature>,
)

/** One producer's detached signature over the bundle's canonical digest. See [BundleSignatures]. */
@Serializable
data class BundleSignature(
  /**
   * Stable id of the signing key, e.g. `"compose-ai-tools-ci"`, mapped to a public key by the
   * verifier's trust store. Conventionally `[A-Za-z0-9._@-]+`.
   */
  val keyId: String,
  /** Signature algorithm. Only [SIGNATURE_ALG_ED25519] is defined today. */
  val algorithm: String = SIGNATURE_ALG_ED25519,
  /** Lowercase-hex canonical digest the signature covers (see [BundleSignatures]). */
  val digest: String,
  /** Base64 (standard, padded) of the raw Ed25519 signature bytes over [digest]'s raw bytes. */
  val signature: String,
  /** Human-readable producer label for diagnostics, e.g. `"Compose AI Tools CI"`. Optional. */
  val producer: String? = null,
  /**
   * Optional keyless (GitHub OIDC / Sigstore) attestation, letting the verifier trust
   * [BundleProvenance.identity] instead of a pinned key.
   */
  val provenance: BundleProvenance? = null,
)

/** Keyless-provenance attestation attached to a [BundleSignature] (GitHub OIDC / Sigstore). */
@Serializable
data class BundleProvenance(
  /** Provenance flavour: [PROVENANCE_GITHUB_OIDC] or [PROVENANCE_SIGSTORE]. */
  val type: String,
  /**
   * Workload identity that produced the bundle, e.g.
   * `repo:yschimke/compose-ai-tools:ref:refs/heads/main`, matched against trusted-identity globs.
   */
  val identity: String,
  /** Optional opaque attestation bundle / certificate (Sigstore) for full offline verification. */
  val attestation: String? = null,
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
   * A Maven coordinate the player resolves at open time — the canonical way to carry a third-party
   * dependency; [ClasspathEntry.Embedded] is the offline fallback. Separate fields so consumers can
   * filter without re-parsing.
   */
  @Serializable
  @kotlinx.serialization.SerialName("maven")
  data class Maven(
    val group: String,
    val artifact: String,
    val version: String,
    /**
     * `"jar"` for pure-JVM deps, `"aar"` for Android libraries (resolved unprocessed so AGP's
     * transforms run).
     */
    val type: String,
    /**
     * Lowercase-hex SHA-256 of the artifact at pack time, or null when unknown.
     *
     * **Mismatch policy: warn, never fail.** A different artifact for the same coordinate is
     * usually almost compatible, and a slightly-off render beats none, so a player logs the
     * mismatch loudly and proceeds.
     */
    val sha256: String? = null,
  ) : ClasspathEntry

  /** Project-local dep with no Maven coordinate, inlined so the bundle stays self-contained. */
  @Serializable
  @kotlinx.serialization.SerialName("project")
  data class Project(
    /** Gradle path of the producing project, e.g. `:my-lib`. Informational. */
    val path: String,
    /** Posix relative path inside the bundle zip, e.g. `libs/my-lib.jar`. */
    val inlinedAs: String,
  ) : ClasspathEntry

  /**
   * A third-party jar carried in `libs/` rather than by coordinate, from `resolution = "embedded"`
   * packs or non-Gradle producers. Unlike [Project] it has no Gradle path.
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
 * [BundleIr.format] for a Lottie asset; the IR is the asset file itself, read from module resources
 * at pack time (`ir/<id>.json` or `ir/<id>.lottie`).
 */
const val IR_FORMAT_LOTTIE: String = "lottie"

/**
 * [BundleIr.format] for an SVG asset; like [IR_FORMAT_LOTTIE] the IR is the raw `.svg`
 * (`ir/<id>.svg`). Static, so no animated companion.
 */
const val IR_FORMAT_SVG: String = "svg"

/** Well-known directory inside the bundle zip holding per-preview IR bytes (`ir/<id>.<ext>`). */
const val BUNDLE_IR_DIR: String = "ir"

/** Well-known zip path of the detached producer signatures (v8). See [BundleSignatures]. */
const val BUNDLE_SIGNATURES_PATH: String = "signatures.json"

/** Schema id stamped into [BundleSignatures.schema]. */
const val BUNDLE_SIGNATURES_SCHEMA: String = "compose-preview-bundle/signatures/v1"

/** [BundleSignature.algorithm] value for an Ed25519 signature (the only one defined today). */
const val SIGNATURE_ALG_ED25519: String = "ed25519"

/** [BundleProvenance.type] values. */
const val PROVENANCE_GITHUB_OIDC: String = "github-oidc"

const val PROVENANCE_SIGSTORE: String = "sigstore"

/** File extension for a captured Remote Compose document byte stream. */
const val IR_EXT_REMOTECOMPOSE: String = "rc"

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

/** Bundle directory holding per-extension data reports, `extensions/<extensionId>.json` (v7). */
const val BUNDLE_EXTENSIONS_DIR: String = "extensions"

/**
 * Conventional report filenames (relative to `previews.json`'s parent) for built-in extensions that
 * don't stamp `dataExtensionReports`. An `--include-data-extensions` pack probes these when the
 * manifest has no pointer; a manifest pointer always wins. Mirrors `:cli`'s `A11yReportRenderer`.
 */
val CONVENTIONAL_DATA_EXTENSION_REPORTS: Map<String, String> = mapOf("a11y" to "accessibility.json")

/**
 * Schema version stamped into [BundleManifest.schemaVersion]. Every bump is additive:
 * `ignoreUnknownKeys` readers skip new fields and entries.
 * - v1 — `bundle.json`, `previews.json`, `classes/app.jar`, `report.json`; cover PNG only.
 * - v2 — [BUNDLE_PREVIEWS_DIR] with a baked PNG per preview.
 * - v3 — [BundleManifest.producer], [BundleManifest.resolution], [ClasspathEntry.Embedded] in
 *   `libs/`.
 * - v4 — [ClasspathEntry.Maven.sha256].
 * - v5 — [BundleManifest.intermediateRepresentations] and `ir/`.
 * - v6 — [BundleManifest.androidResources] and `android/`.
 * - v7 — [BundleManifest.dataExtensions] and `extensions/`.
 * - v8 — `previews/<id>.overrides.json` knob sidecar.
 * - v9 — [BundleManifest.repositories].
 *
 * `signatures.json` ([BUNDLE_SIGNATURES_PATH]) is orthogonal: a post-pack step that doesn't bump
 * the version.
 */
const val BUNDLE_SCHEMA_VERSION: Int = 9

/**
 * Extension of the knob sidecar: `renders/<stem>.overrides.json` on disk,
 * `previews/<id>.overrides.json` in the bundle. Kept in lockstep with the consumer runtime's
 * writer.
 */
const val BUNDLE_OVERRIDES_SIDECAR_EXT: String = "overrides.json"

/**
 * Extension of the Remote Compose knob sidecar (`RemoteComposeDeclarationsPayload`), separate from
 * `overrides.json` because Remote Compose edits round-trip through their own channel. Kept in
 * lockstep with `RobolectricRenderTest.writeRemoteComposeSidecar`.
 */
const val BUNDLE_REMOTECOMPOSE_SIDECAR_EXT: String = "remotecompose.json"

/**
 * Extension of the render-failure sidecar: `<png>.error.json` on disk, `previews/<id>.error.json`
 * in the bundle.
 */
const val BUNDLE_RENDER_ERROR_SIDECAR_EXT: String = "error.json"

/**
 * Extension of the catalog-token sidecar (`data/catalog-tokens/<id>.catalog.json`), packed as
 * `previews/<id>.catalog.json` so a detached reader can import catalog colours and type metrics.
 * Kept in lockstep with the renderer's `CatalogTokenSidecar`.
 */
const val BUNDLE_CATALOG_TOKENS_SIDECAR_EXT: String = "catalog.json"

/**
 * Bundle directory holding one PNG per selected preview, `previews/<previewId>.png`; the cover is
 * mirrored here too.
 */
const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Suffixes a motion render carries when one function owns more than one motion output. Kept in step
 * with `PreviewDiscovery.buildOutputPlan` and design-parity's `catalog-motion-publish.mjs`.
 */
val BUNDLE_MOTION_SUFFIXES: List<String> = listOf("_interaction", "_anim")

/**
 * Bundle entry for a motion capture: `previews/<previewId>[_interaction|_anim].<ext>`.
 *
 * Named from the preview id, like its still, because downstream joins are by name; the render leaf
 * is `<readable>-<digest>` and would land in a different namespace. Reading the suffix off the leaf
 * is safe because a stem only ends in `_interaction` / `_anim` when the renderer put one there.
 *
 * @param bundleId the preview's in-bundle id (post-[sanitizeBundleEntryId]).
 * @param renderLeaf the render's own filename, whose structural suffix is carried through.
 * @return the entry path, or null when [renderLeaf] is not a motion artifact.
 */
fun motionBundleEntryPath(bundleId: String, renderLeaf: String): String? {
  val extension = renderLeaf.substringAfterLast('.', missingDelimiterValue = "").lowercase()
  if (extension != "apng" && extension != "gif") return null
  val stem = renderLeaf.substringBeforeLast('.')
  val suffix = BUNDLE_MOTION_SUFFIXES.firstOrNull { stem.endsWith(it) }.orEmpty()
  return "$BUNDLE_PREVIEWS_DIR/$bundleId$suffix.$extension"
}

/** How aggressive minimization was; always written as `report.json` for auditing. */
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
 * Writes a PNG + ZIP polyglot by concatenating [coverPng] and [zipBytes] verbatim. Works because
 * ZIP's EOCD is searched from EOF and PNG parsing stops at IEND.
 */
internal fun writePngZipPolyglot(coverPng: ByteArray, zipBytes: ByteArray, out: File) {
  out.parentFile?.mkdirs()
  out.outputStream().use { stream ->
    stream.write(coverPng)
    stream.write(zipBytes)
  }
}

/**
 * Returns the zip bytes of a polyglot (or a plain `.zip`) by skipping past the PNG's IEND chunk.
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

/** Byte offset just past the PNG's IEND chunk, i.e. the length of the leading PNG. */
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
