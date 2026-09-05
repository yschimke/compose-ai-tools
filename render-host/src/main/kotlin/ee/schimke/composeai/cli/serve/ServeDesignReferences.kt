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
 * Provider-neutral design references attached to exact preview ids.
 *
 * A producer may start from PNG, SVG, HTML, Figma, or another design tool, but it must include a
 * canonical PNG raster for comparison. Keeping normalization at import time makes serving
 * reproducible and prevents the preview server from executing arbitrary HTML or fetching private
 * design URLs.
 */
@Serializable
data class DesignReferenceManifest(
  val schema: String = SCHEMA,
  val references: List<DesignReference> = emptyList(),
) {
  companion object {
    const val SCHEMA = "compose-preview-references/v1"
  }
}

/** One independently-authored design reference mapped to an exact [previewId]. */
@Serializable
data class DesignReference(
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
   * How close the published render is to this reference, scored at publish time.
   *
   * The catalog exists to answer this, and until it was carried here no page answered it at rest: a
   * visitor had to enter the spec lane and wait for two rasters to decode before a number appeared.
   * Published, it goes on the design-spec chip on first paint.
   *
   * Absent on every catalog published before the producer existed, and on any run whose driver had
   * no browser to score with — so it is a strict enhancement. The lane still computes the same
   * numbers live on entry, which is what a chip with no verdict falls back to and what an
   * override-bearing render needs regardless (the baked score describes the PUBLISHED pixels, and a
   * knob has moved them).
   */
  val match: DesignReferenceMatch? = null,
)

/**
 * A published render/reference comparison, in the units the viewer's readout already prints:
 * [percent] is the structural match `ComposePreviewCompare.scoreImages` reports, [changedPercent]
 * the share of pixels the delta map marks, and [geometry] the content-box proportion difference —
 * carried only when it is above the threshold at which it describes the design rather than the
 * rasteriser, which is why it is nullable rather than zero.
 *
 * The producer computes these by driving that same asset in a headless page, so the baked numbers
 * and the lane's live ones come from one implementation and cannot disagree.
 */
@Serializable
data class DesignReferenceMatch(
  val percent: Double,
  val changedPercent: Double? = null,
  val geometry: Double? = null,
  /**
   * Which pixel path minted these numbers, mirrored from `SCORE_VERSION` in
   * `cli/serve-web/src/scorer/tuning.ts` and checked against it by a test.
   *
   * A match that does not carry [SCORE_VERSION] is dropped rather than printed. The scorer's kernel
   * changed once, deliberately — `drawImage`'s implementation-defined smoothing gave way to the
   * portable area average both engines run — and every published number moved with it. A delivery
   * branch is regenerated on its own schedule, so a viewer will inevitably meet a catalog baked
   * before the change; printing that chip would put a number from the old kernel beside a readout
   * the lane computes with the new one, and the two disagreeing at a glance is the exact failure a
   * baked number cannot survive. Dropped, the lane scores live on entry — which is what a chip with
   * no verdict has always fallen back to.
   *
   * Null on every catalog published before the version existed, which is the same case and is
   * treated the same way.
   */
  val scoreVersion: Int? = null,
)

@Serializable
data class DesignReferenceRaster(
  val path: String,
  val width: Int? = null,
  val height: Int? = null,
  /** Optional lowercase SHA-256. When present, ingestion verifies it before advertising the ref. */
  val sha256: String? = null,
)

@Serializable
data class DesignReferenceSource(
  /** `figma`, `png`, `svg`, `html`, or another provider-defined token. */
  val provider: String = "file",
  /** Informational only. The serve host never fetches this URI. */
  val uri: String? = null,
  val revision: String? = null,
  /** Provider metadata such as Figma node/page/component ids. */
  val attributes: Map<String, String> = emptyMap(),
)

@Serializable data class DesignReferenceArtifact(val kind: String, val path: String? = null)

/**
 * Validated, read-only view of a bundle/catalog's `references/index.json`.
 *
 * All failures are fail-soft: malformed, missing, traversing, duplicate, or hash-mismatched records
 * are omitted while the rest of the preview bundle continues to serve normally.
 */
class ServeDesignReferenceStore
private constructor(
  private val root: Path,
  references: List<DesignReference>,
  private val fileSystem: FileSystem,
) {
  private val byId: Map<String, DesignReference> = references.associateBy { it.id }
  private val byPreview: Map<String, List<DesignReference>> = references.groupBy { it.previewId }

  val all: List<DesignReference> = references

  fun forPreview(previewId: String): List<DesignReference> = byPreview[previewId].orEmpty()

  fun raster(referenceId: String): ByteArray? {
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
   * The manifest as read from disk, with its records left as raw JSON.
   *
   * [DesignReferenceManifest] is the schema producers write against; this is what a fail-soft
   * READER needs, and they differ in exactly one way that matters: decoding a `List<JsonElement>`
   * cannot fail on the contents of any one record, so [load] can decode them individually and drop
   * only what it cannot read.
   */
  @Serializable
  private data class RawManifest(
    val schema: String = DesignReferenceManifest.SCHEMA,
    val references: List<JsonElement> = emptyList(),
  )

  companion object {
    const val DIRECTORY = "references"
    const val INDEX_FILE = "index.json"

    /**
     * The pixel path this build's scorer implements — mirrored from `SCORE_VERSION` in
     * `cli/serve-web/src/scorer/tuning.ts`, which is where the rationale for the number lives, and
     * pinned to it by `ServeDesignReferenceStoreTest`.
     *
     * Two copies of a constant are fine while something fails when they disagree, and this is the
     * pair that has to agree: the browser mints the number and the host decides whether to print
     * it, so a host reading the wrong version would either discard every current match or trust
     * every stale one.
     */
    const val SCORE_VERSION = 3
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    private val SHA256 = Regex("[a-f0-9]{64}")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val JSON = Json { ignoreUnknownKeys = true }

    fun load(
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
          // Decoded ONE RECORD AT A TIME, so a record this reader cannot understand costs only
          // itself. Decoding the whole array in one call makes any single malformed entry — a
          // `"match": {}` from a half-written producer, a null where a number belongs — throw while
          // parsing the envelope, which lands in the `runCatching` above and returns an EMPTY
          // store: one bad record and the catalog's entire design-spec lane goes dark, on every
          // page, silently. That is the opposite of this class's stated contract, and the
          // per-record validation below (ids, paths, hashes, [isSaneMatch]) never gets to run.
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
     * Whether a published match is a number a chip can print — minted by the kernel this build
     * scores with, and in range.
     *
     * Dropped rather than clamped, and dropped WITHOUT taking the reference with it: a nonsense
     * percentage is a producer bug, and the lane's live scoring still answers the same question on
     * entry — so the cost of ignoring it is a chip with no verdict, where the cost of trusting it
     * is a chip stating a falsehood and the cost of dropping the record is a page with no design
     * spec at all.
     */
    private fun isSaneMatch(match: DesignReferenceMatch): Boolean =
      match.scoreVersion == SCORE_VERSION &&
        match.percent.isFinite() &&
        match.percent in 0.0..100.0 &&
        (match.changedPercent?.let { it.isFinite() && it in 0.0..100.0 } ?: true) &&
        (match.geometry?.let { it.isFinite() && it >= 0.0 } ?: true)

    // Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
    // and the `:server` call sites are in a different module now. Not a widened API by intent.
    fun isSafeRelativePath(value: String): Boolean {
      if (value.isBlank() || value.startsWith('/') || value.startsWith('\\')) return false
      if (Regex("^[A-Za-z]:").containsMatchIn(value)) return false
      return value.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    // Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
    // and the `:server` call sites are in a different module now. Not a widened API by intent.
    fun isValid(reference: DesignReference, bytes: ByteArray): Boolean =
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
