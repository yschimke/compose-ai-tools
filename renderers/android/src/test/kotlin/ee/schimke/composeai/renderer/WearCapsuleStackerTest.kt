package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.FigmaSvgModel
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import ee.schimke.composeai.data.layoutinspector.WearCapsuleStacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure (no-render) coverage of the production [WearCapsuleStacker] tree surgery. Lives in the
 * `renderer-android` test set purely because `data-layoutinspector-core`'s own test source set does
 * not compile under this toolchain (a pre-existing `PreviewSlotsTest` method name); this module
 * already depends on core and its tests compile.
 */
class WearCapsuleStackerTest {
  private val width = 227

  /** A part with one leaf node + matching semantics of [h] px height, [w] px wide from [left]. */
  private fun part(id: String, h: Int, w: Int = width, left: Int = 0): WearCapsuleStacker.Part {
    val bounds = LayoutInspectorBounds(left, 0, left + w, h)
    return WearCapsuleStacker.Part(
      layout =
        listOf(
          LayoutInspectorNode(
            nodeId = id,
            component = "Box",
            bounds = bounds,
            size = LayoutInspectorSize(w, h),
          )
        ),
      semantics = listOf(ComposeSemanticsNode(nodeId = id, boundsInRoot = "$left,0,${left + w},$h")),
      height = h,
    )
  }

  private fun LayoutInspectorNode.find(id: String): LayoutInspectorNode? =
    if (nodeId == id) this else children.firstNotNullOfOrNull { it.find(id) }

  @Test
  fun `parts stack at cumulative offsets below the top pad`() {
    val stacked =
      WearCapsuleStacker.stack(
        rootId = "t",
        width = width,
        parts = listOf(part("a", 50), part("b", 50)),
      )
    val root = stacked.layout.root
    // First part sits at TOP_PAD; the second below it by its height + gap.
    assertEquals(WearCapsuleStacker.TOP_PAD, root.find("a")!!.bounds.top)
    assertEquals(
      WearCapsuleStacker.TOP_PAD + 50 + WearCapsuleStacker.GAP,
      root.find("b")!!.bounds.top,
    )
    // Height = topPad + 50 + gap + 50 + bottomPad(no edge ⇒ topPad).
    val expected =
      WearCapsuleStacker.TOP_PAD + 50 + WearCapsuleStacker.GAP + 50 + WearCapsuleStacker.TOP_PAD
    assertEquals(expected, stacked.height)
    assertEquals(width, stacked.width)
    // Semantics tree shifts in lockstep with the layout tree.
    val top = WearCapsuleStacker.TOP_PAD + 50 + WearCapsuleStacker.GAP
    val semB = stacked.semantics.root.children.first { it.nodeId == "b" }
    assertEquals("0,$top,$width,${top + 50}", semB.boundsInRoot)
  }

  @Test
  fun `TimeText is pinned to the very top`() {
    val stacked =
      WearCapsuleStacker.stack(
        rootId = "t",
        width = width,
        parts = listOf(part("row", 40)),
        timeText = part("time", 30),
      )
    // TimeText rides the rim at dy = 0; the row still starts at TOP_PAD (TimeText tucks above it).
    assertEquals(0, stacked.layout.root.find("time")!!.bounds.top)
    assertEquals(WearCapsuleStacker.TOP_PAD, stacked.layout.root.find("row")!!.bounds.top)
  }

  @Test
  fun `edge control becomes an opaque Image raster with source and dest bounds`() {
    val edge = part("btn", h = 60, w = 207, left = 10)
    val stacked =
      WearCapsuleStacker.stack(
        rootId = "t",
        width = width,
        parts = listOf(part("a", 50)),
        edge = edge,
      )
    assertNotNull(stacked.edge)
    val er = stacked.edge!!
    val edgeY = WearCapsuleStacker.TOP_PAD + 50 + WearCapsuleStacker.GAP
    assertEquals(WearCapsuleStacker.EDGE_NODE_ID, er.nodeId)
    assertEquals(LayoutInspectorBounds(10, 0, 217, 60), er.source)
    assertEquals(LayoutInspectorBounds(10, edgeY, 217, edgeY + 60), er.dest)
    // The stack carries a matching opaque Image node the hybrid export rasterises.
    val node = stacked.layout.root.find(WearCapsuleStacker.EDGE_NODE_ID)!!
    assertEquals("Image", node.component)
    assertEquals(er.dest, node.bounds)
    // With an edge the bottom is hugged tight, not padded to TOP_PAD.
    assertEquals(edgeY + 60 + WearCapsuleStacker.EDGE_BOTTOM_PAD, stacked.height)
  }

  @Test
  fun `the tall stacked frame drives the capsule clip in the SVG model`() {
    val stacked =
      WearCapsuleStacker.stack(rootId = "t", width = width, parts = List(8) { part("r$it", 60) })
    val model = FigmaSvgModel.from(layout = stacked.layout, roundClip = true)
    // Much taller than wide ⇒ the vertical stadium (capsule), not the inscribed circle.
    assertNotNull(model.capsuleClip)
    assertNull(model.roundClip)
  }
}
