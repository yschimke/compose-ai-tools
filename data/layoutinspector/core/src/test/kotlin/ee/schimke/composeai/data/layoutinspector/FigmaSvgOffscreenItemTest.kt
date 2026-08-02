package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lazy list reports every *composed* item, including the ones scrolled past the viewport — so the
 * captured tree extends well beyond what the render actually painted (issue #2853).
 *
 * Jetsnack's `Search/Categories` renders 1081×577 and its capture carries grid rows down to y 737,
 * which grew the exported canvas to 1082×769 — a quarter of the sticker being space the render
 * never painted, with the below-fold rows' backgrounds stranded in it. (`Screens/App shell` showed
 * the same shape more severely, 400×800 rendered against a 598×1003 canvas, until the
 * `Modifier.clip` child-clipping of #2852 cut its off-screen cards; a list inside a container that
 * does *not* clip still reaches here.)
 *
 * The captured frame's pixel size is the authority on what was rendered, so the canvas is clamped
 * to it. With no frame size (the vector-only path) nothing is known to clamp against and the drawn
 * extent still decides — the behaviour `FigmaSvgChildClipTest` pins for an unclipped overflow.
 */
class FigmaSvgOffscreenItemTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * A 400×800 screen whose lazy row composed one on-screen card and one that sits entirely past the
   * right edge, plus a row below the fold — the shape of the App shell capture.
   */
  private fun screenWithOffscreenItems() =
    LayoutInspectorNode(
      nodeId = "screen",
      component = "Box",
      bounds = bounds(0, 0, 400, 800),
      size = LayoutInspectorSize(400, 800),
      tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF"),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "card-visible",
            component = "Box",
            bounds = bounds(24, 168, 194, 416),
            size = LayoutInspectorSize(170, 248),
            tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF"),
          ),
          LayoutInspectorNode(
            nodeId = "card-offscreen-right",
            component = "Box",
            bounds = bounds(397, 168, 565, 416),
            size = LayoutInspectorSize(168, 248),
            tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF"),
          ),
          LayoutInspectorNode(
            nodeId = "card-below-fold",
            component = "Box",
            bounds = bounds(24, 722, 194, 970),
            size = LayoutInspectorSize(170, 248),
            tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF"),
          ),
        ),
    )

  private fun model(frameWidthPx: Int?, frameHeightPx: Int?) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(screenWithOffscreenItems()),
      density = 1f,
      frameWidthPx = frameWidthPx,
      frameHeightPx = frameHeightPx,
    )

  @Test
  fun theCanvasIsTheRenderedFrameNotTheOffscreenBbox() {
    val m = model(400, 800)
    assertEquals("canvas width must be the 400px render", 400 + m.padding * 2, m.width)
    assertEquals("canvas height must be the 800px render", 800 + m.padding * 2, m.height)
  }

  @Test
  fun withoutAFrameSizeTheDrawnExtentStillDecides() {
    // The vector-only path knows nothing about what was rendered, so it must not start guessing —
    // this is the same rule that keeps an unclipped overflowing child on the canvas.
    val m = model(null, null)
    assertTrue("no frame size → the overflow still grows the canvas (${m.width})", m.width > 400)
  }

  @Test
  fun anOnScreenSliverOfAPartlyOffscreenItemSurvives() {
    // Clamping is on the canvas, not on the layers: a card straddling the right edge keeps its
    // visible sliver, exactly as the render paints it.
    val m = model(400, 800)
    val svg = FigmaLayeredSvg.render(m)
    assertTrue("the straddling card must still be emitted:\n$svg", svg.contains("""x="397""""))
  }

  @Test
  fun aTreeDrawnEntirelyOffFrameKeepsItsLayersAndTheirCrops() {
    // Pathological capture: everything composed sits past the frame, so the clamp has nothing to
    // intersect and falls back to the drawn extent — those layers stay on the canvas. The raster
    // targets must stay with them, or the `<image>` they emit would reference a PNG nobody writes.
    val offFrame =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "Box",
        bounds = bounds(0, 0, 400, 800),
        size = LayoutInspectorSize(400, 800),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "photo",
              component = "Image",
              bounds = bounds(600, 900, 720, 1020),
              size = LayoutInspectorSize(120, 120),
            )
          ),
      )
    val m =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(offFrame),
        density = 1f,
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
        frameWidthPx = 400,
        frameHeightPx = 800,
      )
    val svg = FigmaLayeredSvg.render(m)
    val referenced = Regex("""href="([^"]+)"""").findAll(svg).map { it.groupValues[1] }.toList()
    for (href in referenced) {
      assertTrue(
        "every <image href> must have a raster target to write it: $href in ${m.rasterTargets}",
        m.rasterTargets.any { it.href == href },
      )
    }
  }

  @Test
  fun offscreenRasterLayersAreRemovedWithTheirDroppedTargets() {
    val screen =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "Box",
        bounds = bounds(0, 0, 400, 800),
        size = LayoutInspectorSize(400, 800),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF"),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "visible-photo",
              component = "Image",
              bounds = bounds(24, 52, 144, 172),
              size = LayoutInspectorSize(120, 120),
            ),
            LayoutInspectorNode(
              nodeId = "below-fold-photo",
              component = "Image",
              bounds = bounds(24, 900, 144, 1020),
              size = LayoutInspectorSize(120, 120),
            ),
          ),
      )
    val model =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(screen),
        density = 1f,
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
        frameWidthPx = 400,
        frameHeightPx = 800,
      )

    val svg = FigmaLayeredSvg.render(model)
    val referenced = Regex("""href="([^"]+)"""").findAll(svg).map { it.groupValues[1] }.toList()
    assertEquals(listOf("figma-raster/visible_photo.png"), referenced)
    assertEquals(referenced, model.rasterTargets.map { it.href })
    assertTrue(
      "the offscreen crop must not remain as a dangling href:\n$svg",
      "figma-raster/below_fold_photo.png" !in svg,
    )
  }
}
