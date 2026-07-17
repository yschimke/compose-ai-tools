package ee.schimke.composeai.data.layoutinspector

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control vectoriser: a Material control drawn imperatively (Slider / progress / Checkbox /
 * RadioButton) whose captured [ComposeSemanticsControl] state + the render's theme colours are
 * present is emitted as editable `<rect>`/`<circle>`/`<path>` geometry instead of an opaque raster
 * crop; with either input missing it degrades to the exact raster behaviour (no regression).
 */
class FigmaControlSynthesisTest {

  private val scheme =
    mapOf(
      "primary" to "#FF6750A4",
      "onPrimary" to "#FFFFFFFF",
      "surfaceVariant" to "#FFE7E0EC",
      "onSurfaceVariant" to "#FF49454F",
      "outline" to "#FF79747E",
    )

  private fun layout(
    component: String,
    l: Int,
    t: Int,
    r: Int,
    b: Int,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = component,
      component = component,
      bounds = LayoutInspectorBounds(l, t, r, b),
      size = LayoutInspectorSize(r - l, b - t),
      children = children,
    )

  private fun control(l: Int, t: Int, r: Int, b: Int, state: ComposeSemanticsControl) =
    ComposeSemanticsNode(nodeId = "sem", boundsInRoot = "$l,$t,$r,$b", control = state)

  private fun svg(
    node: LayoutInspectorNode,
    sem: ComposeSemanticsNode?,
    colorScheme: Map<String, String>,
  ): Pair<String, FigmaSvgModel> {
    val model =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(node),
        semantics = sem?.let { ComposeSemanticsPayload(it) },
        colorScheme = colorScheme,
        density = 1f,
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
        captureCanvasDraws = true,
      )
    val out = FigmaLayeredSvg.render(model)
    assertWellFormedXml(out)
    return out to model
  }

  private fun assertWellFormedXml(s: String) {
    DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun checkboxChecked_emitsPrimaryBoxAndTick() {
    val node = layout("CheckboxKt", 0, 0, 48, 48)
    val sem = control(0, 0, 48, 48, ComposeSemanticsControl(toggle = "on"))
    val (out, model) = svg(node, sem, scheme)
    assertTrue("filled primary box", out.contains("""fill="#6750A4""""))
    assertTrue("checkmark path", out.contains("""id="Checkmark""""))
    assertTrue("tick stroked onPrimary", out.contains("""stroke="#FFFFFF""""))
    assertTrue("no raster crop", model.rasterTargets.isEmpty())
    assertFalse("no <image>", out.contains("<image"))
  }

  @Test
  fun checkboxUnchecked_emitsOutlineOnly() {
    val node = layout("CheckboxKt", 0, 0, 48, 48)
    val sem = control(0, 0, 48, 48, ComposeSemanticsControl(toggle = "off"))
    val (out, _) = svg(node, sem, scheme)
    assertTrue("outline stroke", out.contains("""stroke="#49454F""""))
    assertFalse("no primary fill", out.contains("""fill="#6750A4""""))
  }

  @Test
  fun radioSelected_emitsRingAndDot() {
    val node = layout("RadioButtonKt", 0, 0, 48, 48)
    val sem = control(0, 0, 48, 48, ComposeSemanticsControl(selected = true))
    val (out, _) = svg(node, sem, scheme)
    assertTrue("primary ring stroke", out.contains("""stroke="#6750A4""""))
    assertTrue("dot layer", out.contains("""id="Dot""""))
    assertTrue("dot filled primary", out.contains("""fill="#6750A4""""))
  }

  @Test
  fun slider_emitsTrackAndThumb_andPreemptsRaster() {
    val node = layout("SliderKt", 0, 0, 220, 48)
    val sem = control(0, 0, 220, 48, ComposeSemanticsControl(progress = 0.5f))
    val (out, model) = svg(node, sem, scheme)
    assertTrue("active track", out.contains("""id="ActiveTrack""""))
    assertTrue("inactive track", out.contains("""id="InactiveTrack""""))
    assertTrue("thumb", out.contains("""id="Thumb""""))
    assertTrue("no raster crop", model.rasterTargets.isEmpty())
    assertFalse("no <image>", out.contains("<image"))
  }

  @Test
  fun slider_withoutColorScheme_fallsBackToRaster() {
    val node = layout("SliderKt", 0, 0, 220, 48)
    val sem = control(0, 0, 220, 48, ComposeSemanticsControl(progress = 0.5f))
    // Slider is in DEFAULT_RASTER_COMPONENTS: with no theme colours the synthesiser declines and
    // the
    // opaque-by-name raster path runs exactly as before.
    val (out, model) = svg(node, sem, emptyMap())
    assertEquals("rastered", 1, model.rasterTargets.size)
    assertTrue("has <image>", out.contains("<image"))
    assertFalse("no synthesized track", out.contains("""id="ActiveTrack""""))
  }

  @Test
  fun linearProgress_emitsTrackAndProgress() {
    val node = layout("LinearProgressIndicator", 0, 0, 220, 4)
    val sem = control(0, 0, 220, 4, ComposeSemanticsControl(progress = 0.6f))
    val (out, _) = svg(node, sem, scheme)
    assertTrue("track", out.contains("""id="Track""""))
    assertTrue("progress", out.contains("""id="Progress""""))
    assertTrue("progress filled primary", out.contains("""fill="#6750A4""""))
    assertTrue("track filled surfaceVariant", out.contains("""fill="#E7E0EC""""))
  }

  @Test
  fun circularProgress_emitsArcPath() {
    val node = layout("CircularProgressIndicator", 0, 0, 40, 40)
    val sem = control(0, 0, 40, 40, ComposeSemanticsControl(progress = 0.6f))
    val (out, _) = svg(node, sem, scheme)
    assertTrue("arc path present", out.contains(" A "))
    assertTrue("arc stroked primary", out.contains("""stroke="#6750A4""""))
    assertFalse("arc has no fill", out.contains("""fill="#6750A4""""))
  }

  @Test
  fun progressWithoutColorScheme_isNotSynthesised() {
    val node = layout("LinearProgressIndicator", 0, 0, 220, 4)
    val sem = control(0, 0, 220, 4, ComposeSemanticsControl(progress = 0.6f))
    val (out, _) = svg(node, sem, emptyMap())
    assertFalse("no synthesized progress", out.contains("""id="Progress""""))
  }
}
