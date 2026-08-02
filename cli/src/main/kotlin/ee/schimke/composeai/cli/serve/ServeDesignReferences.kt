package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

  companion object {
    const val DIRECTORY = "references"
    const val INDEX_FILE = "index.json"
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
      val manifest =
        runCatching {
            if (!fileSystem.exists(manifestPath)) return@runCatching null
            JSON.decodeFromString<DesignReferenceManifest>(
              fileSystem.read(manifestPath) { readUtf8() }
            )
          }
          .getOrNull()
          ?.takeIf { it.schema == DesignReferenceManifest.SCHEMA }
      if (manifest == null) return ServeDesignReferenceStore(root, emptyList(), fileSystem)

      val seen = HashSet<String>()
      val valid =
        manifest.references.filter { reference ->
          if (
            !SAFE_ID.matches(reference.id) ||
              !seen.add(reference.id) ||
              reference.previewId.isBlank() ||
              !isSafeRelativePath(reference.raster.path) ||
              reference.raster.width?.let { it <= 0 } == true ||
              reference.raster.height?.let { it <= 0 } == true
          ) {
            return@filter false
          }
          val rasterPath = root / reference.raster.path.toPath()
          if (!fileSystem.exists(rasterPath)) return@filter false
          val bytes =
            runCatching { fileSystem.read(rasterPath) { readByteArray() } }.getOrNull()
              ?: return@filter false
          if (
            bytes.size < PNG_SIGNATURE.size ||
              PNG_SIGNATURE.indices.any { bytes[it] != PNG_SIGNATURE[it] }
          ) {
            return@filter false
          }
          val expected = reference.raster.sha256?.lowercase()
          if (expected == null) return@filter true
          if (!SHA256.matches(expected)) return@filter false
          val actual = bytes.toByteString().sha256().hex()
          actual == expected
        }
      return ServeDesignReferenceStore(root, valid, fileSystem)
    }

    internal fun isSafeRelativePath(value: String): Boolean {
      if (value.isBlank() || value.startsWith('/') || value.startsWith('\\')) return false
      if (Regex("^[A-Za-z]:").containsMatchIn(value)) return false
      return value.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    internal fun isSafeId(value: String): Boolean = SAFE_ID.matches(value)
  }
}
