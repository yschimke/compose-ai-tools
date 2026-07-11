package ee.schimke.composeai.data.layoutinspector

import kotlinx.serialization.Serializable

/**
 * Preview **slots** — named, bounded regions an author marks in a preview so a structured-screen
 * builder can fill them with child components.
 *
 * A slot is declared by tagging the region's node with `testTag = "<SLOT_TAG_PREFIX><name>"` (e.g.
 * `dp-slot:leadingIcon`), typically via a `PreviewSlot(name) { … }` marker composable. `testTag` is
 * a semantics property, so the marked node is captured into the semantics tree with its bounds —
 * slots are therefore **explicit and named**, not guessed from a layout heuristic. [extractSlots]
 * distils a captured [ComposeSemanticsPayload] down to just those markers; the box each returns
 * (absolute-to-root px) is the size a child rendered to fill the slot should be given.
 *
 * **Scope + scrolling.** The marker also records *what kind of container the slot sits in* — its
 * [SlotScope] (`Row`/`Column`/`Box`/`Lazy`) and whether that container [PreviewSlot.scrolling] — so
 * a builder knows how a filled child will be laid out (a `Row` child is placed horizontally, a
 * `Column` child stacks vertically) and whether the region can hold overflowing content. The marker
 * encodes these onto the same `testTag` as `;`-separated attributes after the name
 * (`dp-slot:content;scope=column;scroll=1`); a bare `dp-slot:<name>` (no attributes) reads back as
 * [SlotScope.UNKNOWN], not scrolling, so older tags still parse.
 */
object PreviewSlots {
  /** The `testTag` prefix a slot marker applies; the slot name is the suffix (`dp-slot:<name>`). */
  const val SLOT_TAG_PREFIX: String = "dp-slot:"

  /** Separates the slot name from its optional `key=value` attributes in the tag. */
  private const val ATTR_SEP: Char = ';'

  /**
   * The slot markers in [payload], in depth-first order: each node whose `testTag` starts with
   * [SLOT_TAG_PREFIX], as its name + optional [SlotScope]/`scroll` attributes + box (its
   * `boundsInRoot`). Nodes with a blank name or malformed bounds are skipped, so a partial/garbled
   * tree never throws; an unrecognised attribute is ignored (forward-compatible).
   */
  fun extractSlots(payload: ComposeSemanticsPayload): List<PreviewSlot> {
    val out = mutableListOf<PreviewSlot>()
    fun walk(node: ComposeSemanticsNode) {
      val tag = node.testTag
      if (tag != null && tag.startsWith(SLOT_TAG_PREFIX)) {
        val parts = tag.removePrefix(SLOT_TAG_PREFIX).split(ATTR_SEP)
        val name = parts.first()
        var scope = SlotScope.UNKNOWN
        var scrolling = false
        for (attr in parts.drop(1)) {
          val eq = attr.indexOf('=')
          if (eq < 0) continue
          when (attr.substring(0, eq)) {
            "scope" -> scope = SlotScope.fromWire(attr.substring(eq + 1))
            "scroll" -> scrolling = attr.substring(eq + 1).let { it == "1" || it == "true" }
          }
        }
        val bounds = SlotBounds.parse(node.boundsInRoot)
        if (name.isNotBlank() && bounds != null) {
          out.add(PreviewSlot(name, bounds, scope, scrolling))
        }
      }
      node.children.forEach(::walk)
    }
    walk(payload.root)
    return out
  }
}

/**
 * The layout container a [PreviewSlot] sits in — how a child that fills the slot will be arranged.
 * Derived from the Compose scope receiver at the marker's call site (`RowScope` → [ROW], etc.), or
 * declared explicitly for a slot in a receiver-less lambda (a `Scaffold` `topBar`/`fab`). [UNKNOWN]
 * when the marker didn't record one (a bare `dp-slot:<name>` tag).
 */
@Serializable
enum class SlotScope {
  UNKNOWN,
  /** `RowScope` — children are placed horizontally. */
  ROW,
  /** `ColumnScope` — children stack vertically. */
  COLUMN,
  /** `BoxScope` — a single child fills / aligns within the box. */
  BOX,
  /**
   * A lazy list/grid item scope (`LazyItemScope`) — children scroll; orientation is unspecified.
   */
  LAZY;

  companion object {
    /** The [SlotScope] for a tag's `scope=<wire>` value; [UNKNOWN] for null / anything unknown. */
    fun fromWire(wire: String?): SlotScope =
      when (wire) {
        "row" -> ROW
        "column" -> COLUMN
        "box" -> BOX
        "lazy" -> LAZY
        else -> UNKNOWN
      }
  }
}

/**
 * The wire response of the `/render/<id>.slots` serve route: the [slots] a preview declared, in
 * depth-first order, tagged with the [previewId] they came from. A structured-screen builder reads
 * this to lay out slot regions and size children to fill them.
 */
@Serializable data class PreviewSlotsPayload(val previewId: String, val slots: List<PreviewSlot>)

/**
 * One named slot region — its author-declared [name], its [bounds] (absolute-to-root px), the
 * [scope] container it sits in, and whether that container is [scrolling]. [scope]/[scrolling]
 * default to "unspecified" so a bare `dp-slot:<name>` tag (or a client that predates these fields)
 * round-trips unchanged.
 */
@Serializable
data class PreviewSlot(
  val name: String,
  val bounds: SlotBounds,
  val scope: SlotScope = SlotScope.UNKNOWN,
  val scrolling: Boolean = false,
) {
  val width: Int
    get() = bounds.right - bounds.left

  val height: Int
    get() = bounds.bottom - bounds.top
}

/** A slot's box in absolute-to-root px, mirroring the semantics `boundsInRoot` wire form. */
@Serializable
data class SlotBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  companion object {
    /** Parse the `"left,top,right,bottom"` int wire form; null when malformed. */
    fun parse(wire: String): SlotBounds? {
      val parts = wire.split(",")
      if (parts.size != 4) return null
      val ints = parts.map { it.trim().toIntOrNull() ?: return null }
      return SlotBounds(ints[0], ints[1], ints[2], ints[3])
    }
  }
}
