package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Every record is decoded ONE AT A TIME, through raw [JsonElement]s, for the reason
 * [ServeDesignReferenceStore] does the same: decoding the whole document in one call makes any
 * single malformed record — a finding with no `message`, a number where a detail string belongs, a
 * `bounds` missing a field — throw while parsing the envelope, and the whole catalog's verdict goes
 * dark on every page. That is the opposite of the per-record salvage this class promises, and the
 * validation below never gets to run.
 *
 * Every cap exists because this file is authored by another repository and rendered into a page: an
 * unbounded finding list is a page nobody can scroll, and an unbounded message is a layout break
 * rather than information. The per-preview aggregates are the ones that bind — nested ceilings
 * multiply, so twenty sets of two hundred findings is four thousand rows and a browser that stops
 * responding while the anchors are placed.
 */
class ServeParityFindingStore
private constructor(private val byPreview: Map<String, List<ParityFindingSet>>) {

  val isEmpty: Boolean = byPreview.isEmpty()

  /** Every set published for [previewId], scoped and unscoped alike. */
  fun forPreview(previewId: String): List<ParityFindingSet> = byPreview[previewId].orEmpty()

  /**
   * The sets that describe the comparison on screen: those naming [referenceId], plus the unscoped
   * ones that describe the render whichever reference it is being read against.
   *
   * The page budgets are spent HERE, not at load, because they describe a page and only this
   * selection is one. Reference-scoped sets are mutually exclusive on screen — a preview with three
   * boards shows one of them at a time — so charging them against a shared allowance let the first
   * two exhaust it and left the third comparison with no verdict at all, for a page that was never
   * going to render the other two.
   */
  fun forComparison(previewId: String, referenceId: String): List<ParityFindingSet> =
    spendPageBudget(
      forPreview(previewId).filter { it.referenceId == null || it.referenceId == referenceId }
    )

  companion object {
    private const val MAX_PREVIEWS = 5000
    private const val MAX_SETS_PER_PREVIEW = 20
    private const val MAX_FINDINGS_PER_SET = 200

    /**
     * What ONE comparison may put on a page, across every set it shows.
     *
     * The per-set ceiling alone is not a limit on anything a reader meets: the page renders every
     * set for a preview at once, so twenty of them is twenty times whatever that ceiling says. This
     * is the number that describes the page — beyond it a verdict has stopped being something
     * anyone reads and become something that has to be scrolled past.
     */
    private const val MAX_FINDINGS_PER_PREVIEW = 300
    private const val MAX_ANCHORS_PER_FINDING = 40

    /**
     * And what one comparison may DRAW, which is the tighter constraint of the two.
     *
     * A finding row is a few hundred bytes of HTML; an anchor is an element the client creates,
     * positions on every reflow, and repositions on every resize. The page stays readable long
     * after it has stopped being responsive, so the boxes get their own budget rather than riding
     * the row count.
     */
    private const val MAX_ANCHORS_PER_PREVIEW = 600

    /**
     * What one preview may KEEP, across every reference it publishes a verdict for.
     *
     * A second budget, because it bounds a different thing. The page allowance above has to be
     * spent at READ time — reference-scoped sets are mutually exclusive on screen, so charging them
     * against one allowance at load blanks boards a page was never going to show together — and
     * that leaves the store itself unbounded. The store is not free: it is held in memory by
     * `ServeBundleHost` and reserialized into the staging tree by `ServeCatalogStore`, so the
     * nested ceilings alone let one preview keep four thousand findings and a hundred and sixty
     * thousand anchors no page can ever reach.
     *
     * Deliberately far above the page budget: several boards' worth of a real verdict passes
     * untouched, since which of them a reader asks for is not known here. This bounds disk and
     * heap, not what anyone reads.
     */
    private const val MAX_STORED_FINDINGS_PER_PREVIEW = 1000
    private const val MAX_STORED_ANCHORS_PER_PREVIEW = 2000
    private const val MAX_DETAIL_KEYS = 24
    private const val MAX_MESSAGE = 400

    /** An anchor caption names an element; anything longer is not a name. */
    private const val MAX_LABEL = 120
    private const val MAX_DETAIL_VALUE = 200
    private val STATUSES = setOf("pass", "warn", "fail")
    private val ID = Regex("[^\\p{Cc}]{1,300}")
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Empty store — a catalog that publishes no parity verdict at all. */
    val EMPTY = ServeParityFindingStore(emptyMap())

    /**
     * The document as read from disk, with its records left as raw JSON.
     *
     * [ParityFindings] is the schema producers write against; this is what a fail-soft READER
     * needs. Raw at EVERY level, because a producer can be wrong at any of them and the blast
     * radius has to stop at the record that is wrong: a preview whose value is not a list must not
     * cost the catalog its other previews, a malformed SET must not cost the preview its other
     * sets, a malformed FINDING must not cost the set, and a malformed ANCHOR must not cost the
     * finding its sentence. Typing `previews` as `Map<String, List<JsonElement>>` looks tolerant
     * and is not — one entry holding a string throws while the map is decoded, which is the whole
     * document again.
     */
    @Serializable
    private data class RawManifest(
      val schema: String = ParityFindings.SCHEMA,
      val previews: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    private data class RawSet(
      val referenceId: JsonElement? = null,
      val status: String? = null,
      val reportUrl: String? = null,
      val findings: List<JsonElement> = emptyList(),
    )

    @Serializable
    private data class RawFinding(
      val kind: String = "",
      val severity: String = "",
      val message: String = "",
      /**
       * Values as raw JSON, then coerced. A producer that emits `"expected": 16` rather than `"16"`
       * is writing the same fact; refusing it would drop the delta row over a type the wire format
       * never needed to be strict about.
       */
      val detail: Map<String, JsonElement> = emptyMap(),
      val anchors: List<JsonElement> = emptyList(),
    )

    fun load(
      bundleDir: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): ServeParityFindingStore {
      val path =
        bundleDir.toOkioPath() / ParityFindings.DIRECTORY.toPath() / ParityFindings.FILE.toPath()
      val text =
        runCatching {
          if (!fileSystem.exists(path)) return@runCatching null
          fileSystem.read(path) { readUtf8() }
        }
          .getOrNull() ?: return EMPTY
      val document = sanitizeDocument(text) ?: return EMPTY
      return ServeParityFindingStore(document.previews)
    }

    /**
     * Parse and validate a manifest, returning the cleaned document or null when nothing survives.
     *
     * Shared with [ServeCatalogStore]'s staging path so a published catalog is validated by the
     * very code that will later read it: staging writes what this returns, not what the branch
     * said, and a manifest that could not survive the reader never reaches the staged tree at all.
     */
    fun sanitizeDocument(text: String): ParityFindings? {
      val raw = runCatching { JSON.decodeFromString<RawManifest>(text) }.getOrNull() ?: return null
      if (raw.schema != ParityFindings.SCHEMA) return null
      val previews =
        raw.previews.entries
          .asSequence()
          .filter { (previewId, _) -> ID.matches(previewId) }
          .take(MAX_PREVIEWS)
          .mapNotNull { (previewId, value) ->
            val sets = value as? JsonArray ?: return@mapNotNull null
            sanitizePreview(sets)?.let { previewId to it }
          }
          .toMap()
      if (previews.isEmpty()) return null
      return ParityFindings(previews = previews)
    }

    /**
     * One preview's sets, each validated on its own. The page budgets are spent in [forComparison],
     * which is the only place that knows what one page will show.
     */
    private fun sanitizePreview(sets: JsonArray): List<ParityFindingSet>? {
      val kept =
        sets
          .take(MAX_SETS_PER_PREVIEW)
          .mapNotNull { element ->
            runCatching { JSON.decodeFromJsonElement<RawSet>(element) }.getOrNull()
          }
          .mapNotNull(::sanitizeSet)
      return spendBudget(kept, MAX_STORED_FINDINGS_PER_PREVIEW, MAX_STORED_ANCHORS_PER_PREVIEW)
        .takeIf { it.isNotEmpty() }
    }

    private fun sanitizeSet(raw: RawSet): ParityFindingSet? {
      // A supplied id that does not validate is NOT the same as an absent one, and treating it as
      // one is the worst reading available: `forComparison` takes null as "describes the render
      // whichever reference it is read against", so a producer's malformed scope would print this
      // verdict under every other reference's panels — a plausible, wrong claim rather than a
      // missing one. Absent stays unscoped; present-and-broken drops the set.
      val referenceId =
        when (val supplied = raw.referenceId) {
          null,
          JsonNull -> null
          else -> {
            val text = (supplied as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (text == null || !ID.matches(text)) return null
            text
          }
        }
      val findings =
        raw.findings
          .take(MAX_FINDINGS_PER_SET)
          .mapNotNull { element ->
            runCatching { JSON.decodeFromJsonElement<RawFinding>(element) }.getOrNull()
          }
          .mapNotNull(::sanitizeFinding)
          .sortedBy { ParityFindingSeverity.rank(it.severity) }
      val status = raw.status?.trim()?.lowercase()?.takeIf(STATUSES::contains)
      // A set with no findings is KEPT when it declares a status, and it is not an empty record: a
      // run that looked and found nothing is a different fact from a catalog nobody ran, and only
      // the first can say "Pass". Dropping it made the two indistinguishable on the page. With
      // neither findings nor a status there is nothing to say, and the set goes.
      // …but only when the producer's array was ALSO empty. A set whose findings were all
      // rejected here — a message this reader could not use, a kind it does not know — has a
      // report behind it that this build cannot show, and printing "Pass · No findings." over it
      // would turn a manifest we failed to read into a reassuring clean verdict. That is the one
      // direction this panel must never fail in: silence says "unknown", a green chip says
      // "checked".
      if (findings.isEmpty() && (status == null || raw.findings.isNotEmpty())) return null
      return ParityFindingSet(
        referenceId = referenceId,
        status = status,
        // Only an absolute https link, and only as a link: this string is written by another
        // repository and lands in an `href`, so a `javascript:` or a protocol-relative host would
        // be a stored redirect out of the catalog on a page the reader trusts.
        reportUrl =
          raw.reportUrl?.trim()?.takeIf { it.startsWith("https://") && it.length <= 2000 },
        findings = findings,
      )
    }

    private fun sanitizeFinding(raw: RawFinding): ParityFinding? {
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
            .mapNotNull { (key, value) -> detailValue(value)?.let { key.trim() to it } }
            .take(MAX_DETAIL_KEYS)
            .toMap(),
        anchors =
          raw.anchors
            .take(MAX_ANCHORS_PER_FINDING)
            .mapNotNull { runCatching { JSON.decodeFromJsonElement<ParityAnchor>(it) }.getOrNull() }
            .filter(::isUsable)
            // Clamped like a message and a detail value, and for a sharper reason than either:
            // this string is serialized into every comparison page AND drawn by the client as a
            // `white-space: nowrap` caption, so an unbounded one is both a multi-megabyte response
            // and a label wider than the screen it is meant to annotate.
            .map { anchor ->
              anchor.copy(
                label =
                  anchor.label?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, MAX_LABEL) }
              )
            },
      )
    }

    /**
     * A detail value as text, or null when it is not a value at all.
     *
     * Primitives are printed as written — a number stays `16`, not `16.0` — and an object or array
     * is dropped rather than stringified: a hover card is a readout, and JSON in it is noise the
     * reader cannot act on.
     */
    private fun detailValue(value: JsonElement): String? {
      val primitive = value as? JsonPrimitive ?: return null
      if (primitive is JsonNull) return null
      return clamp(primitive.content.trim(), MAX_DETAIL_VALUE).takeIf { it.isNotEmpty() }
    }

    /**
     * Hold one comparison's sets to what a page can show and draw, worst-severity-first.
     *
     * Trimmed rather than dropped: the budget is spent in the order the producer wrote, and a
     * verdict past it has already said far more than a reader will get through. Findings are
     * severity-ordered within a set before this runs, so what survives is the worst of what was
     * published rather than whatever happened to be written first.
     */
    private fun spendPageBudget(sets: List<ParityFindingSet>): List<ParityFindingSet> =
      spendBudget(sets, MAX_FINDINGS_PER_PREVIEW, MAX_ANCHORS_PER_PREVIEW)

    /** Hold a list of sets to a finding and anchor allowance, worst-severity-first. */
    private fun spendBudget(
      sets: List<ParityFindingSet>,
      maxFindings: Int,
      maxAnchors: Int,
    ): List<ParityFindingSet> {
      var findingsLeft = maxFindings
      var anchorsLeft = maxAnchors
      // Nothing to spend against: the common shape, and worth not rebuilding every set for.
      if (
        sets.sumOf { it.findings.size } <= findingsLeft &&
          sets.sumOf { set -> set.findings.sumOf { it.anchors.size } } <= anchorsLeft
      ) {
        return sets
      }
      return sets.mapNotNull { set ->
        val findings =
          set.findings.take(findingsLeft.coerceAtLeast(0)).map { finding ->
            val room = anchorsLeft.coerceAtLeast(0)
            anchorsLeft -= finding.anchors.size.coerceAtMost(room)
            if (finding.anchors.size <= room) finding
            else finding.copy(anchors = finding.anchors.take(room))
          }
        findingsLeft -= findings.size
        // A set this budget emptied is DROPPED, never trimmed to a status-only record. It reached
        // here carrying findings, so its "Pass" would be printed over a report that exists and
        // that this build then discarded — the same false-clean verdict a rejected findings array
        // produces, arriving one level up. Only a set the producer wrote empty may say "Pass" with
        // nothing under it, and such a set is empty before this runs rather than because of it.
        if (findings.isEmpty() && set.findings.isNotEmpty()) null else set.copy(findings = findings)
      }
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
