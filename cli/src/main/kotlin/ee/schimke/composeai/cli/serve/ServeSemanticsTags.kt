package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * ## Coordinates, and an open contract question
 *
 * Bounds are `boundsInRoot` — absolute-to-root **render pixels**, the same space
 * [ServeDesignAnnotations] reports and the same space the served PNG is in.
 *
 * The design doc says the *index* publishes bounds already transformed into an acceptance's
 * **canonical plane**, and that the server does that transform once so no consumer repeats it. This
 * projection cannot: the canonical plane is resolved per comparison from the reference raster and
 * the acceptance's recorded plane, and this is a per-preview endpoint with neither in scope. So one
 * of the two has to move — either the transform belongs to the comparison (and the doc's placement
 * is wrong), or the index must be produced by a comparison-scoped projection that has the plane.
 *
 * Until that is settled, every entry states its own space on the wire ([TagEntry.space]). A
 * consumer that reads `render-pixels` cannot silently treat these as canonical, which is the one
 * failure mode a mismatch here produces — a wrong `element-moved` verdict from bounds nobody
 * converted.
 */
object ServeSemanticsTags {

  /**
   * The coordinate space [TagEntry.bounds] is in. See the class KDoc for why this is on the wire.
   */
  const val RENDER_PIXELS = "render-pixels"

  /**
   * One tag's occupancy of the tree. [count] is every node carrying the tag; [bounds] is the first
   * usable box among them, absent when none of them has one (a tag on a zero-area or malformed node
   * is still worth reporting, because `count` is what a uniqueness check reads).
   *
   * [space] names the coordinate space of [bounds] explicitly rather than leaving it to a
   * consumer's reading of the spec, which currently disagrees with this producer. It is always
   * [RENDER_PIXELS] today; the field exists so that a consumer cannot assume otherwise, and so a
   * later canonical-plane producer is distinguishable on the wire instead of by version guessing.
   *
   * `@EncodeDefault` because the host serialises with `encodeDefaults = false`, which would drop a
   * default-valued property from the JSON entirely — a discriminator that never reaches the wire is
   * worse than none, since it reads as present in Kotlin and is absent to the browser. Same
   * treatment, for the same reason, as the schema discriminators on `HistoryDataDelta`.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  data class TagEntry(
    val count: Int,
    val bounds: AnnotationBounds? = null,
    @EncodeDefault val space: String = RENDER_PIXELS,
  )

  /**
   * [payload]'s tag index, in depth-first encounter order.
   *
   * Blank tags are skipped: `testTag = ""` is not an identity anything can resolve, and admitting
   * it would give every untagged-but-present node one shared key.
   */
  fun index(payload: ComposeSemanticsPayload): Map<String, TagEntry> {
    val out = LinkedHashMap<String, TagEntry>()
    fun walk(node: ComposeSemanticsNode) {
      // A trial-measured copy is not a second use of the tag: it was never placed, so it is not on
      // the frame and its bounds read as the origin. Counting it publishes `count = 2` for a tag
      // that identifies exactly one node, which a scoped parity consumer rejects as ambiguous.
      // See `ComposeSemanticsNode.placed`.
      if (!node.placed) return
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
