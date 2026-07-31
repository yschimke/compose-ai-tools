package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A padded `Icon` draws its `ImageVector` into the box left *after* the paddings ahead of its
 * painter — not into the node's own box (issue #2853).
 *
 * Jetchat writes both shapes of this. `RecordButton` is `Icon(modifier = Modifier.sizeIn(minWidth =
 * 56.dp, …).padding(18.dp))`: the node is placed at 56dp and the microphone is drawn into the 20dp
 * that survives the padding. `InputSelectorButton` is `IconButton { Icon(modifier =
 * Modifier.padding(8.dp).size(56.dp)) }`: the padding insets *and* the button's constraints clamp
 * the requested size. Fitting the viewport to the node box drew both at their button's size — the
 * oversized mic (`scale(6.54)` for a 24-unit viewport in a 157px button) and the five oversized
 * action icons of `Conversation/Input`.
 *
 * The `Modifier.paint` entry carries the rect the painter actually filled, so the export fits the
 * vector to that instead.
 */
class FigmaSvgPaddedIconTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  private val glyph =
    LayoutInspectorVectorGraphic(
      viewportWidth = 24f,
      viewportHeight = 24f,
      paths =
        listOf(LayoutInspectorVectorPath(pathData = "M0 0 L24 0 L24 24 Z", fillArgb = "#FF112233")),
    )

  /**
   * An icon node placed across the whole 105×105 button, whose painter — after a 21px padding —
   * fills only the inner 63×63. [paintBounds] is what the connector captures off the
   * `Modifier.paint` entry; null models an older capture that carried no modifier bounds.
   */
  private fun iconInButton(paintBounds: LayoutInspectorBounds?) =
    LayoutInspectorNode(
      nodeId = "button-1",
      component = "Box",
      bounds = bounds(0, 0, 105, 105),
      size = LayoutInspectorSize(105, 105),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "icon-1",
            component = "Icon",
            bounds = bounds(0, 0, 105, 105),
            size = LayoutInspectorSize(105, 105),
            vectorGraphic = glyph,
            modifiers =
              listOf(
                LayoutInspectorModifier(name = "padding", bounds = bounds(0, 0, 105, 105)),
                LayoutInspectorModifier(name = "paint", bounds = paintBounds),
              ),
          )
        ),
    )

  private fun iconLayer(paintBounds: LayoutInspectorBounds?): FigmaSvgLayer {
    val root =
      FigmaSvgModel.from(layout = LayoutInspectorPayload(iconInButton(paintBounds)), density = 1f)
        .root
    return firstVector(root) ?: error("vector layer not found")
  }

  private fun firstVector(layer: FigmaSvgLayer): FigmaSvgLayer? =
    if (layer.vector != null) layer else layer.children.firstNotNullOfOrNull { firstVector(it) }

  @Test
  fun theVectorFitsThePaintersOwnRectNotTheButtonBox() {
    val icon = iconLayer(bounds(21, 21, 84, 84))
    assertEquals(21, icon.left)
    assertEquals(21, icon.top)
    assertEquals(63, icon.right - icon.left)
    assertEquals(63, icon.bottom - icon.top)
    // The layout slot handed to the emitter is that same drawn rect, so the fit is 63/24 — not the
    // 105/24 the node box would have given.
    assertEquals(63, icon.vector!!.layoutWidth)
    assertEquals(63, icon.vector!!.layoutHeight)
  }

  @Test
  fun theEmittedGroupScalesTheGlyphToTheDrawnRect() {
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(
          layout = LayoutInspectorPayload(iconInButton(bounds(21, 21, 84, 84))),
          density = 1f,
        )
      )
    // 63px drawn / 24 viewport units = 2.62, uniform; and it lands on the drawn rect's origin.
    assertTrue(
      "expected a 2.62 fit at the padded origin, got:\n$svg",
      svg.contains("translate(21 21) scale(2.62 2.62)"),
    )
  }

  @Test
  fun aCaptureWithoutPaintBoundsKeepsTheNodeBox() {
    // Older `layout-inspector.json` files carry no modifier bounds; the export must degrade to what
    // it did before rather than collapsing the icon.
    val icon = iconLayer(null)
    assertEquals(0, icon.left)
    assertEquals(105, icon.right - icon.left)
  }

  @Test
  fun aPaintRectOutsideTheNodeBoxIsIgnored() {
    // A detached / not-yet-placed coordinate reports a rect the painter cannot have filled.
    // Trusting
    // it would move the icon off its node, so the node box wins.
    val icon = iconLayer(bounds(-40, -40, 300, 300))
    assertEquals(0, icon.left)
    assertEquals(105, icon.right - icon.left)
  }

  @Test
  fun aDegeneratePaintRectIsIgnored() {
    val icon = iconLayer(bounds(0, 0, 0, 0))
    assertEquals(105, icon.right - icon.left)
  }
}
