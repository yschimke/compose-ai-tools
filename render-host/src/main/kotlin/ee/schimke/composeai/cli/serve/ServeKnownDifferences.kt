package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * A catalog's committed known differences: the `compose-preview-known-differences/v1` document
 * ([docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)
 * §4) and the mask / accepted-candidate rasters it names, published under `parity/`.
 *
 * The host carries the document verbatim and decides nothing: verdicts belong to the shared engine
 * ([known-differences.mjs](../../../../../../../../scripts/design-artifacts/known-differences.mjs)),
 * and pre-parsing here would make a third, untested implementation. This class only discharges the
 * reader obligations no lexical rule can: size checked before allocation, containment resolved
 * against the acceptance's own `<id>/` directory (not just the artifact root), and exact case.
 */
public object ServeKnownDifferences {
  public const val DIRECTORY: String = "parity"
  public const val DOCUMENT_FILE: String = "known-differences.json"
  public const val ARTIFACT_DIRECTORY: String = "known-differences"

  /**
   * The producer's own list of published artifacts, so staging can copy a list rather than derive
   * one. A sibling of the document because `index.json` is a legal record id. Not part of the
   * contract; a catalog without it still works.
   */
  public const val ARTIFACT_INDEX_FILE: String = "known-differences-index.json"

  /** The index's schema token; a document declaring another is ignored rather than guessed at. */
  public const val ARTIFACT_INDEX_SCHEMA: String = "compose-preview-known-difference-artifacts/v1"

  /**
   * Mirrored so staging can skip fetching artifacts for a document the engine refuses whole. Pinned
   * to the contract by [ServeKnownDifferencesTest].
   */
  public const val SCHEMA: String = "compose-preview-known-differences/v1"

  /**
   * Contract ceilings, restated because Kotlin cannot import the JS `BUDGET`;
   * [ServeKnownDifferencesTest] fails when they disagree.
   */
  public const val MAX_DOCUMENT_BYTES: Int = 1024 * 1024
  public const val MAX_ARTIFACT_BYTES: Int = 8 * 1024 * 1024

  /** Mirrors `BUDGET.maxAcceptances`; bounds the staging fetch list, never a verdict. */
  public const val MAX_ACCEPTANCES: Int = 256

  /**
   * §4's portable path grammar, mirroring `isPortableSegment` in `known-differences.mjs`: the
   * character class plus the shapes it can't exclude (`.`/`..`, trailing dot or space, Windows
   * device names).
   */
  private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]{1,255}")

  private val RESERVED_SEGMENTS = buildSet {
    addAll(listOf("con", "prn", "aux", "nul"))
    for (i in 1..9) {
      add("com$i")
      add("lpt$i")
    }
  }

  private fun isPortableSegment(segment: String): Boolean {
    if (!SAFE_SEGMENT.matches(segment)) return false
    if (segment == "." || segment == "..") return false
    if (segment.endsWith(".") || segment.endsWith(" ")) return false
    return segment.substringBefore('.').lowercase() !in RESERVED_SEGMENTS
  }

  /** What a read produced; the failures are the reader tokens §4 defines. */
  public sealed interface Artifact {
    public class Bytes(public val bytes: ByteArray) : Artifact

    /** The path resolves outside the acceptance's own directory. */
    public data object NotContained : Artifact

    /** The file is past [MAX_ARTIFACT_BYTES], refused from its length rather than read. */
    public data object TooLarge : Artifact

    /** No file, a directory, or a spelling the filesystem resolved case-insensitively. */
    public data object Unreadable : Artifact
  }

  /**
   * The document's raw text, or null when the catalog publishes none. An oversized document is
   * [Document.TooLarge], not null, and is refused from its length before it is read.
   */
  public fun document(bundleDir: File, fileSystem: FileSystem = FileSystem.SYSTEM): Document? =
    document(bundleDir.toOkioPath(), fileSystem)

  public fun document(bundleRoot: Path, fileSystem: FileSystem): Document? {
    val path = bundleRoot / DIRECTORY.toPath() / DOCUMENT_FILE.toPath()
    val metadata = runCatching { fileSystem.metadataOrNull(path) }.getOrNull() ?: return null
    if (metadata.isDirectory) return null
    val size = metadata.size ?: return null
    if (size > MAX_DOCUMENT_BYTES) return Document.TooLarge
    val text = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return null
    // Re-checked on the decoded text in case something between the two re-encodes.
    if (text.encodeToByteArray().size > MAX_DOCUMENT_BYTES) return Document.TooLarge
    return Document.Text(text)
  }

  public sealed interface Document {
    public data class Text(val text: String) : Document

    public data object TooLarge : Document
  }

  /** One artifact, addressed as `<id>/<path>` relative to the `known-differences/` root. */
  public fun artifact(
    bundleDir: File,
    relativePath: String,
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ): Artifact = artifact(bundleDir.toOkioPath(), relativePath, fileSystem)

  /**
   * The lexical half of [artifact], shared with catalog staging so the stager and reader cannot
   * disagree. Containment, case and size stay in [artifact].
   */
  public fun isLookupPath(relativePath: String): Boolean {
    val segments = relativePath.split('/')
    if (segments.size < 2) return false
    return segments.all { isPortableSegment(it) }
  }

  public fun artifact(bundleRoot: Path, relativePath: String, fileSystem: FileSystem): Artifact {
    val segments = relativePath.split('/')
    // Lexical first, so a traversal never becomes a filesystem question.
    if (segments.size < 2) return Artifact.NotContained
    if (segments.any { !isPortableSegment(it) }) return Artifact.NotContained

    val acceptanceRoot = bundleRoot / DIRECTORY.toPath() / ARTIFACT_DIRECTORY.toPath() / segments[0]
    var path = acceptanceRoot
    for (segment in segments.drop(1)) path /= segment

    val canonical =
      runCatching { fileSystem.canonicalize(path) }.getOrNull() ?: return Artifact.Unreadable
    val root =
      runCatching { fileSystem.canonicalize(acceptanceRoot) }.getOrNull()
        ?: return Artifact.Unreadable
    // Contained in this acceptance's own directory, not merely under the root: a symlink from
    // `a/link` into `b/` would otherwise let record `a` read `b`'s bytes. Walked as parents so
    // `…/id-two` cannot pass as a child of `…/id`.
    if (generateSequence(canonical) { it.parent }.none { it == root }) return Artifact.NotContained
    // Exact case: `canonicalize` reports the on-disk spelling. Only matters on case-insensitive
    // filesystems, so CI does not exercise it.
    if (canonical.segments.takeLast(segments.size) != segments) return Artifact.Unreadable

    val metadata =
      runCatching { fileSystem.metadataOrNull(canonical) }.getOrNull() ?: return Artifact.Unreadable
    if (metadata.isDirectory) return Artifact.Unreadable
    val size = metadata.size ?: return Artifact.Unreadable
    // From the length, before the bytes exist.
    if (size > MAX_ARTIFACT_BYTES) return Artifact.TooLarge
    val bytes = runCatching { fileSystem.read(canonical) { readByteArray() } }.getOrNull()
    return if (bytes == null) Artifact.Unreadable else Artifact.Bytes(bytes)
  }
}

/**
 * Everything the browser engine needs to evaluate this catalog's acceptances against one
 * comparison. The scope fields must be the same values `ServeIssueReport` writes: an acceptance
 * matches on every recorded field, and an independently derived spelling would miss it.
 */
@Serializable
public data class KnownDifferenceContext(
  val documentUrl: String,
  /**
   * An artifact URL is `artifactBase + "<id>/<file>" + artifactQuery`; split so the credential in
   * the query survives without the client splicing paths into a URL it did not build.
   */
  val artifactBase: String,
  val artifactQuery: String,
  val referenceUrl: String,
  val candidateUrl: String,
  val scope: KnownDifferenceScope,
  /** Positive issue-state evidence for the lifecycle join; an absent row remains `unknown`. */
  val issues: List<KnownDifferenceIssue> = emptyList(),
)

@Serializable
public data class KnownDifferenceIssue(
  val repository: String,
  val number: Int,
  val state: String,
)

/**
 * The comparison's identity, without URLs: the handler knows the identity, the page builds every
 * URL through one query builder.
 */
@Serializable
public data class KnownDifferenceScope(
  val system: String,
  val component: String,
  val previewId: String,
  val referenceId: String,
  val variant: String,
  val overrides: Map<String, String> = emptyMap(),
  /**
   * The served reference's digest. Null makes a targeting acceptance refused, not unchecked: a gate
   * that cannot fire must not report a pass.
   */
  val referenceSha256: String? = null,
  /**
   * The tag index for this preview, or empty when it was not measured on this frame. Empty makes an
   * element-scoped acceptance suppress nothing, which is safer than a stale index.
   */
  val tagIndex: Map<String, WireTagEntry> = emptyMap(),
)

private val CONTEXT_JSON = Json { encodeDefaults = true }

/** As an inline `application/json` payload, with `<` escaped so it cannot close the script tag. */
public fun encodeKnownDifferenceContext(context: KnownDifferenceContext): String =
  CONTEXT_JSON.encodeToString(context).replace("<", "\\u003c")

/**
 * Everything the browser engine needs to audit this catalog's acceptances without a comparison, so
 * `orphaned-target` records (scoped into no comparison) are visible in the dashboard. [previews]
 * uses the locator's spellings ([ServeIssueReport.Context]) or every acceptance would read as an
 * orphan.
 */
@Serializable
public data class KnownDifferenceAuditContext(
  val documentUrl: String,
  val artifactBase: String,
  val artifactQuery: String,
  val previews: List<KnownDifferenceCatalogPreview> = emptyList(),
  /** Positive issue-state evidence for the lifecycle join; an absent row remains `unknown`. */
  val issues: List<KnownDifferenceIssue> = emptyList(),
)

/** One served preview, as an acceptance's scope names it. */
@Serializable
public data class KnownDifferenceCatalogPreview(
  val system: String,
  val id: String,
  /** Null when the preview declares no component — it can then match no acceptance. */
  val component: String? = null,
  val variant: String = "",
  val referenceIds: List<String> = emptyList(),
)

/** As an inline `application/json` payload, with `<` escaped so it cannot close the script tag. */
public fun encodeKnownDifferenceAuditContext(context: KnownDifferenceAuditContext): String =
  CONTEXT_JSON.encodeToString(context).replace("<", "\\u003c")
