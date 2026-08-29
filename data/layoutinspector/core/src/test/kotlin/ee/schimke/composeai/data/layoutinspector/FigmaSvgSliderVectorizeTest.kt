package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Slider` is no longer in [FigmaSvgModel.DEFAULT_RASTER_COMPONENTS]: its track + thumb are drawn
 * imperatively by `SliderKt`, and the draw-capture extractor re-invokes those draw lambdas against
 * a recording `DrawScope` on-device, populating a [LayoutInspectorVectorGraphic] on the drawn
 * leaves. This covers the pure model/emitter half from a synthetic payload — that a `SliderKt` node
 * keeps recursing into those captured leaves (instead of being cropped out as an opaque-by-name
 * `<image>`, which would drop the whole subtree before recursion ever reached them).
 */
class FigmaSvgSliderVectorizeTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** The active-track segment the draw-capture extractor recorded as a rounded `<path>`. */
  private val track =
    LayoutInspectorVectorGraphic(
      viewportWidth = 200f,
      viewportHeight = 4f,
      paths =
        listOf(
          LayoutInspectorVectorPath(
            pathData =
              "M2 0L198 0A2 2 0 0 1 200 2A2 2 0 0 1 198 4L2 4A2 2 0 0 1 0 2A2 2 0 0 1 2 0Z",
            fillArgb = "#FF6750A4",
          )
        ),
    )

  /**
   * A `SliderKt` container whose drawn track leaf carries a captured
   * [LayoutInspectorVectorGraphic], exactly as the connector's draw-capture populates it on-device.
   */
  private fun sliderNode() =
    LayoutInspectorNode(
      nodeId = "slider-1",
      component = "SliderKt", // fragment "Slider" used to force an opaque raster of the whole node
      bounds = bounds(0, 0, 200, 48),
      size = LayoutInspectorSize(200, 48),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "track-1",
            component = "Spacer",
            bounds = bounds(0, 22, 200, 26),
            size = LayoutInspectorSize(200, 4),
            vectorGraphic = track,
          )
        ),
    )

  private fun model(node: LayoutInspectorNode, rasterComponents: Set<String>) =
    FigmaSvgModel.from(layout = LayoutInspectorPayload(node), rasterComponents = rasterComponents)

  @Test
  fun sliderIsNotInTheDefaultRasterSet() {
    assertFalse(
      "Slider is draw-captured now, not rastered by name",
      FigmaSvgModel.DEFAULT_RASTER_COMPONENTS.contains("Slider"),
    )
    assertTrue(
      "TextField stays rastered — its cursor/selection/IME state is genuinely raster-only",
      FigmaSvgModel.DEFAULT_RASTER_COMPONENTS.contains("TextField"),
    )
  }

  @Test
  fun sliderRecursesIntoItsCapturedTrackAsAVector() {
    val m = model(sliderNode(), FigmaSvgModel.DEFAULT_RASTER_COMPONENTS)
    // The SliderKt node itself is a plain container now — no opaque-by-name raster of the subtree.
    assertNull("SliderKt is not an opaque-leaf <image>", m.root.raster)
    assertEquals("its drawn track survives as a child layer", 1, m.root.children.size)
    val trackLayer = m.root.children.single()
    assertNotNull("the track carries the editable captured vector", trackLayer.vector)
    assertNull("the track is a vector, not a raster crop", trackLayer.raster)
    assertTrue(m.rasterTargets.isEmpty())

    val svg = FigmaLayeredSvg.render(m)
    assertFalse("no <image> for a vectorised slider track", svg.contains("<image"))
    assertTrue("emits the captured track <path>", svg.contains("<path d=\"M2 0"))
    assertTrue("solid track fill is carried through", svg.contains("fill=\"#6750A4\""))
  }

  @Test
  fun withSliderBackInTheRasterSetTheSubtreeIsCroppedOut() {
    // Guard the mechanism: had `Slider` stayed a raster-component fragment, the opaque-by-name
    // branch
    // would fire on `SliderKt` *before* recursion, cropping the node to an <image> and dropping the
    // captured track entirely — the very regression removing it from the set avoids.
    val m = model(sliderNode(), FigmaSvgModel.DEFAULT_RASTER_COMPONENTS + "Slider")
    assertNotNull("with Slider rastered, the node is an opaque <image>", m.root.raster)
    assertTrue("the drawn track child is dropped", m.root.children.isEmpty())
    assertTrue(m.rasterTargets.isNotEmpty())
  }
}
