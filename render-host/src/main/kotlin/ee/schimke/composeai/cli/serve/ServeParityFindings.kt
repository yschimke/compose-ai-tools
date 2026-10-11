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
 * The parity verdict behind a comparison: what a parity run concluded about a render and its design
 * reference (accessibility/i18n, token compliance, layout drift, comparability).
 *
 * A separate manifest because it is a claim about the pair, with a severity: [DesignReference] only
 * scores pixel distance and [DesignAnnotation] describes one panel at a time.
 *
 * Anchors are pixel bounds in the annotated image's space (as [DesignAnnotation.bounds]), not
 * labels, since labels are neither unique nor stable across a publish. A finding without anchors is
 * still shown, as prose. [ParityFindingSet.referenceId] scopes a set to one reference; absent means
 * it applies to every reference (render-only checks).
 *
 * Fail-soft like [ServeAnnotationStore]: a producer bug costs the panel, never the comparison.
 */
@Serializable
public data class ParityFindings(
  val schema: String = SCHEMA,
  val generatedAt: String? = null,
  /** Finding sets over a preview's rendered frame, keyed by exact serve/catalog preview id. */
  val previews: Map<String, List<ParityFindingSet>> = emptyMap(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-parity-findings/v1"
    public const val DIRECTORY: String = "parity"
    public const val FILE: String = "findings.json"
  }
}

/** One run's conclusion about one (preview, reference) pair. */
@Serializable
public data class ParityFindingSet(
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
public data class ParityFinding(
  /** One of [ParityFindingKind.KNOWN]; unknown kinds are dropped. */
  val kind: String,
  /** One of [ParityFindingSeverity.KNOWN]. */
  val severity: String,
  /** Human-readable, one line — the sentence the reader acts on. */
  val message: String,
  /**
   * Structured payload: a delta row when it has `expected`/`actual`, otherwise the finding's title.
   */
  val detail: Map<String, String> = emptyMap(),
  /** Where on the two panels this finding is. Empty ⇒ the finding reads as prose. */
  val anchors: List<ParityAnchor> = emptyList(),
)

/** A region of one panel a finding points at, in that panel image's own pixel space. */
@Serializable
public data class ParityAnchor(
  /** `reference` or `actual`; anything else is dropped. */
  val side: String,
  val bounds: AnnotationBounds,
  /** Optional caption for the highlight, e.g. the node the finding is about. */
  val label: String? = null,
)

/**
 * Finding categories, mirroring `@design-parity/core`'s `FindingKind`. Strings, not an enum: this
 * is read from another repository's file, and an unknown kind must cost only its rows, not
 * decoding.
 */
public object ParityFindingKind {
  public const val A11Y: String = "a11y"
  public const val I18N: String = "i18n"
  public const val CONTRAST: String = "contrast"
  public const val TOKEN: String = "token"
  public const val LAYOUT: String = "layout"
  public const val SEMANTIC: String = "semantic"
  public const val VISUAL: String = "visual"
  public const val PAIRING: String = "pairing"

  public val KNOWN: Set<String> =
    setOf(A11Y, I18N, CONTRAST, TOKEN, LAYOUT, SEMANTIC, VISUAL, PAIRING)
}

public object ParityFindingSeverity {
  public const val INFO: String = "info"
  public const val WARN: String = "warn"
  public const val ERROR: String = "error"

  public val KNOWN: Set<String> = setOf(INFO, WARN, ERROR)

  /** Worst-first, so a group leads with the row that decides its status. */
  public fun rank(value: String): Int =
    when (value) {
      ERROR -> 0
      WARN -> 1
      else -> 2
    }
}

/**
 * The groups the compare page prints, in order: what a reader can act on first (a11y and i18n, then
 * tokens, then pixels), following design-parity's reporting order rather than severity.
 */
public enum class ParityFindingGroup(
  public val id: String,
  public val title: String,
  public val kinds: Set<String>,
) {
  ACCESSIBILITY(
    "a11y",
    "Accessibility & i18n",
    setOf(ParityFindingKind.A11Y, ParityFindingKind.I18N, ParityFindingKind.CONTRAST),
  ),
  TOKENS("tokens", "Token compliance", setOf(ParityFindingKind.TOKEN)),
  LAYOUT("layout", "Layout", setOf(ParityFindingKind.LAYOUT, ParityFindingKind.SEMANTIC)),
  PAIRING("pairing", "Pairing", setOf(ParityFindingKind.PAIRING)),
  VISUAL("visual", "Visual", setOf(ParityFindingKind.VISUAL));

  public companion object {
    public fun of(kind: String): ParityFindingGroup? = entries.firstOrNull { kind in it.kinds }
  }
}

/**
 * The compare page's client payload: anchored findings' regions keyed by row id. Findings
 * themselves are server-rendered HTML so they're readable and searchable without script; only the
 * geometry travels as data.
 */
@Serializable
public data class ParityAnchorPayload(val findings: Map<String, List<ParityAnchor>> = emptyMap())

private val PARITY_FINDINGS_JSON = Json { encodeDefaults = false }

/**
 * Encode for a `<script type="application/json">` block, as [encodeAnnotationPayload] does
 * (entities aren't decoded inside script, and `</script>` must not end the block).
 */
public fun encodeParityAnchorPayload(payload: ParityAnchorPayload): String =
  PARITY_FINDINGS_JSON.encodeToString(payload).replace("<", "\\u003c")

/**
 * Validated, read-only view of a bundle/catalog's `parity/findings.json`.
 *
 * Decoded record by record through raw [JsonElement]s (as [ServeDesignReferenceStore] does), so one
 * malformed record can't blank the whole catalog. All sizes are capped because another repository
 * authors the file and it is rendered into pages.
 */
public class ServeParityFindingStore
private constructor(private val byPreview: Map<String, List<ParityFindingSet>>) {

  public val isEmpty: Boolean = byPreview.isEmpty()

  /** Every set published for [previewId], scoped and unscoped alike. */
  public fun forPreview(previewId: String): List<ParityFindingSet> = byPreview[previewId].orEmpty()

  /**
   * The sets for the comparison on screen: those naming [referenceId] plus unscoped ones. Page
   * budgets are spent here, since reference-scoped sets are never shown together.
   */
  public fun forComparison(previewId: String, referenceId: String): List<ParityFindingSet> =
    spendPageBudget(
      forPreview(previewId).filter { it.referenceId == null || it.referenceId == referenceId }
    )

  public companion object {
    private const val MAX_PREVIEWS = 5000
    private const val MAX_SETS_PER_PREVIEW = 20
    private const val MAX_FINDINGS_PER_SET = 200

    /** Findings one comparison may put on a page, across every set it shows. */
    private const val MAX_FINDINGS_PER_PREVIEW = 300
    private const val MAX_ANCHORS_PER_FINDING = 40

    /**
     * Anchors one comparison may draw: each is a positioned client element, so it gets a tighter
     * budget than rows.
     */
    private const val MAX_ANCHORS_PER_PREVIEW = 600

    /**
     * Findings one preview may keep across all references, bounding memory and staged output. Far
     * above the page budget, since which board a reader asks for isn't known at load.
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
    public val EMPTY: ServeParityFindingStore = ServeParityFindingStore(emptyMap())

    /**
     * The document as read, with records left as raw JSON at every level, so a malformed preview,
     * set, finding or anchor only costs itself. (A typed `Map<String, List<JsonElement>>` would
     * still throw on one bad entry.)
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
      /** Raw values, then coerced: `16` and `"16"` are the same fact. */
      val detail: Map<String, JsonElement> = emptyMap(),
      val anchors: List<JsonElement> = emptyList(),
    )

    public fun load(
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
     * Parse and validate a manifest, or null when nothing survives. Shared with
     * [ServeCatalogStore]'s staging, which writes what this returns, so published catalogs are
     * validated by the reading code.
     */
    public fun sanitizeDocument(text: String): ParityFindings? {
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

    /** One preview's sets, each validated alone; page budgets are spent in [forComparison]. */
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
      // A supplied but invalid id drops the set: treating it as absent would show the verdict under
      // every reference.
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
      // Keep an empty set only when it declares a status ("looked and found nothing" ≠ "never ran")
      // and the producer's array was also empty. If all findings were rejected here, a "Pass" would
      // turn an unreadable report into a false clean verdict.
      if (findings.isEmpty() && (status == null || raw.findings.isNotEmpty())) return null
      return ParityFindingSet(
        referenceId = referenceId,
        status = status,
        // Absolute https only: this lands in an `href` and is authored elsewhere.
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
            // Clamped: it is serialized into every comparison page and drawn as a `nowrap` caption.
            .map { anchor ->
              anchor.copy(
                label =
                  anchor.label?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, MAX_LABEL) }
              )
            },
      )
    }

    /** A detail value as text (numbers as written, `16` not `16.0`), or null for objects/arrays. */
    private fun detailValue(value: JsonElement): String? {
      val primitive = value as? JsonPrimitive ?: return null
      if (primitive is JsonNull) return null
      return clamp(primitive.content.trim(), MAX_DETAIL_VALUE).takeIf { it.isNotEmpty() }
    }

    /**
     * Hold one comparison's sets to the page budgets, keeping the worst findings (sets are
     * severity-ordered first).
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
        // A set emptied by the budget is dropped, never shown as "Pass" over a report that exists.
        if (findings.isEmpty() && set.findings.isNotEmpty()) null else set.copy(findings = findings)
      }
    }

    /**
     * Zero-area boxes and negative origins indicate a producer bug (same rule as
     * [ServeAnnotationStore]).
     */
    private fun isUsable(anchor: ParityAnchor): Boolean =
      (anchor.side == SIDE_REFERENCE || anchor.side == SIDE_ACTUAL) &&
        anchor.bounds.width > 0 &&
        anchor.bounds.height > 0 &&
        anchor.bounds.x >= 0 &&
        anchor.bounds.y >= 0

    public const val SIDE_REFERENCE: String = "reference"
    public const val SIDE_ACTUAL: String = "actual"

    private fun clamp(value: String, max: Int): String =
      if (value.length <= max) value else value.take(max - 1) + "…"
  }
}
