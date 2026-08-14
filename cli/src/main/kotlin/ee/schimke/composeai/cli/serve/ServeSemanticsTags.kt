package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import kotlinx.serialization.Serializable

/**
 * Project a render's `compose/semantics` tree into a **tag index** — `testTag → {count, bounds}`.
 *
 * This is the identity a scoped parity acceptance targets an element by
 * ([docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)).
 * It exists because the obvious alternative does not work:
 * [ee.schimke.composeai.data.layoutinspector.SemanticsRefs] assigns a `ref` per node, but it
 * *indexes siblings that share an anchor* — so `r/role:Button[0]` names "the first Button under
 * this parent", and inserting a Button ahead of it silently retargets the same string at different
 * pixels. A `testTag` is authored, not positional, so it survives the edit or stops resolving; both
 * are honest outcomes where a silent retarget is not.
 *
 * ## Why `count` is the load-bearing field
 *
 * A tag is only a usable identity while exactly one node carries it. Compose does not enforce that
 * — nothing stops the same `testTag` appearing on every row of a list — so a consumer that resolved
 * "the node with this tag" would silently pick one of several. [TagEntry.count] is what makes that
 * checkable without shipping the whole tree to the client, which production never does.
 *
 * So **every** node carrying the tag counts, including nodes whose bounds are unusable. Counting
 * only the drawable ones would let a zero-area duplicate hide behind a usable sibling and report
 * `count = 1` for a tag that is genuinely ambiguous — the exact failure the field exists to catch.
 * [TagEntry.bounds] is the first *usable* box in depth-first order, and is null when no node
 * carrying the tag has one.
 *
 * ## Coordinates
 *
 * `boundsInRoot` — absolute-to-root **render pixels**, the same space [ServeDesignAnnotations]
 * reports and the same space the served PNG is in. A consumer scales one number and is done. This
 * index must be built from the *same render transaction* as the PNG it describes, which is why it
 * travels inside the annotations response rather than as an endpoint of its own: a second endpoint
 * would be a second render, and the bounds would describe a frame nobody scored.
 */
object ServeSemanticsTags {

  /**
   * One tag's occupancy of the tree. [count] is every node carrying the tag; [bounds] is the first
   * usable box among them, absent when none of them has one (a tag on a zero-area or malformed node
   * is still worth reporting, because `count` is what a uniqueness check reads).
   */
  @Serializable data class TagEntry(val count: Int, val bounds: AnnotationBounds? = null)

  /**
   * [payload]'s tag index, in depth-first encounter order.
   *
   * Blank tags are skipped: `testTag = ""` is not an identity anything can resolve, and admitting
   * it would give every untagged-but-present node one shared key.
   */
  fun index(payload: ComposeSemanticsPayload): Map<String, TagEntry> {
    val out = LinkedHashMap<String, TagEntry>()
    fun walk(node: ComposeSemanticsNode) {
      // Blank-or-absent decides *omission*; the key is then the tag VERBATIM. Trimming it would be
      // a second identity rule, and Compose (and `SemanticsTargets.Tag`) match the exact string —
      // so normalising here collapses `"item"` and `" item "` into one entry reporting `count = 2`
      // (false ambiguity) while an acceptance recording `" item "` finds no key at all (false
      // disappearance). Two wrong verdicts for two tags each unique in the tree.
      val tag = node.testTag?.takeIf { it.isNotBlank() }
      if (tag != null) {
        val box = SlotBounds.parse(node.boundsInRoot)?.takeIf { it.hasArea() }
        val existing = out[tag]
        out[tag] =
          if (existing == null) TagEntry(count = 1, bounds = box?.toAnnotationBounds())
          // Keep the first usable box, not the latest: depth-first order is the one both engines
          // walk, so "first" is reproducible where "last" depends on where the duplicate landed.
          else
            existing.copy(
              count = existing.count + 1,
              bounds = existing.bounds ?: box?.toAnnotationBounds(),
            )
      }
      node.children.forEach(::walk)
    }
    walk(payload.root)
    return out
  }

  private fun SlotBounds.hasArea(): Boolean = right > left && bottom > top

  private fun SlotBounds.toAnnotationBounds(): AnnotationBounds =
    AnnotationBounds(x = left, y = top, width = right - left, height = bottom - top)
}
