package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.FigmaSvgModel
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorCurvedText
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import ee.schimke.composeai.data.layoutinspector.WearScrollSliceStitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure (no-render) coverage of the production [WearScrollSliceStitcher] — the chaining, de-dup, and
 * placement math. Lives in `renderer-android`'s test set because `data-layoutinspector-core`'s own
 * test source set doesn't compile under this toolchain (a pre-existing `PreviewSlotsTest` name).
 */
class WearScrollSliceStitcherTest {
  private val width = 100

  private fun card(id: String, top: Int, h: Int = 40): LayoutInspectorNode =
    LayoutInspectorNode(
      nodeId = id,
      component = "ColumnMeasurePolicy",
      bounds = LayoutInspectorBounds(0, top, width, top + h),
      size = LayoutInspectorSize(width, h),
      tokens = ComposeSemanticsTokens(backgroundColor = "#FF332E3C"),
    )

  private fun sem(vararg rows: Pair<String, Int>): ComposeSemanticsNode =
    ComposeSemanticsNode(
      nodeId = "root",
      boundsInRoot = "0,0,$width,$width",
      children =
        rows.map { (t, top) ->
          ComposeSemanticsNode(nodeId = t, boundsInRoot = "0,$top,$width,${top + 18}", text = t)
        },
    )

  /** A slice: a subcomposition container of cards, optional curved TimeText, and matching semantics. */
  private fun slice(
    cards: List<LayoutInspectorNode>,
    timeText: Boolean = false,
    rows: List<Pair<String, Int>>,
  ): WearScrollSliceStitcher.Slice {
    val container =
      LayoutInspectorNode(
        nodeId = "container",
        component = "LayoutNodeSubcompositionsState",
        bounds = LayoutInspectorBounds(0, 0, width, width),
        size = LayoutInspectorSize(width, width),
        children = cards,
      )
    val rootChildren =
      if (timeText)
        listOf(
          container,
          LayoutInspectorNode(
            nodeId = "clock",
            component = "CurvedLayoutKt",
            bounds = LayoutInspectorBounds(0, 0, width, width),
            size = LayoutInspectorSize(width, width),
            curvedTexts =
              listOf(
                LayoutInspectorCurvedText(
                  text = "10:10",
                  centerXPx = width / 2.0,
                  centerYPx = width / 2.0,
                  radiusPx = width / 2.0,
                  startAngleRadians = 0.0,
                  sweepRadians = 1.0,
                  clockwise = true,
                  fontSizePx = 12.0,
                )
              ),
          ),
        )
      else listOf(container)
    val root =
      LayoutInspectorNode(
        nodeId = "frameRoot",
        component = "RootMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, width, width),
        size = LayoutInspectorSize(width, width),
        children = rootChildren,
      )
    return WearScrollSliceStitcher.Slice(root, sem(*rows.toTypedArray()))
  }

  private fun LayoutInspectorNode.find(id: String): LayoutInspectorNode? =
    if (nodeId == id) this else children.firstNotNullOfOrNull { it.find(id) }

  @Test
  fun `chains overlapping slices and de-dups items to true positions`() {
    // Slice 0 shows A@50,B@100; slice 1 (scrolled 50) shows B@50,C@100. Shared B fixes the offset.
    val s0 = slice(listOf(card("A", 50), card("B", 100)), timeText = true, rows = listOf("A" to 50, "B" to 100))
    val s1 = slice(listOf(card("B", 50), card("C", 100)), rows = listOf("B" to 50, "C" to 100))

    val out = WearScrollSliceStitcher.stitch(rootId = "t", width = width, slices = listOf(s0, s1))
    val root = out.layout.root

    // A@50, B@100 (once, not twice), C@150 — chained by B's 100→50 move (offset 50).
    assertEquals(50, root.find("A")!!.bounds.top)
    assertEquals(100, root.find("B")!!.bounds.top)
    assertEquals(150, root.find("C")!!.bounds.top)
    // De-dup: exactly one card node per id (A, B, C once each; no overlap ghost).
    val cardIds = root.children.mapNotNull { it.nodeId.takeIf { id -> id in setOf("A", "B", "C") } }
    assertEquals(listOf("A", "B", "C"), cardIds)
    // TimeText pinned at the rim (dy 0).
    assertNotNull(root.find("clock"))
    // No edge requested ⇒ no raster, height = last card bottom (190) + bottom pad.
    assertNull(out.edge)
    assertEquals(190 + WearScrollSliceStitcher.BOTTOM_PAD, out.height)
    assertEquals(width, out.width)
  }

  @Test
  fun `edge crescent is placed below the last item and the tall frame masks to a capsule`() {
    val s0 = slice(listOf(card("A", 20), card("B", 80)), rows = listOf("A" to 20, "B" to 80))
    val tall = List(6) { slice(listOf(card("r$it", 20)), rows = listOf("r$it" to 20)) }
    val out =
      WearScrollSliceStitcher.stitch(
        rootId = "t",
        width = width,
        slices = listOf(s0) + tall,
        edgeCropTop = 60,
      )
    assertNotNull(out.edge)
    val edge = out.edge!!
    assertEquals(WearScrollSliceStitcher.EDGE_NODE_ID, edge.nodeId)
    assertEquals(60, edge.sourceTop)
    // Emitted as an opaque Image node the hybrid export rasters.
    val node = out.layout.root.children.first { it.nodeId == WearScrollSliceStitcher.EDGE_NODE_ID }
    assertEquals("Image", node.component)
    // Tall + narrow ⇒ capsule clip, not the inscribed circle.
    val model = FigmaSvgModel.from(layout = out.layout, roundClip = true)
    assertNotNull(model.capsuleClip)
    assertNull(model.roundClip)
  }
}
