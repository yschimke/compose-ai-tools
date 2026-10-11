package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Project a render's `compose/semantics` tree into a tag index (`testTag → {count, bounds}`), the
 * identity a scoped parity acceptance targets an element by
 * ([docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)).
 * Positional refs ([ee.schimke.composeai.data.layoutinspector.SemanticsRefs]) silently retarget
 * when siblings are inserted; an authored `testTag` survives or stops resolving.
 *
 * [TagEntry.count] makes uniqueness checkable without shipping the tree: every node carrying the
 * tag counts, even ones without usable bounds, so a zero-area duplicate can't hide ambiguity.
 * [TagEntry.bounds] is the first usable box depth-first, or null.
 *
 * Bounds are `boundsInRoot` render pixels (as [ServeDesignAnnotations] and the served PNG). The
 * design doc expects canonical-plane bounds, which a per-preview endpoint can't compute; until that
 * is settled each entry states its space ([TagEntry.space]) so consumers can't mistake it.
 */
public object ServeSemanticsTags {

  /** The coordinate space of [TagEntry.bounds]; see the class docs for why it's on the wire. */
  public const val RENDER_PIXELS: String = "render-pixels"

  /**
   * One tag's occupancy: [count] nodes carry it; [bounds] is the first usable box, absent when none
   * has one. [space] is explicit (always [RENDER_PIXELS] today) so a future canonical-plane
   * producer is distinguishable. `@EncodeDefault` because the host encodes with `encodeDefaults =
   * false`, which would drop it.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  public data class TagEntry(
    val count: Int,
    val bounds: AnnotationBounds? = null,
    @EncodeDefault val space: String = RENDER_PIXELS,
  )

  /**
   * [payload]'s tag index in depth-first order. Blank tags are skipped (not a resolvable identity).
   */
  public fun index(payload: ComposeSemanticsPayload): Map<String, TagEntry> {
    val out = LinkedHashMap<String, TagEntry>()
    fun walk(node: ComposeSemanticsNode) {
      // Unplaced trial-measured copies aren't on the frame; counting them would report false
      // ambiguity. See `ComposeSemanticsNode.placed`.
      if (!node.placed) return
      // Keyed verbatim: Compose and `SemanticsTargets.Tag` match the exact string, and trimming
      // would merge distinct tags.
      val tag = node.testTag?.takeIf { it.isNotBlank() }
      if (tag != null) {
        val box = SlotBounds.parse(node.boundsInRoot)?.takeIf { it.hasArea() }
        val existing = out[tag]
        out[tag] =
          if (existing == null) TagEntry(count = 1, bounds = box?.toAnnotationBounds())
          // First usable box, matching the depth-first order both engines walk.
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
