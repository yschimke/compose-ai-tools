package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — an `Icon`/`Image` backed by a captured [LayoutInspectorVectorGraphic] emits as editable
 * `<path>` layers instead of an opaque `<image>` raster crop, while a bitmap-backed one (no captured
 * graphic) still rasters. The capture side (reflecting the `VectorPainter`) is exercised on-device;
 * this covers the pure model/emitter half from a synthetic payload.
 */
class FigmaSvgVectorIconTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  private fun iconNode(graphic: LayoutInspectorVectorGraphic?) =
    LayoutInspectorNode(
      nodeId = "icon-1",
      component = "Icon", // opaque-by-name: without a graphic this rasters
      bounds = bounds(0, 0, 96, 96),
      size = LayoutInspectorSize(96, 96),
      vectorGraphic = graphic,
    )

  private val star =
    LayoutInspectorVectorGraphic(
      viewportWidth = 24f,
      viewportHeight = 24f,
      paths =
        listOf(
          LayoutInspectorVectorPath(
            pathData = "M12 0L15 9L24 9L17 14L20 24L12 18L4 24L7 14L0 9L9 9Z",
            fillArgb = "#FF112233",
          )
        ),
    )

  private fun model(node: LayoutInspectorNode) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(node),
      rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
    )

  @Test
  fun vectorBackedIconEmitsPathsNotRaster() {
    val m = model(iconNode(star))
    assertNull("a captured vector icon is not an opaque <image> leaf", m.root.raster)
    assertTrue("no raster crops are scheduled for it", m.rasterTargets.isEmpty())
    val vec = m.root.vector
    assertNotNull("the layer carries the editable vector", vec)
    assertTrue(vec!!.paths.single().pathData.startsWith("M12 0"))

    val svg = FigmaLayeredSvg.render(m)
    assertFalse("no <image> for a vectorised icon", svg.contains("<image"))
    assertTrue("emits a <path>", svg.contains("<path d=\"M12 0"))
    assertTrue("solid fill is carried through", svg.contains("fill=\"#112233\""))
    // 96px box over a 24-unit viewport ⇒ 4× scale, translated to the placed origin.
    assertTrue(
      "viewport is scaled onto the placed box",
      svg.contains("transform=\"translate(0 0) scale(4 4)\""),
    )
  }

  @Test
  fun bitmapBackedIconStillRasters() {
    // No captured graphic (a BitmapPainter-backed Icon/Image) ⇒ the opaque-by-name raster fallback.
    val m = model(iconNode(graphic = null))
    assertNotNull("a bitmap icon still rasters", m.root.raster)
    assertNull(m.root.vector)
    assertTrue(m.rasterTargets.isNotEmpty())
  }

  @Test
  fun gradientOnlyGraphicFallsBackToRaster() {
    // A path whose fill/stroke never resolved to a solid colour (a gradient/brush) carries no
    // paintable path, so the icon falls through to the raster fallback rather than emitting nothing.
    val gradientOnly =
      LayoutInspectorVectorGraphic(
        viewportWidth = 24f,
        viewportHeight = 24f,
        paths = listOf(LayoutInspectorVectorPath(pathData = "M0 0L24 24Z", fillArgb = null)),
      )
    val m = model(iconNode(gradientOnly))
    assertNull("no emittable paths ⇒ not a vector layer", m.root.vector)
    assertNotNull("falls back to the raster crop", m.root.raster)
  }
}
