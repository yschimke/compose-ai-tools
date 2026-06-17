package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SemanticsDelta
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import ee.schimke.composeai.data.theme.ThemeDelta
import ee.schimke.composeai.data.theme.ThemeDiff
import ee.schimke.composeai.data.theme.ThemePayload
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// `history/diff mode=data` — the data-product diff over two history entries
// (issue #1873). Where PIXEL mode answers "what moved on screen" and SEMANTICS
// mode diffs the captured `compose/semantics` tree, DATA mode rolls the three
// archived **data** products into one versioned delta:
//
//   - `semantics` — reuses `SemanticsDiff` (compose-semantics-diff/v1).
//   - `a11y`      — ATF findings added / removed / changed, keyed by the stable
//                   hierarchy `ref` from #1784 (a11y-diff/v1).
//   - `theme`     — Material 3 resolved-token deltas (compose-theme-diff/v1).
//
// Each section is computed only when BOTH entries carry that product; a section
// is null when neither (or only one) side has it, so a consumer can tell "not
// captured" apart from "captured and identical" (an empty-but-present section).
// The a11y mirror types below are structurally-identical copies of the
// `:data-a11y-core` models — that module is an Android library the plain-JVM
// `:daemon:core` can't depend on, and the a11y kdoc explicitly sanctions JVM
// consumers keeping their own mirrors. Only the handful of fields the diff
// needs are mirrored; `ignoreUnknownKeys` skips the rest.
// ---------------------------------------------------------------------------

object HistoryDataDiffProduct {
  const val SCHEMA: String = "history-data-diff/v1"
}

object HistoryDataDiff {

  private val DECODE = Json { ignoreUnknownKeys = true }

  /**
   * Diffs the archived data products of [from] against [to]. Both are assumed to belong to the same
   * preview (the caller enforces that). Decoding uses a lenient JSON reader so an older sidecar
   * with extra/renamed fields still diffs on the fields this product cares about.
   */
  fun diff(from: HistoryEntry, to: HistoryEntry, json: Json = DECODE): HistoryDataDelta {
    val semantics =
      bothPresent(from.semantics, to.semantics) { base, head ->
        SemanticsDiff.diff(
          json.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), base),
          json.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), head),
        )
      }
    val a11y =
      bothPresent(from.a11yAtf, to.a11yAtf) { _, _ ->
        A11yDiff.diff(
          baseFindings = decodeFindings(from.a11yAtf, json),
          baseNodes = decodeNodes(from.a11yHierarchy, json),
          headFindings = decodeFindings(to.a11yAtf, json),
          headNodes = decodeNodes(to.a11yHierarchy, json),
        )
      }
    val theme =
      bothPresent(from.theme, to.theme) { base, head ->
        ThemeDiff.diff(
          json.decodeFromJsonElement(ThemePayload.serializer(), base),
          json.decodeFromJsonElement(ThemePayload.serializer(), head),
        )
      }
    return HistoryDataDelta(semantics = semantics, a11y = a11y, theme = theme)
  }

  private inline fun <T> bothPresent(
    base: JsonElement?,
    head: JsonElement?,
    block: (JsonElement, JsonElement) -> T,
  ): T? = if (base != null && head != null) block(base, head) else null

  private fun decodeFindings(element: JsonElement?, json: Json): List<A11yFindingMirror> =
    element?.let { json.decodeFromJsonElement(A11yFindingsPayloadMirror.serializer(), it).findings }
      ?: emptyList()

  private fun decodeNodes(element: JsonElement?, json: Json): List<A11yNodeMirror> =
    element?.let { json.decodeFromJsonElement(A11yHierarchyPayloadMirror.serializer(), it).nodes }
      ?: emptyList()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HistoryDataDelta(
  // `@EncodeDefault` so the versioned schema discriminator rides the wire even under
  // `encodeDefaults = false`, matching the `SemanticsDelta` / `ThemeDelta` contract.
  @EncodeDefault val schema: String = HistoryDataDiffProduct.SCHEMA,
  val semantics: SemanticsDelta? = null,
  val a11y: A11yDelta? = null,
  val theme: ThemeDelta? = null,
) {
  /** True when every compared section is absent or carries no changes. */
  val isEmpty: Boolean
    get() = (semantics?.isEmpty ?: true) && (a11y?.isEmpty ?: true) && (theme?.isEmpty ?: true)
}

// ---------------------------------------------------------------------------
// a11y findings diff (a11y-diff/v1)
// ---------------------------------------------------------------------------

object A11yDiffProduct {
  const val SCHEMA: String = "a11y-diff/v1"
}

/**
 * Diffs two entries' ATF findings (`a11y/atf`). Each finding is keyed by its rule [type] plus the
 * stable hierarchy `ref` of the node it sits on (issue #1784) — resolved by matching the finding's
 * `boundsInScreen` against the `a11y/hierarchy` nodes captured in the same entry. When no hierarchy
 * is captured (or no node matches the bounds), the key falls back to the bounds string so the diff
 * is still deterministic; a copy edit (message text changing on the same ref) then reports as a
 * field change rather than a remove + add.
 */
object A11yDiff {

  // `internal` because the parameter types are internal mirrors; the only caller is
  // `HistoryDataDiff` in this module. The result type (`A11yDelta`) is public.
  internal fun diff(
    baseFindings: List<A11yFindingMirror>,
    baseNodes: List<A11yNodeMirror>,
    headFindings: List<A11yFindingMirror>,
    headNodes: List<A11yNodeMirror>,
  ): A11yDelta {
    val baseByKey = indexByKey(baseFindings, boundsToRef(baseNodes))
    val headByKey = indexByKey(headFindings, boundsToRef(headNodes))

    val removed =
      baseByKey.keys.filter { it !in headByKey }.sorted().map { baseByKey.getValue(it).summary() }
    val added =
      headByKey.keys.filter { it !in baseByKey }.sorted().map { headByKey.getValue(it).summary() }
    val changed =
      baseByKey.keys
        .filter { it in headByKey }
        .sorted()
        .mapNotNull { key -> findingChange(baseByKey.getValue(key), headByKey.getValue(key)) }

    return A11yDelta(added = added, removed = removed, changed = changed)
  }

  /** `boundsInScreen` → first node `ref` carrying it, for attributing findings to stable refs. */
  private fun boundsToRef(nodes: List<A11yNodeMirror>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (node in nodes) {
      val bounds = node.boundsInScreen ?: continue
      val ref = node.ref ?: continue
      out.putIfAbsent(bounds, ref)
    }
    return out
  }

  /** Map each finding to its identity key, keeping the resolved `ref` on a small wrapper. */
  private fun indexByKey(
    findings: List<A11yFindingMirror>,
    boundsToRef: Map<String, String>,
  ): Map<String, Resolved> {
    val out = LinkedHashMap<String, Resolved>()
    for (finding in findings) {
      val ref = finding.boundsInScreen?.let { boundsToRef[it] }
      val anchor = ref ?: finding.boundsInScreen ?: ""
      val key = "${finding.type}|$anchor"
      // First finding for a key wins; duplicate (type, ref) findings collapse, which is fine for a
      // structural "did this class of finding appear/disappear" signal.
      out.putIfAbsent(key, Resolved(finding, ref))
    }
    return out
  }

  private fun findingChange(base: Resolved, head: Resolved): A11yFindingChange? {
    val changes = COMPARED_FIELDS.mapNotNull { (field, extract) ->
      val from = extract(base.finding)
      val to = extract(head.finding)
      if (from != to) A11yFieldChange(field, from, to) else null
    }
    return if (changes.isEmpty()) null
    else A11yFindingChange(ref = head.ref ?: base.ref, type = head.finding.type, changes = changes)
  }

  private val COMPARED_FIELDS: List<Pair<String, (A11yFindingMirror) -> String?>> =
    listOf(
      "level" to { it.level },
      "message" to { it.message },
      "viewDescription" to { it.viewDescription },
    )

  private class Resolved(val finding: A11yFindingMirror, val ref: String?) {
    fun summary(): A11yFindingSummary =
      A11yFindingSummary(
        ref = ref,
        type = finding.type,
        level = finding.level,
        message = finding.message,
        boundsInScreen = finding.boundsInScreen,
      )
  }
}

@Serializable
data class A11yFieldChange(val field: String, val from: String? = null, val to: String? = null)

@Serializable
data class A11yFindingSummary(
  val ref: String? = null,
  val type: String,
  val level: String,
  val message: String,
  val boundsInScreen: String? = null,
)

@Serializable
data class A11yFindingChange(
  val ref: String? = null,
  val type: String,
  val changes: List<A11yFieldChange>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class A11yDelta(
  @EncodeDefault val schema: String = A11yDiffProduct.SCHEMA,
  val added: List<A11yFindingSummary> = emptyList(),
  val removed: List<A11yFindingSummary> = emptyList(),
  val changed: List<A11yFindingChange> = emptyList(),
) {
  val isEmpty: Boolean
    get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

// ---------------------------------------------------------------------------
// a11y mirror types — structurally-identical to `:data-a11y-core`'s
// `AccessibilityFinding` / `AccessibilityNode` payloads, mirrored here because
// that module is an Android library `:daemon:core` (plain JVM) can't depend on.
// Only the diffed fields are kept; `ignoreUnknownKeys` drops the rest.
// ---------------------------------------------------------------------------

@Serializable
internal data class A11yFindingMirror(
  val level: String = "",
  val type: String = "",
  val message: String = "",
  val viewDescription: String? = null,
  val boundsInScreen: String? = null,
)

@Serializable
internal data class A11yFindingsPayloadMirror(val findings: List<A11yFindingMirror> = emptyList())

@Serializable
internal data class A11yNodeMirror(val ref: String? = null, val boundsInScreen: String? = null)

@Serializable
internal data class A11yHierarchyPayloadMirror(val nodes: List<A11yNodeMirror> = emptyList())
