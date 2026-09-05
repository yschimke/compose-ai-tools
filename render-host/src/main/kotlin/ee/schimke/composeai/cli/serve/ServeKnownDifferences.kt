package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * A catalog's committed known differences — the `compose-preview-known-differences/v1` document and
 * the mask / accepted-candidate rasters it names.
 *
 * [docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)
 * §4 is the contract. This class is **not** an implementation of it, and the distinction is the
 * whole design of the file:
 *
 * ## The host carries; it does not decide
 *
 * Every other carried artifact here — [ServeParityIssuesStore], [ServeTagIndexStore] — parses,
 * validates and drops what it cannot use. This one deliberately does none of that. The document's
 * verdicts belong to the engine, which runs in the browser and in `design-parity` from
 * [one shared implementation](../../../../../../../../scripts/design-artifacts/known-differences.mjs),
 * and a host that pre-parsed it would become a **third** implementation of the same rules — with no
 * conformance suite behind it, disagreeing about exactly the cases the contract spends its length
 * on. `document-unreadable`, `document-too-large`, a duplicated id, a schema token from the future:
 * all of those are answers the engine must be able to reach, and it can only reach them if the
 * bytes arrive intact.
 *
 * So the document is served **verbatim**, as text, and the only thing this class refuses is what a
 * *reader* must refuse. §4 names three obligations no lexical rule inside the engine can discharge,
 * and they are discharged here:
 *
 * 1. **Size before allocation.** The reader must not hand over more than the ceiling so the caller
 *    can measure it; a document or artifact past its cap is refused from the file's length, before
 *    its bytes exist in memory.
 * 2. **Containment, resolved rather than lexical.** The path grammar cannot see a symlink inside an
 *    acceptance directory. Whether a resolved path stays inside **that acceptance's own** `<id>/`
 *    directory — not merely somewhere under the artifact root — is a fact about the filesystem, and
 *    the narrower bound is the load-bearing one: a link from one acceptance's directory into
 *    another's stays under the root while letting a record read bytes it does not own, and the hash
 *    it is then checked against is the *other* record's.
 * 3. **Exact case, including the `<id>` directory.** A document spelling `MASK.png` for a committed
 *    `mask.png` opens on a case-insensitive filesystem and is `artifact-unreadable` on a Linux
 *    checkout — the same host-versus-checkout divergence the portable path grammar closes from the
 *    other side, and one no lexical rule can see, because it is a fact about which bytes the reader
 *    actually opened.
 *
 * ## Where the files live
 *
 * Published beside the issue index, under `parity/`, because they are the same kind of thing and
 * batch 02 already established that a file-only commit on the delivery branch reaches serving hosts
 * within one refresh tick without a re-render:
 * ```
 * parity/known-differences.json
 * parity/known-differences/<id>/mask.png
 * parity/known-differences/<id>/accepted-candidate.png
 * ```
 *
 * The source repo commits them under `.design-parity/`; `@design-parity/catalog-export` is what
 * carries them across.
 */
object ServeKnownDifferences {
  const val DIRECTORY = "parity"
  const val DOCUMENT_FILE = "known-differences.json"
  const val ARTIFACT_DIRECTORY = "known-differences"

  /**
   * The producer's own list of the artifacts it published, beside the document.
   *
   * Written by `@design-parity/catalog-export`, which knows exactly which files it wrote. It exists
   * so the staging path can *copy a list* rather than interpret the contract to derive one — see
   * [ServeCatalogStore.knownDifferenceArtifactPaths] for what deriving it costs.
   *
   * A **sibling** of the document rather than `known-differences/index.json`, because a record `id`
   * is a portable segment and `index.json` is one: a record so named owns the directory
   * `known-differences/index.json/`, which an index file of that name could not coexist with.
   *
   * Not part of `compose-preview-known-differences/v1` and not read by any engine. It is a
   * transport convenience between a producer and a host, which is why a catalog without it still
   * works.
   */
  const val ARTIFACT_INDEX_FILE = "known-differences-index.json"

  /** The index's schema token; a document declaring another is ignored rather than guessed at. */
  const val ARTIFACT_INDEX_SCHEMA = "compose-preview-known-difference-artifacts/v1"

  /**
   * The document's schema token, mirrored for the same one job the record cap is: the staging path
   * has to know whether the engine will read *anything* before it fetches a document's artifacts,
   * and a document declaring another schema is one the engine refuses whole.
   *
   * Not a licence to interpret the document — nothing here decides a record's verdict. Pinned to
   * the contract by [ServeKnownDifferencesTest] like the ceilings beside it.
   */
  const val SCHEMA = "compose-preview-known-differences/v1"

  /**
   * The two ceilings, versioned with the schema and **not** per-catalog settings.
   *
   * Restated from the contract rather than imported, because Kotlin cannot import a JavaScript
   * constant and a host that guessed them would refuse what the engine calls legal. They are
   * checked by [ServeKnownDifferencesTest] against `known-differences.mjs`'s `BUDGET`, which is the
   * same device the scorer's tuning mirror uses: two copies are fine while something fails when
   * they disagree.
   */
  const val MAX_DOCUMENT_BYTES = 1024 * 1024
  const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024

  /**
   * The record cap, mirrored for the one job the host has that needs it: the staging path
   * enumerates a document's artifact paths to fetch them, and a document past this cap is one the
   * engine rejects wholesale — so reading further would fetch bytes no consumer will ever evaluate.
   *
   * It bounds a fetch list, never a verdict. Checked against `BUDGET.maxAcceptances` by the same
   * mirror test the two byte ceilings use.
   */
  const val MAX_ACCEPTANCES = 256

  /**
   * §4's portable path grammar, restated: the character class, the length, and the three shapes a
   * *checkout* cannot express.
   *
   * The class alone is not the grammar, and the gap is where a traversal lives: `.` and `..` are
   * spelled entirely in permitted characters. The rest are the host-versus-checkout divergences the
   * grammar exists to close — a Windows device name, which commits fine and cannot be created under
   * that name at all; a trailing dot or space, which Windows silently strips so two committed names
   * collapse onto one file; and a segment past what any filesystem allows as a *component*, which a
   * URL-backed consumer fetches happily and `git checkout` cannot create.
   *
   * Mirrors `isPortableSegment` in `known-differences.mjs`. Two copies again, and again the reason
   * is that Kotlin cannot import one — a host that accepted what the engine refuses would serve
   * bytes for a record the engine then discards, which is only wasted work, but a host that
   * *refused* what the engine accepts is a catalog that evaluates differently here and there.
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

  /**
   * What a read produced. The three failures are the reader's own tokens from §4 — the engine turns
   * them into the record's verdict, and inventing a fourth here would be a rule with no conformance
   * case behind it.
   */
  sealed interface Artifact {
    class Bytes(val bytes: ByteArray) : Artifact

    /** The path resolves outside the acceptance's own directory. */
    data object NotContained : Artifact

    /** The file is past [MAX_ARTIFACT_BYTES], refused from its length rather than read. */
    data object TooLarge : Artifact

    /** No file, a directory, or a spelling the filesystem resolved case-insensitively. */
    data object Unreadable : Artifact
  }

  /**
   * The document's raw text, or null when the catalog publishes none.
   *
   * Null is "this catalog has no known differences", which is every catalog until it has some. A
   * document past the byte ceiling is **not** null — it is refused as text the engine will reject,
   * because "absent" and "too large" are different answers and a consumer that cannot tell them
   * apart cannot report the second one. It is refused *here* rather than handed over, so nothing
   * allocates a hostile document to discover its size.
   */
  fun document(bundleDir: File, fileSystem: FileSystem = FileSystem.SYSTEM): Document? =
    document(bundleDir.toOkioPath(), fileSystem)

  fun document(bundleRoot: Path, fileSystem: FileSystem): Document? {
    val path = bundleRoot / DIRECTORY.toPath() / DOCUMENT_FILE.toPath()
    val metadata = runCatching { fileSystem.metadataOrNull(path) }.getOrNull() ?: return null
    if (metadata.isDirectory) return null
    val size = metadata.size ?: return null
    if (size > MAX_DOCUMENT_BYTES) return Document.TooLarge
    val text = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return null
    // A second check, on the decoded bytes: `size` is the file's length and the ceiling is in UTF-8
    // bytes, which agree — but only while nothing between them re-encodes. Cheap, and it keeps the
    // rule stated where it is enforced.
    if (text.encodeToByteArray().size > MAX_DOCUMENT_BYTES) return Document.TooLarge
    return Document.Text(text)
  }

  sealed interface Document {
    data class Text(val text: String) : Document

    data object TooLarge : Document
  }

  /**
   * One artifact, addressed the way the document addresses it: `<id>/<path>`, relative to the fixed
   * `known-differences/` root.
   *
   * The `<id>` is the first segment and is a path segment in its own right, which is why a record's
   * `id` is grammar-checked at all — it names a directory on every consumer's disk.
   */
  fun artifact(
    bundleDir: File,
    relativePath: String,
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ): Artifact = artifact(bundleDir.toOkioPath(), relativePath, fileSystem)

  /**
   * The **lexical** half of [artifact]: a path this consumer will look up at all.
   *
   * Exposed because the catalog staging path needs the same answer *before* it writes a byte — a
   * producer's document names where its artifacts live, and a path the reader would refuse is a
   * path that must never reach the staging tree either. Deliberately the same predicate rather than
   * a second one: a stager stricter than the reader silently drops records the engine calls legal,
   * and a stager looser than it writes files nothing will ever open.
   *
   * The filesystem half — containment inside the record's own directory, exact case, the byte cap —
   * stays in [artifact], where there is a resolved path to ask about.
   */
  fun isLookupPath(relativePath: String): Boolean {
    val segments = relativePath.split('/')
    if (segments.size < 2) return false
    return segments.all { isPortableSegment(it) }
  }

  fun artifact(bundleRoot: Path, relativePath: String, fileSystem: FileSystem): Artifact {
    val segments = relativePath.split('/')
    // Lexical first, and cheaply: an absolute path, a backslash, a `..`, an over-long segment or a
    // Windows-reserved name never reaches the filesystem. The engine refuses these too — this is
    // the
    // host declining to *look up* what it would refuse anyway, which is what keeps a traversal from
    // being a filesystem question at all.
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
    // Contained in **this acceptance's** directory, not merely under the artifact root. A symlink
    // from `a/link` into `b/` resolves inside the root, so a root-only check would let record `a`
    // read `b`'s bytes and be hashed against `a`'s recorded digest. Walked as parents rather than
    // compared as strings, so `…/id-two` cannot pass as a child of `…/id`.
    if (generateSequence(canonical) { it.parent }.none { it == root }) return Artifact.NotContained
    // Exact case. `canonicalize` reports the on-disk spelling, so comparing the resolved tail
    // against the requested segments is the check — and it costs nothing where the filesystem is
    // already case-sensitive, which is also why CI does not exercise it. Recorded rather than
    // claimed as covered.
    if (canonical.segments.takeLast(segments.size) != segments) return Artifact.Unreadable

    val metadata =
      runCatching { fileSystem.metadataOrNull(canonical) }.getOrNull() ?: return Artifact.Unreadable
    if (metadata.isDirectory) return Artifact.Unreadable
    val size = metadata.size ?: return Artifact.Unreadable
    // From the length, before the bytes exist. Handing back an oversized file so the caller can
    // measure `.length` exhausts the process through the guard meant to prevent that.
    if (size > MAX_ARTIFACT_BYTES) return Artifact.TooLarge
    val bytes = runCatching { fileSystem.read(canonical) { readByteArray() } }.getOrNull()
    return if (bytes == null) Artifact.Unreadable else Artifact.Bytes(bytes)
  }
}

/**
 * Everything the browser engine needs to evaluate this catalog's acceptances against one
 * comparison.
 *
 * Handed over as one JSON payload rather than a scatter of `data-` attributes, for the reason the
 * annotation layers are: `overrides` and `tagIndex` are maps, and a map flattened into attributes
 * is a serialisation format two sides have to agree on for no benefit.
 *
 * **The scope fields are the locator's, and must be the same values `ServeIssueReport` writes.** An
 * acceptance matches on *every* recorded field — `system` and `component` included, which a
 * comparison-shaped mental model quietly drops, because served preview and reference ids are unique
 * only within a system and one source repo can publish several. A page that derived them
 * independently from the report would be two spellings of one identity, and the acceptance would
 * miss the very comparison it was authored on.
 */
@Serializable
data class KnownDifferenceContext(
  val documentUrl: String,
  /**
   * Prefix and suffix an artifact URL is built from: `artifactBase + "<id>/<file>" +
   * artifactQuery`.
   *
   * Two fields rather than one, because the path segment goes **between** them and the credential
   * lives in the query. A single "base" would either lose the query or force the client to splice a
   * path into a URL it did not build, which is where a hand-rolled query loses its token.
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
data class KnownDifferenceIssue(
  val repository: String,
  val number: Int,
  val state: String,
)

/**
 * The comparison's identity, as the page knows it — everything except the URLs.
 *
 * Separate from [KnownDifferenceContext] because the two are decided in different places, and the
 * split follows the rule the tag-index URL already follows: the *handler* knows the identity, the
 * *page* builds URLs, so that every link goes through one query builder. A hand-rolled query in a
 * handler is how the element picker once lost its credential on a header-authorized page and
 * silently withheld itself from a catalog publishing a perfectly good index.
 */
@Serializable
data class KnownDifferenceScope(
  val system: String,
  val component: String,
  val previewId: String,
  val referenceId: String,
  val variant: String,
  val overrides: Map<String, String> = emptyMap(),
  /**
   * The served reference's digest, when it publishes one.
   *
   * Null is not a missing check that degrades to "compare anyway": an acceptance targeting a
   * reference with no `sha256` is **refused**, because the fingerprint gate has nothing to compare
   * against and a gate that cannot have fired must not be reported as having passed.
   */
  val referenceSha256: String? = null,
  /**
   * The published tag index for this preview, or **empty when it does not describe this frame**.
   *
   * Empty is load-bearing and is why this is passed rather than fetched: an element-scoped
   * acceptance whose gate cannot run suppresses **nothing**. Handing over an index measured on a
   * different render would be worse than handing over none — it reports an element that never moved
   * as moved, with a plausible explanation attached.
   */
  val tagIndex: Map<String, WireTagEntry> = emptyMap(),
)

private val CONTEXT_JSON = Json { encodeDefaults = true }

/** As an inline `application/json` payload, with `<` escaped so it cannot close the script tag. */
fun encodeKnownDifferenceContext(context: KnownDifferenceContext): String =
  CONTEXT_JSON.encodeToString(context).replace("<", "\\u003c")

/**
 * Everything the browser engine needs to audit this catalog's acceptances **without a comparison**.
 *
 * The comparison band ([KnownDifferenceContext]) can only ever judge records scoped into the page
 * it is on, and that leaves one finding structurally unreachable: an acceptance naming a preview,
 * reference, component or variant the catalog no longer has is scoped into *no* comparison, so it
 * stays invisible in the browser while `design-parity` reports `orphaned-target` for the same
 * record. The dashboard is where the whole document can be walked, so the walk is mounted there.
 *
 * [previews] is the catalog inventory the walk resolves targets against, and its fields are the
 * **locator's**, spelled exactly as the comparison page spells them ([ServeIssueReport.Context]) —
 * an acceptance matches on every recorded field, so a second derivation of `system`, `component` or
 * `variant` here would report every acceptance in the catalog as an orphan.
 */
@Serializable
data class KnownDifferenceAuditContext(
  val documentUrl: String,
  val artifactBase: String,
  val artifactQuery: String,
  val previews: List<KnownDifferenceCatalogPreview> = emptyList(),
  /** Positive issue-state evidence for the lifecycle join; an absent row remains `unknown`. */
  val issues: List<KnownDifferenceIssue> = emptyList(),
)

/** One served preview, as an acceptance's scope names it. */
@Serializable
data class KnownDifferenceCatalogPreview(
  val system: String,
  val id: String,
  /** Null when the preview declares no component — it can then match no acceptance. */
  val component: String? = null,
  val variant: String = "",
  val referenceIds: List<String> = emptyList(),
)

/** As an inline `application/json` payload, with `<` escaped so it cannot close the script tag. */
fun encodeKnownDifferenceAuditContext(context: KnownDifferenceAuditContext): String =
  CONTEXT_JSON.encodeToString(context).replace("<", "\\u003c")
