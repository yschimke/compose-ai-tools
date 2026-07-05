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
 */
object PreviewSlots {
  /** The `testTag` prefix a slot marker applies; the slot name is the suffix (`dp-slot:<name>`). */
  const val SLOT_TAG_PREFIX: String = "dp-slot:"

  /**
   * The slot markers in [payload], in depth-first order: each node whose `testTag` starts with
   * [SLOT_TAG_PREFIX], as its name (the suffix) + box (its `boundsInRoot`). Nodes with a blank name
   * or malformed bounds are skipped, so a partial/garbled tree never throws.
   */
  fun extractSlots(payload: ComposeSemanticsPayload): List<PreviewSlot> {
    val out = mutableListOf<PreviewSlot>()
    fun walk(node: ComposeSemanticsNode) {
      val tag = node.testTag
      if (tag != null && tag.startsWith(SLOT_TAG_PREFIX)) {
        val name = tag.removePrefix(SLOT_TAG_PREFIX)
        val bounds = SlotBounds.parse(node.boundsInRoot)
        if (name.isNotBlank() && bounds != null) out.add(PreviewSlot(name, bounds))
      }
      node.children.forEach(::walk)
    }
    walk(payload.root)
    return out
  }
}

/**
 * The wire response of the `/render/<id>.slots` serve route: the [slots] a preview declared, in
 * depth-first order, tagged with the [previewId] they came from. A structured-screen builder reads
 * this to lay out slot regions and size children to fill them.
 */
@Serializable data class PreviewSlotsPayload(val previewId: String, val slots: List<PreviewSlot>)

/** One named slot region — its author-declared name and its box (absolute-to-root px). */
@Serializable
data class PreviewSlot(val name: String, val bounds: SlotBounds) {
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
