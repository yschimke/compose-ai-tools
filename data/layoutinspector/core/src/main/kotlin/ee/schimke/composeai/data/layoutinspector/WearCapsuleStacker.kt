package ee.schimke.composeai.data.layoutinspector

/**
 * Composes a tall Wear "scroll → SVG" capsule from parts captured **in isolation** on a round face.
 *
 * Growing a single `ScreenScaffold` frame tall doesn't fit Wear: the round-face content padding,
 * `TransformingLazyColumn` virtualisation, and `SurfaceTransformation` fisheye all scale with the
 * frame, so a grown frame balloons. The alternative — used here — is to capture each screen part at
 * its natural size and **stack** them: the pinned `TimeText`, the header, each unscaled list row,
 * and (optionally) the bottom `EdgeButton`. This object is the backend-agnostic tree surgery for
 * that stack; the caller supplies already-captured [Part]s (layout + semantics + measured height)
 * and gets back one combined [LayoutInspectorPayload] + [ComposeSemanticsPayload] whose synthetic
 * frame root is much taller than wide, so `FigmaSvgModel.from(roundClip = true)` auto-selects the
 * vertical **capsule (stadium)** clip.
 *
 * Padding is deliberate so the round clip **frames** the content without shearing it: the header
 * and rows are inset from the top by [TOP_PAD] so the left-aligned header clears the top
 * semicircle, and the bottom inset is shape-aware — a tapering `EdgeButton` crescent nestles tight
 * into the bottom curve ([EDGE_BOTTOM_PAD]), while a rectangular last item (card / button row) gets
 * the full [TOP_PAD] so the bottom semicircle doesn't clip its corners. `TimeText` is pinned at the
 * very top (dy = 0) so its captured curve — taken from a square round face, centred at (w/2, w/2)
 * with radius ~w/2 — rides the capsule's top rim.
 */
object WearCapsuleStacker {
  /**
   * One screen part captured in isolation: its content layers/semantics (frame stripped) + height.
   */
  data class Part(
    val layout: List<LayoutInspectorNode>,
    val semantics: List<ComposeSemanticsNode>,
    val height: Int,
  )

  /**
   * The bottom `EdgeButton` control, whose crescent is a `Canvas`-drawn container the vector export
   * can't read. It's placed in the stack as one opaque `Image` node ([nodeId]) at [dest] (stacked,
   * root-pixel space); the caller crops the control's isolated pixels from its captured frame at
   * [source] (part-local) and pastes them into a composited frame at [dest] so the hybrid export
   * emits an `<image>` reading those pixels.
   */
  data class EdgeRaster(
    val nodeId: String,
    val source: LayoutInspectorBounds,
    val dest: LayoutInspectorBounds,
  )

  /**
   * The stacked capsule: one combined layout + semantics tree, its size, and any edge raster spec.
   */
  data class Stacked(
    val layout: LayoutInspectorPayload,
    val semantics: ComposeSemanticsPayload,
    val width: Int,
    val height: Int,
    val edge: EdgeRaster?,
  )

  /**
   * Node id + component the bottom `EdgeButton` raster is emitted under (an opaque `Image` layer).
   */
  const val EDGE_NODE_ID: String = "edge-raster"
  private const val EDGE_COMPONENT = "Image"

  /** Top inset (px) so the left-aligned header clears the capsule's rounded top corner. */
  const val TOP_PAD: Int = 44

  /**
   * Tight bottom inset (px) for a tapering `EdgeButton` crescent that nestles into the bottom
   * curve.
   */
  const val EDGE_BOTTOM_PAD: Int = 8

  /** Inter-part gap (px). */
  const val GAP: Int = 6

  /**
   * The natural content height of a captured part — the deepest bottom edge across its [layers], in
   * root px. (A part is captured in a tall throwaway frame, so its own root bounds are meaningless;
   * this measures the content that was actually drawn.)
   */
  fun contentHeight(layers: List<LayoutInspectorNode>): Int =
    layers.maxOfOrNull { it.maxBottom() } ?: 0

  /**
   * Stacks [parts] (plus optional [timeText] and [edge]) into one capsule under a synthetic frame
   * root named `<rootId>-root`, [width] px wide. Returns the combined trees, the derived total
   * height, and — when [edge] is present — the [EdgeRaster] spec the caller composites pixels for.
   */
  fun stack(
    rootId: String,
    width: Int,
    parts: List<Part>,
    timeText: Part? = null,
    edge: Part? = null,
    topPad: Int = TOP_PAD,
    gap: Int = GAP,
  ): Stacked {
    val layoutChildren = mutableListOf<LayoutInspectorNode>()
    val semChildren = mutableListOf<ComposeSemanticsNode>()

    // TimeText rides the top rim: pinned at dy = 0 so its captured curve (centred at w/2, w/2)
    // lands
    // on the capsule's top arc. Its curved textPath carries no straight bounds to clip the header.
    timeText?.let { tt ->
      tt.layout.forEach { layoutChildren.add(it.offsetY(0)) }
      tt.semantics.forEach { semChildren.add(it.offsetY(0)) }
    }

    var y = topPad
    for (part in parts) {
      part.layout.forEach { layoutChildren.add(it.offsetY(y)) }
      part.semantics.forEach { semChildren.add(it.offsetY(y)) }
      y += part.height + gap
    }

    var edgeRaster: EdgeRaster? = null
    if (edge != null) {
      val edgeY = y
      val box = edge.layout.boundingBox()
      val dest =
        LayoutInspectorBounds(
          left = box.left,
          top = box.top + edgeY,
          right = box.right,
          bottom = box.bottom + edgeY,
        )
      layoutChildren.add(
        LayoutInspectorNode(
          nodeId = EDGE_NODE_ID,
          component = EDGE_COMPONENT,
          bounds = dest,
          size = LayoutInspectorSize(box.right - box.left, box.bottom - box.top),
        )
      )
      edgeRaster = EdgeRaster(EDGE_NODE_ID, source = box, dest = dest)
      y += edge.height + gap
    }

    // Shape-aware bottom pad: hug a tapering EdgeButton crescent tight; give a rectangular last
    // item
    // the full inset so the bottom semicircle clears its corners instead of shearing them.
    val bottomPad = if (edge != null) EDGE_BOTTOM_PAD else topPad
    val totalHeight = y - gap + bottomPad

    val layout =
      LayoutInspectorPayload(
        LayoutInspectorNode(
          nodeId = "$rootId-root",
          component = "WearScrollExtract",
          bounds = LayoutInspectorBounds(0, 0, width, totalHeight),
          size = LayoutInspectorSize(width, totalHeight),
          children = layoutChildren,
        )
      )
    val semantics =
      ComposeSemanticsPayload(
        ComposeSemanticsNode(
          nodeId = "$rootId-root",
          boundsInRoot = "0,0,$width,$totalHeight",
          children = semChildren,
        )
      )
    return Stacked(layout, semantics, width, totalHeight, edgeRaster)
  }

  /** Union of every node's bounds in this subtree list, in captured (part-local) px space. */
  private fun List<LayoutInspectorNode>.boundingBox(): LayoutInspectorBounds {
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var b = Int.MIN_VALUE
    fun visit(n: LayoutInspectorNode) {
      l = minOf(l, n.bounds.left)
      t = minOf(t, n.bounds.top)
      r = maxOf(r, n.bounds.right)
      b = maxOf(b, n.bounds.bottom)
      n.children.forEach(::visit)
    }
    forEach(::visit)
    return LayoutInspectorBounds(l, t, r, b)
  }

  /** Deepest bottom edge in this subtree, in root px. */
  private fun LayoutInspectorNode.maxBottom(): Int =
    maxOf(bounds.bottom, children.maxOfOrNull { it.maxBottom() } ?: bounds.bottom)

  /** Shift a whole captured layout subtree down by [dy] px (bounds, modifiers, curved text). */
  private fun LayoutInspectorNode.offsetY(dy: Int): LayoutInspectorNode =
    copy(
      bounds = bounds.copy(top = bounds.top + dy, bottom = bounds.bottom + dy),
      modifiers =
        modifiers.map { m ->
          val b = m.bounds ?: return@map m
          m.copy(bounds = b.copy(top = b.top + dy, bottom = b.bottom + dy))
        },
      curvedTexts = curvedTexts.map { it.copy(centerYPx = it.centerYPx + dy) },
      children = children.map { it.offsetY(dy) },
    )

  /** Shift a captured semantics subtree down by [dy] px, re-serialising its `l,t,r,b` bounds. */
  private fun ComposeSemanticsNode.offsetY(dy: Int): ComposeSemanticsNode {
    val parts = boundsInRoot.split(",")
    val shifted =
      if (parts.size == 4) {
        val l = parts[0].trim()
        val t = parts[1].trim().toIntOrNull()
        val r = parts[2].trim()
        val b = parts[3].trim().toIntOrNull()
        if (t != null && b != null) "$l,${t + dy},$r,${b + dy}" else boundsInRoot
      } else boundsInRoot
    return copy(boundsInRoot = shifted, children = children.map { it.offsetY(dy) })
  }
}
