package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * Provider-neutral design references attached to exact preview ids. Producers must include a
 * canonical PNG raster, so serving is reproducible and never executes HTML or fetches private URLs.
 */
@Serializable
public data class DesignReferenceManifest(
  val schema: String = SCHEMA,
  val references: List<DesignReference> = emptyList(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-references/v1"
  }
}

/** One independently-authored design reference mapped to an exact [previewId]. */
@Serializable
public data class DesignReference(
  /** Route-safe identity, unique within one served session. */
  val id: String,
  /** Exact serve/catalog preview id; theme/state/props selection is never inferred. */
  val previewId: String,
  /** Human label shown when a preview carries more than one reference. */
  val label: String = id,
  /** Canonical PNG used by the scorer, relative to the bundle/catalog root. */
  val raster: DesignReferenceRaster,
  /** Where this reference came from (Figma, a checked-in PNG, an HTML mock, …). */
  val source: DesignReferenceSource = DesignReferenceSource(),
  /** Original inert artifact retained by the producer for provenance/download. */
  val artifact: DesignReferenceArtifact? = null,
  /**
   * How close the published render is to this reference, scored at publish time, so the design-spec
   * chip shows it on first paint. Absent on older catalogs; the lane still scores live on entry,
   * which override-bearing renders always need anyway.
   */
  val match: DesignReferenceMatch? = null,
)

/**
 * A published render/reference comparison in the viewer readout's units: [percent] is
 * `ComposePreviewCompare.scoreImages`' structural match, [changedPercent] the delta-map share, and
 * [geometry] the content-box proportion difference (only above the threshold where it reflects the
 * design rather than rasterisation, hence nullable). Computed by the same scorer in a headless
 * page, so baked and live numbers agree.
 */
@Serializable
public data class DesignReferenceMatch(
  val percent: Double,
  val changedPercent: Double? = null,
  val geometry: Double? = null,
  /**
   * Which pixel path minted these numbers, mirroring `SCORE_VERSION` in
   * `cli/serve-web/src/scorer/tuning.ts`. A match without the current [SCORE_VERSION] (or null,
   * from older catalogs) is dropped and scored live instead, so old-kernel numbers never sit beside
   * new ones.
   */
  val scoreVersion: Int? = null,
)

@Serializable
public data class DesignReferenceRaster(
  val path: String,
  val width: Int? = null,
  val height: Int? = null,
  /** Optional lowercase SHA-256. When present, ingestion verifies it before advertising the ref. */
  val sha256: String? = null,
)

@Serializable
public data class DesignReferenceSource(
  /** `figma`, `png`, `svg`, `html`, or another provider-defined token. */
  val provider: String = "file",
  /** Informational only. The serve host never fetches this URI. */
  val uri: String? = null,
  val revision: String? = null,
  /** Provider metadata such as Figma node/page/component ids. */
  val attributes: Map<String, String> = emptyMap(),
)

@Serializable public data class DesignReferenceArtifact(val kind: String, val path: String? = null)

/**
 * Validated, read-only view of a bundle/catalog's `references/index.json`. Fail-soft: malformed,
 * missing, traversing, duplicate or hash-mismatched records are omitted.
 */
public class ServeDesignReferenceStore
private constructor(
  private val root: Path,
  references: List<DesignReference>,
  private val fileSystem: FileSystem,
) {
  private val byId: Map<String, DesignReference> = references.associateBy { it.id }
  private val byPreview: Map<String, List<DesignReference>> = references.groupBy { it.previewId }

  public val all: List<DesignReference> = references

  public fun forPreview(previewId: String): List<DesignReference> = byPreview[previewId].orEmpty()

  public fun raster(referenceId: String): ByteArray? {
    val reference = byId[referenceId] ?: return null
    val path = containedPath(reference.raster.path) ?: return null
    return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
  }

  private fun containedPath(relative: String): Path? {
    if (!isSafeRelativePath(relative)) return null
    val candidate = root / relative.toPath()
    return candidate.takeIf { fileSystem.exists(it) }
  }

  /**
   * The manifest with records left as raw JSON, so [load] can decode them one by one and drop only
   * what it can't read.
   */
  @Serializable
  private data class RawManifest(
    val schema: String = DesignReferenceManifest.SCHEMA,
    val references: List<JsonElement> = emptyList(),
  )

  public companion object {
    public const val DIRECTORY: String = "references"
    public const val INDEX_FILE: String = "index.json"

    /**
     * The pixel path this build's scorer implements, mirrored from `SCORE_VERSION` in
     * `cli/serve-web/src/scorer/tuning.ts` (which explains the number) and pinned by
     * `ServeDesignReferenceStoreTest`.
     */
    public const val SCORE_VERSION: Int = 3
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    private val SHA256 = Regex("[a-f0-9]{64}")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val JSON = Json { ignoreUnknownKeys = true }

    public fun load(
      bundleDir: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): ServeDesignReferenceStore {
      val root = bundleDir.toOkioPath()
      val manifestPath = root / DIRECTORY / INDEX_FILE
      val manifest = runCatching {
        if (!fileSystem.exists(manifestPath)) return@runCatching null
        JSON.decodeFromString<RawManifest>(fileSystem.read(manifestPath) { readUtf8() })
      }
        .getOrNull()
        ?.takeIf { it.schema == DesignReferenceManifest.SCHEMA }
      if (manifest == null) return ServeDesignReferenceStore(root, emptyList(), fileSystem)

      val seen = HashSet<String>()
      val valid =
        manifest.references
          // Decoded one record at a time: decoding the whole array would let one malformed entry
          // empty the entire store.
          .mapNotNull {
            runCatching { JSON.decodeFromJsonElement<DesignReference>(it) }.getOrNull()
          }
          .filter { reference ->
            if (!hasValidMetadata(reference)) return@filter false
            val rasterPath = root / reference.raster.path.toPath()
            if (!fileSystem.exists(rasterPath)) return@filter false
            val bytes =
              runCatching { fileSystem.read(rasterPath) { readByteArray() } }.getOrNull()
                ?: return@filter false
            hasValidRaster(reference, bytes) && seen.add(reference.id)
          }
          .map { it.copy(match = it.match?.takeIf(::isSaneMatch)) }
      return ServeDesignReferenceStore(root, valid, fileSystem)
    }

    /**
     * Whether a published match is printable: minted by this build's kernel and in range. Dropped
     * (without dropping the reference) rather than clamped; the lane scores live instead.
     */
    private fun isSaneMatch(match: DesignReferenceMatch): Boolean =
      match.scoreVersion == SCORE_VERSION &&
        match.percent.isFinite() &&
        match.percent in 0.0..100.0 &&
        (match.changedPercent?.let { it.isFinite() && it in 0.0..100.0 } ?: true) &&
        (match.geometry?.let { it.isFinite() && it >= 0.0 } ?: true)

    // Public because `:server` call sites live in another module; not a widened API by intent.
    public fun isSafeRelativePath(value: String): Boolean {
      if (value.isBlank() || value.startsWith('/') || value.startsWith('\\')) return false
      if (Regex("^[A-Za-z]:").containsMatchIn(value)) return false
      return value.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    // Public because `:server` call sites live in another module; not a widened API by intent.
    public fun isValid(reference: DesignReference, bytes: ByteArray): Boolean =
      hasValidMetadata(reference) && hasValidRaster(reference, bytes)

    private fun hasValidMetadata(reference: DesignReference): Boolean =
      SAFE_ID.matches(reference.id) &&
        reference.previewId.isNotBlank() &&
        isSafeRelativePath(reference.raster.path) &&
        reference.raster.width?.let { it > 0 } != false &&
        reference.raster.height?.let { it > 0 } != false

    private fun hasValidRaster(reference: DesignReference, bytes: ByteArray): Boolean {
      if (
        bytes.size < PNG_SIGNATURE.size ||
          PNG_SIGNATURE.indices.any { bytes[it] != PNG_SIGNATURE[it] }
      ) {
        return false
      }
      val expected = reference.raster.sha256?.lowercase() ?: return true
      if (!SHA256.matches(expected)) return false
      return bytes.toByteString().sha256().hex() == expected
    }
  }
}
