package ee.schimke.composeai.data.layoutinspector

/**
 * Stitches a Wear scrolling screen into one tall capsule SVG **from the real preview**, the
 * tree-level analogue of the raster `ScrollSliceStitcher`.
 *
 * The caller drives the real preview's scrollable one viewport-step at a time (reduce-motion on, so
 * items are unscaled) and captures the layout + semantics trees at each position — a [Slice] each.
 * This object assembles them:
 * - **Chaining.** The reported scroll offset drifts where a late-revealing `EdgeButton` shifts the
 *   list, so slices are aligned by how far *shared items* actually moved between them (a text
 *   string that occurs once in both slices), not by the scroller's number — the same reason the
 *   raster stitcher pixel-matches instead of trusting the offset.
 * - **Scrollable vs pinned.** The scrollable's item subtrees (the list rows + header) are placed at
 *   their true content position (`viewport-top + chained-offset`), de-duplicated across the slice
 *   overlaps. The pinned `TimeText` (a curved arc, from the first slice) rides the top rim; the
 *   pinned `EdgeButton` is a `Canvas`-drawn crescent the vector export can't read, so it's emitted
 *   as one opaque `Image` layer ([EdgeRaster]) the caller composites the settled crescent's pixels
 *   into.
 * - **Result.** One combined [LayoutInspectorPayload] + [ComposeSemanticsPayload] under a synthetic
 *   frame root much taller than wide, so `FigmaSvgModel.from(roundClip = true)` masks it to the
 *   vertical capsule. Every card, its text, the clock arc and the device face stay editable vector.
 */
object WearScrollSliceStitcher {
  /**
   * One captured scroll slice: the preview's layout + semantics trees at native (viewport) size.
   */
  data class Slice(val layout: LayoutInspectorNode, val semantics: ComposeSemanticsNode)

  /**
   * The settled `EdgeButton` crescent, emitted as one opaque `Image` node ([nodeId]) at [dest]. The
   * caller crops the crescent's pixels from the settled final frame's `[0, sourceTop, width,
   * height]` band (black-backed, so it composites onto the black capsule face) and pastes them at
   * [dest].
   */
  data class EdgeRaster(val nodeId: String, val sourceTop: Int, val dest: LayoutInspectorBounds)

  /** The stitched capsule: combined layout + semantics trees, size, and any edge-crescent spec. */
  data class Stitched(
    val layout: LayoutInspectorPayload,
    val semantics: ComposeSemanticsPayload,
    val width: Int,
    val height: Int,
    val edge: EdgeRaster?,
  )

  const val EDGE_NODE_ID: String = "edge-raster"
  private const val EDGE_COMPONENT = "Image"

  /** Inter-part gap (px) between the last list item and the EdgeButton. */
  const val GAP: Int = 6

  /** Bottom inset (px) below the EdgeButton, hugging the capsule's bottom curve. */
  const val BOTTOM_PAD: Int = 8

  /** ~px bucket that treats an item measured a pixel differently across slices as the same item. */
  private const val DEDUP_BUCKET = 6

  /**
   * Assembles [slices] into a capsule of [width] px, named `<rootId>-root`. When [edgeCropTop] is
   * set (the top of the settled EdgeButton crescent in native-frame coords, the frame being
   * `width`×`width` square), the crescent is placed below the last item as an [EdgeRaster];
   * otherwise the screen has no bottom control.
   */
  fun stitch(
    rootId: String,
    width: Int,
    slices: List<Slice>,
    edgeCropTop: Int? = null,
    gap: Int = GAP,
    bottomPad: Int = BOTTOM_PAD,
  ): Stitched {
    val offsets = chainOffsets(slices)

    // Scrollable items placed at true absolute y, de-duplicated across slice overlaps.
    val itemLayers = mutableListOf<LayoutInspectorNode>()
    val seenTops = mutableSetOf<Int>()
    val semLayers = mutableListOf<ComposeSemanticsNode>()
    val seenText = mutableSetOf<String>()
    for (i in slices.indices) {
      val container = findItemContainer(slices[i].layout)
      if (container != null) {
        for (child in container.children) {
          if (child.bounds.bottom <= child.bounds.top) continue
          val absTop = child.bounds.top + offsets[i]
          if (seenTops.add(absTop / DEDUP_BUCKET)) itemLayers.add(child.shiftY(offsets[i]))
        }
      }
      collectSemText(slices[i].semantics, offsets[i], seenText, semLayers)
    }
    // Pinned TimeText — the curved clock from the first slice, already on the top rim.
    val timeText =
      slices.firstOrNull()?.let { findCurved(it.layout) }?.let { listOf(it.shiftY(0)) }
        ?: emptyList()

    val lastItemBottom = itemLayers.maxOfOrNull { it.bounds.bottom } ?: 0
    var edge: EdgeRaster? = null
    var contentBottom = lastItemBottom
    if (edgeCropTop != null) {
      val edgeH = width - edgeCropTop
      val edgeY = lastItemBottom + gap
      val dest = LayoutInspectorBounds(0, edgeY, width, edgeY + edgeH)
      edge = EdgeRaster(EDGE_NODE_ID, edgeCropTop, dest)
      contentBottom = edgeY + edgeH
    }
    val totalHeight = contentBottom + bottomPad

    val children =
      timeText +
        itemLayers +
        (edge?.let {
          listOf(
            LayoutInspectorNode(
              nodeId = EDGE_NODE_ID,
              component = EDGE_COMPONENT,
              bounds = it.dest,
              size = LayoutInspectorSize(width, it.dest.bottom - it.dest.top),
            )
          )
        } ?: emptyList())

    val layout =
      LayoutInspectorPayload(
        LayoutInspectorNode(
          nodeId = "$rootId-root",
          component = "WearScrollExtract",
          bounds = LayoutInspectorBounds(0, 0, width, totalHeight),
          size = LayoutInspectorSize(width, totalHeight),
          children = children,
        )
      )
    val semantics =
      ComposeSemanticsPayload(
        ComposeSemanticsNode(
          nodeId = "$rootId-root",
          boundsInRoot = "0,0,$width,$totalHeight",
          children = semLayers,
        )
      )
    return Stitched(layout, semantics, width, totalHeight, edge)
  }

  /**
   * Cumulative content offset per slice. Slice 0 is the anchor (0); each next slice is offset by
   * the median distance its shared **once-occurring** text moved (previous-top − next-top), so the
   * align follows the pixels, not the drifting scroll number.
   */
  private fun chainOffsets(slices: List<Slice>): IntArray {
    val tops = slices.map { uniqueTextTops(it.semantics) }
    val offsets = IntArray(slices.size)
    for (i in 1 until slices.size) {
      val shared = tops[i - 1].keys.intersect(tops[i].keys)
      val deltas = shared.map { tops[i - 1].getValue(it) - tops[i].getValue(it) }.sorted()
      offsets[i] = offsets[i - 1] + if (deltas.isEmpty()) 0 else deltas[deltas.size / 2]
    }
    return offsets
  }

  /**
   * Text strings that occur exactly once in this slice → their top edge, for unambiguous chaining.
   */
  private fun uniqueTextTops(root: ComposeSemanticsNode): Map<String, Int> {
    val rows = mutableListOf<Pair<String, Int>>()
    fun walk(n: ComposeSemanticsNode) {
      val t = n.text?.takeIf { it.isNotBlank() } ?: n.layoutText?.takeIf { it.isNotBlank() }
      val b = n.boundsInRoot.split(",").mapNotNull { it.trim().toIntOrNull() }
      if (t != null && b.size == 4 && b[3] > b[1]) rows.add(t to b[1])
      n.children.forEach(::walk)
    }
    walk(root)
    val counts = rows.groupingBy { it.first }.eachCount()
    return rows.filter { counts[it.first] == 1 }.associate { it.first to it.second }
  }

  /**
   * The scrollable's item container — the subcomposition node holding the most **filled** children
   * (list rows carry a background token: a card/button surface). Keying on filled children rather
   * than any placed child avoids selecting a scaffold-level subcomposition whose children are
   * chrome wrappers, which would pull pinned/stray nodes into the item stream.
   */
  private fun findItemContainer(root: LayoutInspectorNode): LayoutInspectorNode? {
    var best: LayoutInspectorNode? = null
    var bestCount = 0
    fun visit(n: LayoutInspectorNode) {
      if (n.component == "LayoutNodeSubcompositionsState") {
        val filled =
          n.children.count {
            it.tokens?.backgroundColor != null && it.bounds.bottom > it.bounds.top
          }
        if (filled > bestCount) {
          best = n
          bestCount = filled
        }
      }
      n.children.forEach(::visit)
    }
    visit(root)
    return best
  }

  private fun findCurved(root: LayoutInspectorNode): LayoutInspectorNode? {
    if (root.curvedTexts.isNotEmpty()) return root
    root.children.forEach { c ->
      findCurved(c)?.let {
        return it
      }
    }
    return null
  }

  private fun collectSemText(
    node: ComposeSemanticsNode,
    dy: Int,
    seen: MutableSet<String>,
    out: MutableList<ComposeSemanticsNode>,
  ) {
    val t = node.text?.takeIf { it.isNotBlank() } ?: node.layoutText?.takeIf { it.isNotBlank() }
    val b = node.boundsInRoot.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (t != null && b.size == 4 && b[3] > b[1]) {
      val key = "$t@${(b[1] + dy) / DEDUP_BUCKET}"
      if (seen.add(key)) out.add(node.copy(children = emptyList()).shiftY(dy))
    }
    node.children.forEach { collectSemText(it, dy, seen, out) }
  }

  /** Shift a captured layout subtree down by [dy] px (bounds, modifiers, curved text, children). */
  private fun LayoutInspectorNode.shiftY(dy: Int): LayoutInspectorNode =
    copy(
      bounds = bounds.copy(top = bounds.top + dy, bottom = bounds.bottom + dy),
      modifiers =
        modifiers.map { m ->
          val mb = m.bounds ?: return@map m
          m.copy(bounds = mb.copy(top = mb.top + dy, bottom = mb.bottom + dy))
        },
      curvedTexts = curvedTexts.map { it.copy(centerYPx = it.centerYPx + dy) },
      children = children.map { it.shiftY(dy) },
    )

  /**
   * Shift a captured semantics node's `l,t,r,b` bounds down by [dy] px (children stripped
   * upstream).
   */
  private fun ComposeSemanticsNode.shiftY(dy: Int): ComposeSemanticsNode {
    val p = boundsInRoot.split(",")
    if (p.size != 4) return this
    val t = p[1].trim().toIntOrNull() ?: return this
    val b = p[3].trim().toIntOrNull() ?: return this
    return copy(
      boundsInRoot = "${p[0].trim()},${t + dy},${p[2].trim()},${b + dy}",
      children = children.map { it.shiftY(dy) },
    )
  }
}
