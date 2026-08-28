package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * The parity **verdict** behind a comparison — what a parity run concluded about a render and its
 * design reference, in the categories a designer asks about: accessibility and i18n, token
 * compliance, layout drift, and whether the two frames were comparable at all.
 *
 * ## Why this is a third manifest and not a field on the other two
 *
 * The compare page already transports two producer-authored artifacts, and this is deliberately
 * neither of them:
 *
 * - [DesignReference] carries how far apart the two pictures are — one number, scored off pixels.
 *   It says a comparison is 64% matched and cannot say why.
 * - [DesignAnnotation] carries what each side *is* — the padding, the type style — anchored to a
 *   region. It describes one panel at a time and asserts nothing about the pair.
 *
 * A finding is the sentence between them: a claim about the PAIR, with a severity, that a reader
 * acts on. "spacing.padding: 24 vs spec 12" is not derivable from either manifest — the annotation
 * layer reports both numbers without knowing which one the spec asserts, and the score reports a
 * percentage that a padding change and a colour change move identically.
 *
 * ## Anchors are bounds, not labels
 *
 * design-parity's own HTML report ties a layout finding back to a node by matching its LABEL
 * against the semantics tree, which works there because the report renders the tree it matched
 * against, in the same process, moments later. Here the two are separated by a publish, a branch
 * and a server: the render can be re-rendered, the reference re-exported, and a label ("Label
 * text") is neither unique nor stable. So a finding names its regions in the same pixel space
 * [DesignAnnotation.bounds] uses — the annotated image's own — and the page draws them with the
 * placement code it already has. A finding with no anchors is still a finding; it simply reads as
 * prose rather than lighting up a box.
 *
 * ## Scoped to a reference, when the producer knows which
 *
 * A preview may carry several references (a component's `ideal` and `layout` boards, a variant
 * set), and the compare page shows exactly one at a time. A flat list per preview would print the
 * `ideal` board's token drift under the `layout` board's panels, which is a wrong claim rather than
 * a missing one. [ParityFindingSet.referenceId] scopes a set; an absent one applies to every
 * reference, which is the correct reading for a check that describes the render alone (a touch
 * target, a contrast ratio) and the only shape a producer with one reference per preview has to
 * think about.
 *
 * Fail-soft throughout, like [ServeAnnotationStore] and [ServeDesignReferenceStore]: a parity
 * verdict is an enhancement over a comparison that already works, and a producer bug in it must
 * cost the reader the panel it describes, never the comparison.
 */
@Serializable
data class ParityFindings(
  val schema: String = SCHEMA,
  val generatedAt: String? = null,
  /** Finding sets over a preview's rendered frame, keyed by exact serve/catalog preview id. */
  val previews: Map<String, List<ParityFindingSet>> = emptyMap(),
) {
  companion object {
    const val SCHEMA = "compose-preview-parity-findings/v1"
    const val DIRECTORY = "parity"
    const val FILE = "findings.json"
  }
}

/** One run's conclusion about one (preview, reference) pair. */
@Serializable
data class ParityFindingSet(
  /** The [DesignReference.id] this verdict compared against; null ⇒ it applies to any of them. */
  val referenceId: String? = null,
  /** `pass` / `warn` / `fail`, as the run concluded. Unknown values are dropped, not defaulted. */
  val status: String? = null,
  /** Where the producing run's own report lives, when it published one. */
  val reportUrl: String? = null,
  val findings: List<ParityFinding> = emptyList(),
)

/** One observation, in the diff engine's own vocabulary. */
@Serializable
data class ParityFinding(
  /**
   * One of [ParityFindingKind.KNOWN]. An unknown kind is dropped rather than shown uncategorised.
   */
  val kind: String,
  /** One of [ParityFindingSeverity.KNOWN]. */
  val severity: String,
  /** Human-readable, one line — the sentence the reader acts on. */
  val message: String,
  /**
   * Structured payload behind the sentence. Rendered as a delta row when it carries
   * `expected`/`actual`, and as the finding's title otherwise, so a producer's extra keys are
   * transported rather than dropped.
   */
  val detail: Map<String, String> = emptyMap(),
  /** Where on the two panels this finding is. Empty ⇒ the finding reads as prose. */
  val anchors: List<ParityAnchor> = emptyList(),
)

/** A region of one panel a finding points at, in that panel image's own pixel space. */
@Serializable
data class ParityAnchor(
  /** `reference` or `actual`; anything else is dropped. */
  val side: String,
  val bounds: AnnotationBounds,
  /** Optional caption for the highlight, e.g. the node the finding is about. */
  val label: String? = null,
)

/**
 * The categories a finding can be about, mirroring `@design-parity/core`'s `FindingKind`.
 *
 * Kept as strings rather than an enum because this is a wire type read from a file a different
 * repository writes: an enum would make a kind this build has not heard of a DECODE failure for the
 * whole record, and the point of the fail-soft posture is that a newer producer costs this reader
 * only the rows it cannot place.
 */
object ParityFindingKind {
  const val A11Y = "a11y"
  const val I18N = "i18n"
  const val CONTRAST = "contrast"
  const val TOKEN = "token"
  const val LAYOUT = "layout"
  const val SEMANTIC = "semantic"
  const val VISUAL = "visual"
  const val PAIRING = "pairing"

  val KNOWN = setOf(A11Y, I18N, CONTRAST, TOKEN, LAYOUT, SEMANTIC, VISUAL, PAIRING)
}

object ParityFindingSeverity {
  const val INFO = "info"
  const val WARN = "warn"
  const val ERROR = "error"

  val KNOWN = setOf(INFO, WARN, ERROR)

  /** Worst-first, so a group leads with the row that decides its status. */
  fun rank(value: String): Int =
    when (value) {
      ERROR -> 0
      WARN -> 1
      else -> 2
    }
}

/**
 * The groups the compare page prints, in the order it prints them.
 *
 * Ordered by what a reader can act on, which is design-parity's own reporting order (Principle 2:
 * a11y and i18n first, then tokens, then pixels) rather than by severity: a `warn` that a label
 * will truncate in German is a bug in the component, while an `error` on 35% of pixels differing is
 * usually the two frames being different sizes.
 */
enum class ParityFindingGroup(val id: String, val title: String, val kinds: Set<String>) {
  ACCESSIBILITY(
    "a11y",
    "Accessibility & i18n",
    setOf(ParityFindingKind.A11Y, ParityFindingKind.I18N, ParityFindingKind.CONTRAST),
  ),
  TOKENS("tokens", "Token compliance", setOf(ParityFindingKind.TOKEN)),
  LAYOUT("layout", "Layout", setOf(ParityFindingKind.LAYOUT, ParityFindingKind.SEMANTIC)),
  PAIRING("pairing", "Pairing", setOf(ParityFindingKind.PAIRING)),
  VISUAL("visual", "Visual", setOf(ParityFindingKind.VISUAL));

  companion object {
    fun of(kind: String): ParityFindingGroup? = entries.firstOrNull { kind in it.kinds }
  }
}

/**
 * The compare page's client payload: every anchored finding's regions, keyed by the id the
 * server-rendered row carries.
 *
 * Only the ANCHORS ride here. The findings themselves are rendered into the page as HTML, because
 * they are prose a reader needs with or without script — a parity verdict that only appears once a
 * bundle has downloaded and upgraded is one the reader cannot cite, quote or find with the
 * browser's own search. The geometry is the half that is useless without script, so it is the half
 * that travels as data.
 */
@Serializable
data class ParityAnchorPayload(val findings: Map<String, List<ParityAnchor>> = emptyMap())

private val PARITY_FINDINGS_JSON = Json { encodeDefaults = false }

/**
 * Encode for embedding in a `<script type="application/json">` block, exactly as
 * [encodeAnnotationPayload] does and for the same reason: entities are not decoded inside a script
 * element, so HTML-escaping would reach `JSON.parse` verbatim and throw, while an unescaped
 * `</script>` inside a label would end the block early.
 */
fun encodeParityAnchorPayload(payload: ParityAnchorPayload): String =
  PARITY_FINDINGS_JSON.encodeToString(payload).replace("<", "\\u003c")

/**
 * Validated, read-only view of a bundle/catalog's `parity/findings.json`.
 *
 * Every cap below exists because this file is authored by another repository and rendered into a
 * page: an unbounded finding list is a page nobody can scroll, and an unbounded message is a layout
 * break rather than information.
 */
class ServeParityFindingStore
private constructor(private val byPreview: Map<String, List<ParityFindingSet>>) {

  val isEmpty: Boolean = byPreview.isEmpty()

  /** Every set published for [previewId], scoped and unscoped alike. */
  fun forPreview(previewId: String): List<ParityFindingSet> = byPreview[previewId].orEmpty()

  /**
   * The sets that describe the comparison on screen: those naming [referenceId], plus the unscoped
   * ones that describe the render whichever reference it is being read against.
   */
  fun forComparison(previewId: String, referenceId: String): List<ParityFindingSet> =
    forPreview(previewId).filter { it.referenceId == null || it.referenceId == referenceId }

  companion object {
    private const val MAX_PREVIEWS = 5000
    private const val MAX_SETS_PER_PREVIEW = 20
    private const val MAX_FINDINGS_PER_SET = 200
    private const val MAX_ANCHORS_PER_FINDING = 40
    private const val MAX_DETAIL_KEYS = 24
    private const val MAX_MESSAGE = 400
    private const val MAX_DETAIL_VALUE = 200
    private val STATUSES = setOf("pass", "warn", "fail")
    private val ID = Regex("[^\\p{Cc}]{1,300}")
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Empty store — a catalog that publishes no parity verdict at all. */
    val EMPTY = ServeParityFindingStore(emptyMap())

    fun load(
      bundleDir: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): ServeParityFindingStore {
      val path =
        bundleDir.toOkioPath() / ParityFindings.DIRECTORY.toPath() / ParityFindings.FILE.toPath()
      val raw =
        runCatching {
          if (!fileSystem.exists(path)) return@runCatching null
          JSON.decodeFromString<ParityFindings>(fileSystem.read(path) { readUtf8() })
        }
          .getOrNull() ?: return EMPTY
      return sanitize(raw)
    }

    fun sanitize(raw: ParityFindings): ServeParityFindingStore {
      if (raw.schema != ParityFindings.SCHEMA) return EMPTY
      val previews =
        raw.previews.entries
          .asSequence()
          .filter { (previewId, _) -> ID.matches(previewId) }
          .take(MAX_PREVIEWS)
          .mapNotNull { (previewId, sets) ->
            val kept =
              sets.take(MAX_SETS_PER_PREVIEW).mapNotNull(::sanitizeSet).takeIf { it.isNotEmpty() }
            kept?.let { previewId to it }
          }
          .toMap()
      return ServeParityFindingStore(previews)
    }

    private fun sanitizeSet(raw: ParityFindingSet): ParityFindingSet? {
      val findings =
        raw.findings.take(MAX_FINDINGS_PER_SET).mapNotNull(::sanitizeFinding).sortedBy {
          ParityFindingSeverity.rank(it.severity)
        }
      if (findings.isEmpty()) return null
      return ParityFindingSet(
        referenceId = raw.referenceId?.trim()?.takeIf(ID::matches),
        status = raw.status?.trim()?.lowercase()?.takeIf(STATUSES::contains),
        // Only an absolute https link, and only as a link: this string is written by another
        // repository and lands in an `href`, so a `javascript:` or a protocol-relative host would
        // be a stored redirect out of the catalog on a page the reader trusts.
        reportUrl =
          raw.reportUrl?.trim()?.takeIf { it.startsWith("https://") && it.length <= 2000 },
        findings = findings,
      )
    }

    private fun sanitizeFinding(raw: ParityFinding): ParityFinding? {
      val kind =
        raw.kind.trim().lowercase().takeIf(ParityFindingKind.KNOWN::contains) ?: return null
      val severity =
        raw.severity.trim().lowercase().takeIf(ParityFindingSeverity.KNOWN::contains) ?: return null
      val message =
        raw.message.trim().takeIf { it.isNotEmpty() }?.let { clamp(it, MAX_MESSAGE) } ?: return null
      return ParityFinding(
        kind = kind,
        severity = severity,
        message = message,
        detail =
          raw.detail.entries
            .asSequence()
            .filter { (key, _) -> key.isNotBlank() && key.length <= 80 }
            .take(MAX_DETAIL_KEYS)
            .associate { (key, value) -> key.trim() to clamp(value.trim(), MAX_DETAIL_VALUE) },
        anchors = raw.anchors.take(MAX_ANCHORS_PER_FINDING).filter(::isUsable),
      )
    }

    /**
     * A box with no area cannot be drawn and a negative origin paints outside the panel — the same
     * rule [ServeAnnotationStore] applies, for the same reason: both indicate a producer bug rather
     * than something to render badly.
     */
    private fun isUsable(anchor: ParityAnchor): Boolean =
      (anchor.side == SIDE_REFERENCE || anchor.side == SIDE_ACTUAL) &&
        anchor.bounds.width > 0 &&
        anchor.bounds.height > 0 &&
        anchor.bounds.x >= 0 &&
        anchor.bounds.y >= 0

    const val SIDE_REFERENCE = "reference"
    const val SIDE_ACTUAL = "actual"

    private fun clamp(value: String, max: Int): String =
      if (value.length <= max) value else value.take(max - 1) + "…"
  }
}
