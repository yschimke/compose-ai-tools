package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hybrid raster fallback crops pixels out of the rendered frame, so its rect can never reach
 * beyond what a clipping ancestor admits (issue #2853).
 *
 * The crop region is the union of the node's box and its over-drawing modifiers' bounds — the
 * over-draw is what a blend-mode tint paints, and it can legitimately spill past the icon it
 * covers, and past an ordinary (non-clipping) container too. A draw modifier reporting a rect
 * outside a `Modifier.clip` is a different thing: a detached coordinate, or an ancestor transform
 * the capture didn't apply. Trusting it mints an `<image>` of frame pixels belonging to something
 * else — the detached white tiles Jetsnack's `Screens/App shell` and `Snack/Detail` grew to the
 * right of and below their UI — and, because the exported canvas is the union of its layers,
 * stretches the document to cover them.
 */
class FigmaSvgRasterOverflowTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  private val glyph =
    LayoutInspectorVectorGraphic(
      viewportWidth = 24f,
      viewportHeight = 24f,
      paths =
        listOf(LayoutInspectorVectorPath(pathData = "M0 0 L24 0 L24 24 Z", fillArgb = "#FF112233")),
    )

  /**
   * A tinted icon (an `ImageVector` under a `drawWithContent` blend-mode tint — the shape that
   * takes the hybrid raster path) inside a 200×100 screen, with the tint reporting [drawBounds].
   */
  private fun tintedIconOnScreen(drawBounds: LayoutInspectorBounds, clips: Boolean = true) =
    LayoutInspectorNode(
      nodeId = "screen-1",
      component = "Box",
      bounds = bounds(0, 0, 200, 100),
      size = LayoutInspectorSize(200, 100),
      tokens = ComposeSemanticsTokens(clipsContent = clips.takeIf { it }),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "icon-1",
            component = "Icon",
            bounds = bounds(40, 40, 64, 64),
            size = LayoutInspectorSize(24, 24),
            vectorGraphic = glyph,
            modifiers =
              listOf(LayoutInspectorModifier(name = "drawWithContent", bounds = drawBounds)),
          )
        ),
    )

  private fun rasterTarget(
    drawBounds: LayoutInspectorBounds,
    clips: Boolean = true,
  ): FigmaSvgRasterTarget {
    val m =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(tintedIconOnScreen(drawBounds, clips)),
        captureCanvasDraws = true,
      )
    return m.rasterTargets.single()
  }

  @Test
  fun anOverDrawWithinTheClipStillGrowsTheCrop() {
    // The pre-existing behaviour: a tint that spills a little past its icon keeps its own extent,
    // so
    // the crop covers every pixel it painted.
    val t = rasterTarget(bounds(32, 32, 72, 72))
    assertEquals(32, t.left)
    assertEquals(32, t.top)
    assertEquals(72, t.right)
    assertEquals(72, t.bottom)
  }

  @Test
  fun anOverDrawOutsideAClippingAncestorIsClampedToIt() {
    // A draw modifier claiming the whole world: the crop stops at the clip that contains it instead
    // of minting a tile of unrelated frame pixels beyond it.
    val t = rasterTarget(bounds(-500, -500, 900, 900))
    assertEquals(0, t.left)
    assertEquals(0, t.top)
    assertEquals(200, t.right)
    assertEquals(100, t.bottom)
  }

  @Test
  fun anOverDrawUnderNoClipKeepsItsOverflow() {
    // Without a `Modifier.clip` above it, an overflowing draw really is painted past the container
    // edge — the same rule `FigmaSvgChildClipTest` pins for the canvas extent — so the crop keeps
    // every pixel rather than being truncated at a box that never clipped anything.
    val t = rasterTarget(bounds(-40, -30, 260, 160), clips = false)
    assertEquals(-40, t.left)
    assertEquals(-30, t.top)
    assertEquals(260, t.right)
    assertEquals(160, t.bottom)
  }

  @Test
  fun aClampedRasterDoesNotExpandTheExportedCanvas() {
    val m =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(tintedIconOnScreen(bounds(-500, -500, 900, 900))),
        captureCanvasDraws = true,
      )
    // The document extent stays the screen (plus the export's own padding on each side); before the
    // clamp the out-of-bounds crop stretched it to 1400×1400.
    assertTrue(
      "the canvas must stay the screen's size, was ${m.width}×${m.height}",
      m.width <= 200 + m.padding * 2 && m.height <= 100 + m.padding * 2,
    )
  }
}
