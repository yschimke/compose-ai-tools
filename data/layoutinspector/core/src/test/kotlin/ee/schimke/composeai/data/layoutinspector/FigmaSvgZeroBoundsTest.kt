package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node whose layout-inspector `bounds` collapsed to a zero-area box — the Android/Wear inspector
 * mints (0,0,0,0) for a detached / not-yet-placed subcomposed child (an `OutlinedTextField` /
 * `Button` decoration subtree), and `boundsIn` propagates those zeros — must not export as
 * degenerate 0×0 geometry that vanishes and drops the rest of the panel. The export recovers a rect
 * from the node's still-valid measured `size`, anchored inside its placed parent, so the subtree
 * stays visible. See `FigmaSvgModel.recoverBounds`.
 */
class FigmaSvgZeroBoundsTest {

  private fun node(
    component: String,
    bounds: LayoutInspectorBounds,
    size: LayoutInspectorSize,
    tokens: ComposeSemanticsTokens? = null,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = component,
      component = component,
      bounds = bounds,
      size = size,
      tokens = tokens,
      children = children,
    )

  private fun allLayers(layer: FigmaSvgLayer): List<FigmaSvgLayer> = buildList {
    add(layer)
    layer.children.forEach { addAll(allLayers(it)) }
  }

  /**
   * A placed 288×**56** `OutlinedTextField` container whose captured `bounds` are (0,0,0,0) but
   * whose measured `size` survives. The default (opt-in) raster set marks `TextField` opaque, so it
   * becomes a raster target — that target must carry the recovered `size`, never a 0×0 crop.
   */
  @Test
  fun `a zero-bounds TextField rasters at its measured size, not 0x0`() {
    val parent = LayoutInspectorBounds(0, 0, 288, 300)
    val textField =
      node(
        component = "OutlinedTextFieldMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 0, 0),
        size = LayoutInspectorSize(288, 56),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = parent,
        size = LayoutInspectorSize(288, 300),
        children = listOf(textField),
      )

    val model =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(root),
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
      )

    assertEquals(
      "the text field must produce exactly one raster target",
      1,
      model.rasterTargets.size,
    )
    val target = model.rasterTargets.single()
    assertTrue(
      "raster width must be positive (was ${target.right - target.left})",
      target.right - target.left > 0,
    )
    assertTrue(
      "raster height must be positive (was ${target.bottom - target.top})",
      target.bottom - target.top > 0,
    )
    assertEquals("recovered width matches the measured size", 288, target.right - target.left)
    assertEquals("recovered height matches the measured size", 56, target.bottom - target.top)
  }

  /**
   * A zero-bounds `Button` fill node (its `backgroundColor` token resolved, only the geometry is
   * lost) must still paint a positive-area `<rect>` rather than a 0×0 rectangle that disappears.
   */
  @Test
  fun `a zero-bounds filled Button draws a positive-area rect`() {
    val parent = LayoutInspectorBounds(0, 0, 288, 300)
    val button =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 0, 0),
        size = LayoutInspectorSize(288, 40),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF006A60"),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = parent,
        size = LayoutInspectorSize(288, 300),
        children = listOf(button),
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val fillLayer =
      allLayers(model.root).firstOrNull { it.fill != null }
        ?: error("the button fill must survive as a vector layer")
    assertTrue(
      "fill width must be positive (was ${fillLayer.right - fillLayer.left})",
      fillLayer.right - fillLayer.left > 0,
    )
    assertTrue(
      "fill height must be positive (was ${fillLayer.bottom - fillLayer.top})",
      fillLayer.bottom - fillLayer.top > 0,
    )
  }

  /**
   * The collapse mechanism: a zero-bounds *container* whose descendants place against it. Without
   * recovery the child inherits a (0,0,0,0) parent and the whole subtree vanishes; with it, the
   * container is restored from its `size` and the grandchild fill stays on-canvas.
   */
  @Test
  fun `a zero-bounds container keeps its descendants visible`() {
    val leaf =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 0, 0),
        size = LayoutInspectorSize(200, 48),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
      )
    val container =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 0, 0),
        size = LayoutInspectorSize(288, 64),
        children = listOf(leaf),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 288, 300),
        size = LayoutInspectorSize(288, 300),
        children = listOf(container),
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val fillLayer =
      allLayers(model.root).firstOrNull { it.fill != null }
        ?: error("the descendant fill must survive as a vector layer")
    assertTrue(
      "descendant fill must have positive area (was " +
        "${fillLayer.right - fillLayer.left}x${fillLayer.bottom - fillLayer.top})",
      fillLayer.right - fillLayer.left > 0 && fillLayer.bottom - fillLayer.top > 0,
    )
  }

  /**
   * A *placed* node that genuinely measures to zero area — an intentionally collapsed
   * `Modifier.size(0.dp).background(...)` — reports its real non-zero origin, not `(0,0,0,0)`, so
   * recovery must leave it untouched instead of ballooning it to a parent-sized rect (a rect that
   * was not in the captured preview). Guards the narrowing requested in review.
   */
  @Test
  fun `a placed zero-area node is not ballooned to the parent`() {
    val collapsed =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(120, 80, 120, 80),
        size = LayoutInspectorSize(0, 0),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF006A60"),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 320, 300),
        size = LayoutInspectorSize(320, 300),
        children = listOf(collapsed),
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val fill = allLayers(model.root).firstOrNull { it.fill != null }
    if (fill != null) {
      assertEquals("left preserved", 120, fill.left)
      assertEquals("top preserved", 80, fill.top)
      assertEquals("must stay zero-width", 120, fill.right)
      assertEquals("must stay zero-height", 80, fill.bottom)
    }
    assertTrue("no raster targets expected", model.rasterTargets.isEmpty())
  }

  /**
   * A truly 0×0 node sitting at the origin does hit the `(0,0,0,0)` signature, but with no measured
   * size there is nothing to reconstruct — it must stay zero-area, never a parent-sized rect.
   */
  @Test
  fun `a genuinely 0x0 node at the origin stays zero-area`() {
    val collapsed =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 0, 0),
        size = LayoutInspectorSize(0, 0),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF006A60"),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 320, 300),
        size = LayoutInspectorSize(320, 300),
        children = listOf(collapsed),
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val fill = allLayers(model.root).firstOrNull { it.fill != null }
    if (fill != null) {
      assertTrue(
        "a genuinely 0x0 node must not gain area (was " +
          "${fill.right - fill.left}x${fill.bottom - fill.top})",
        fill.right - fill.left == 0 && fill.bottom - fill.top == 0,
      )
    }
  }

  /**
   * A normally-placed node keeps its real bounds — recovery must be a no-op off the failure path.
   */
  @Test
  fun `a placed node keeps its real bounds`() {
    val button =
      node(
        component = "BoxMeasurePolicy",
        bounds = LayoutInspectorBounds(16, 200, 304, 240),
        size = LayoutInspectorSize(288, 40),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF006A60"),
      )
    val root =
      node(
        component = "ColumnMeasurePolicy",
        bounds = LayoutInspectorBounds(0, 0, 320, 300),
        size = LayoutInspectorSize(320, 300),
        children = listOf(button),
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val fillLayer =
      allLayers(model.root).firstOrNull { it.fill != null }
        ?: error("the button fill must survive as a vector layer")
    assertEquals("left preserved", 16, fillLayer.left)
    assertEquals("top preserved", 200, fillLayer.top)
    assertEquals("right preserved", 304, fillLayer.right)
    assertEquals("bottom preserved", 240, fillLayer.bottom)
  }
}
