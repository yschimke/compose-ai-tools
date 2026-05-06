package ee.schimke.composeai.renderer.uiautomator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire-format JSON shape for [Selector]. Lives separately from `Selector` itself so the in-memory
 * matcher type stays free of `@Serializable` annotations (and free of a hard kotlinx-serialization
 * runtime dep on its own field definitions).
 *
 * # JSON shape
 *
 * One flat object per selector, with regex variants as parallel keys (`textMatches` /
 * `descMatches` / `clazzMatches` / `resMatches`). Tree predicates carry nested selector arrays.
 * Every field is optional — a missing field means "this axis isn't filtered". Example:
 *
 * ```json
 * {
 *   "text": "Submit",
 *   "enabled": true
 * }
 * ```
 *
 * ```json
 * {
 *   "desc": "row-2",
 *   "hasDescendant": [{"textMatches": "Item \\d+"}]
 * }
 * ```
 *
 * # Why a separate DTO instead of `@Serializable` on `Selector`
 *
 * `Selector` carries `TextMatch` (sealed class with `Pattern` payload) which doesn't serialize
 * cleanly without polymorphic boilerplate. Splitting the regex variants into parallel string
 * keys (`text` for exact, `textMatches` for regex) flattens the wire shape and matches what
 * agents actually send — same convention `androidx.test.uiautomator.By` uses
 * (`By.text(String)` vs `By.text(Pattern)`).
 *
 * Round-trip is **not** lossless for compiled `Pattern` flags: a `By.text(Pattern.compile("x",
 * CASE_INSENSITIVE))` round-trips to a flag-free pattern. Agents that need flags should use
 * inline syntax (`(?i)x`) — the same advice the upstream UIAutomator docs give.
 */
@Serializable
public data class SelectorJson(
  /** Exact match against the node's `text` axis. Mutually exclusive with [textMatches]. */
  val text: String? = null,
  /** Regex match against the node's `text` axis. Mutually exclusive with [text]. */
  val textMatches: String? = null,
  val desc: String? = null,
  val descMatches: String? = null,
  val clazz: String? = null,
  val clazzMatches: String? = null,
  val res: String? = null,
  val resMatches: String? = null,
  val enabled: Boolean? = null,
  val clickable: Boolean? = null,
  val longClickable: Boolean? = null,
  val checkable: Boolean? = null,
  val checked: Boolean? = null,
  val selected: Boolean? = null,
  val focused: Boolean? = null,
  val scrollable: Boolean? = null,
  /** Direct-child predicates — every entry must match some immediate child. */
  @SerialName("hasChild") val hasChild: List<SelectorJson> = emptyList(),
  /** Descendant predicates — every entry must match somewhere in the subtree. */
  @SerialName("hasDescendant") val hasDescendant: List<SelectorJson> = emptyList(),
)

private val WireFormat: Json = Json {
  encodeDefaults = false
  ignoreUnknownKeys = true
  prettyPrint = false
}

/**
 * Render this [Selector] as a JSON string suitable for the daemon bridge and the MCP
 * `record_preview` script-event surface. The reverse is [decodeSelectorJson]. Round-trip is
 * stable for every chain the [By] factory produces; see [SelectorJson] for the regex-flag
 * caveat.
 */
public fun Selector.encodeJson(): String = WireFormat.encodeToString(SelectorJson.serializer(), toJson())

/** Parse a JSON string produced by [encodeJson] (or hand-written by an agent) into a [Selector]. */
public fun decodeSelectorJson(json: String): Selector =
  WireFormat.decodeFromString(SelectorJson.serializer(), json).toSelector()

/** Programmatic conversion (no JSON intermediate) — useful for unit tests. */
public fun Selector.toJson(): SelectorJson =
  SelectorJson(
    text = (text as? TextMatch.Exact)?.expected,
    textMatches = (text as? TextMatch.Regex)?.regex,
    desc = (desc as? TextMatch.Exact)?.expected,
    descMatches = (desc as? TextMatch.Regex)?.regex,
    clazz = (clazz as? TextMatch.Exact)?.expected,
    clazzMatches = (clazz as? TextMatch.Regex)?.regex,
    res = (res as? TextMatch.Exact)?.expected,
    resMatches = (res as? TextMatch.Regex)?.regex,
    enabled = enabled,
    clickable = clickable,
    longClickable = longClickable,
    checkable = checkable,
    checked = checked,
    selected = selected,
    focused = focused,
    scrollable = scrollable,
    hasChild = children.map { it.toJson() },
    hasDescendant = descendants.map { it.toJson() },
  )

/** Convert the JSON DTO back into a runnable [Selector]. */
public fun SelectorJson.toSelector(): Selector {
  require(text == null || textMatches == null) {
    "Selector JSON has both 'text' and 'textMatches' — pick one"
  }
  require(desc == null || descMatches == null) {
    "Selector JSON has both 'desc' and 'descMatches' — pick one"
  }
  require(clazz == null || clazzMatches == null) {
    "Selector JSON has both 'clazz' and 'clazzMatches' — pick one"
  }
  require(res == null || resMatches == null) {
    "Selector JSON has both 'res' and 'resMatches' — pick one"
  }
  return Selector(
    text = text?.let { TextMatch.Exact(it) } ?: textMatches?.let { TextMatch.Regex(it) },
    desc = desc?.let { TextMatch.Exact(it) } ?: descMatches?.let { TextMatch.Regex(it) },
    clazz = clazz?.let { TextMatch.Exact(it) } ?: clazzMatches?.let { TextMatch.Regex(it) },
    res = res?.let { TextMatch.Exact(it) } ?: resMatches?.let { TextMatch.Regex(it) },
    enabled = enabled,
    clickable = clickable,
    longClickable = longClickable,
    checkable = checkable,
    checked = checked,
    selected = selected,
    focused = focused,
    scrollable = scrollable,
    children = hasChild.map { it.toSelector() },
    descendants = hasDescendant.map { it.toSelector() },
  )
}
