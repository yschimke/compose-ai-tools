package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stock Material icon exports as a **named reference**: its canonical fonts.google.com identity
 * on the layer, its geometry hoisted into a shared `<defs>` entry, and a `<use>` at each placement.
 *
 * The invariant under all of it — the drawing never changes. The def holds the captured paths, so
 * an icon the mapping doesn't recognise, or a run with references switched off, emits exactly the
 * inline paths it always did.
 */
class FigmaSvgMaterialIconRefTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** `Icons.Filled.Menu`'s real geometry, as the capture reflects it off the `VectorPainter`. */
  private fun menu(vectorName: String?, fill: String = "#FF112233") =
    LayoutInspectorVectorGraphic(
      viewportWidth = 24f,
      viewportHeight = 24f,
      paths =
        listOf(
          LayoutInspectorVectorPath(
            pathData = "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z",
            fillArgb = fill,
          )
        ),
      vectorName = vectorName,
    )

  private fun iconNode(
    graphic: LayoutInspectorVectorGraphic?,
    id: String = "icon-1",
    left: Int = 0,
  ) =
    LayoutInspectorNode(
      nodeId = id,
      component = "Icon",
      bounds = bounds(left, 0, left + 24, 24),
      size = LayoutInspectorSize(24, 24),
      vectorGraphic = graphic,
    )

  private fun render(
    root: LayoutInspectorNode,
    options: FigmaLayeredSvg.Options = FigmaLayeredSvg.Options(),
  ): String =
    FigmaLayeredSvg.render(
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(root),
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
      ),
      options,
    )

  private fun row(vararg children: LayoutInspectorNode) =
    LayoutInspectorNode(
      nodeId = "row",
      component = "Row",
      bounds = bounds(0, 0, 96, 24),
      size = LayoutInspectorSize(96, 24),
      children = children.toList(),
    )

  @Test
  fun materialIconCarriesItsCanonicalIdentity() {
    val svg = render(iconNode(menu("Filled.Menu")))
    assertTrue("names the canonical icon", svg.contains("""data-material-icon="menu""""))
    assertTrue("names the style", svg.contains("""data-material-icon-style="Filled""""))
    assertTrue(
      "points at the exact drawing on Google's icon CDN",
      svg.contains(
        """data-material-icon-url="https://fonts.gstatic.com/s/i/materialicons/menu/v1/24px.svg""""
      ),
    )
  }

  @Test
  fun geometryIsHoistedIntoDefsAndReferenced() {
    val svg = render(iconNode(menu("Filled.Menu")))
    assertTrue(
      "the captured paths live in a shared def",
      svg.contains("""<g id="material-icon-materialicons-menu">"""),
    )
    assertTrue(
      "the def holds the geometry Compose drew",
      svg.contains("""<path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z" fill="#112233"/>"""),
    )
    assertTrue(
      "the placement references it",
      svg.contains("""<use href="#material-icon-materialicons-menu""""),
    )
    // SVG 1.1 consumers never learned the SVG 2 `href`, and an unresolved `<use>` draws nothing.
    assertTrue(
      "carries the legacy reference too",
      svg.contains("""xlink:href="#material-icon-materialicons-menu""""),
    )
    assertTrue(
      "…and declares the namespace it needs",
      svg.contains("""xmlns:xlink="http://www.w3.org/1999/xlink""""),
    )
    assertEquals(
      "exactly one definition",
      1,
      Regex("""<g id="material-icon-""").findAll(svg).count(),
    )
  }

  @Test
  fun repeatedIconSharesOneDefinition() {
    val svg =
      render(
        row(
          iconNode(menu("Filled.Menu"), id = "a", left = 0),
          iconNode(menu("Filled.Menu"), id = "b", left = 32),
          iconNode(menu("Filled.Menu"), id = "c", left = 64),
        )
      )
    assertEquals(
      "three placements collapse to one def",
      1,
      Regex("""<g id="material-icon-materialicons-menu">""").findAll(svg).count(),
    )
    assertEquals("…referenced three times", 3, Regex("""<use href=""").findAll(svg).count())
  }

  @Test
  fun sameIconInTwoTintsGetsTwoDefinitions() {
    // Paint is part of a def's identity, so a second tint can't quietly recolour the first.
    val svg =
      render(
        row(
          iconNode(menu("Filled.Menu", fill = "#FF112233"), id = "a", left = 0),
          iconNode(menu("Filled.Menu", fill = "#FFAABBCC"), id = "b", left = 32),
        )
      )
    assertTrue(svg.contains("""<g id="material-icon-materialicons-menu">"""))
    assertTrue(svg.contains("""<g id="material-icon-materialicons-menu-2">"""))
    assertTrue("each keeps its own paint", svg.contains("""fill="#112233""""))
    assertTrue(svg.contains("""fill="#AABBCC""""))
  }

  @Test
  fun autoMirroredIconIsFlagged() {
    val svg = render(iconNode(menu("AutoMirrored.Outlined.ArrowBack")))
    assertTrue(svg.contains("""data-material-icon="arrow_back""""))
    assertTrue(svg.contains("""data-material-icon-style="Outlined""""))
    assertTrue(svg.contains("""data-material-icon-auto-mirrored="true""""))
    assertTrue(
      svg.contains("""https://fonts.gstatic.com/s/i/materialiconsoutlined/arrow_back/v1/24px.svg""")
    )
  }

  @Test
  fun appArtworkIsUntouched() {
    // An app's own ImageVector: no identity to claim, so it inlines its paths exactly as before.
    val svg = render(iconNode(menu("BrandLogo")))
    assertFalse(svg.contains("data-material-icon"))
    assertFalse(svg.contains("<use "))
    assertTrue(
      svg.contains("""<path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z" fill="#112233"/>""")
    )
  }

  @Test
  fun unnamedVectorIsUntouched() {
    val svg = render(iconNode(menu(vectorName = null)))
    assertFalse(svg.contains("data-material-icon"))
    assertFalse(svg.contains("<use "))
    assertTrue(svg.contains("""<path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z""""))
  }

  @Test
  fun referencesCanBeSwitchedOff() {
    val svg =
      render(iconNode(menu("Filled.Menu")), FigmaLayeredSvg.Options(materialIconRefs = false))
    assertFalse("no def, no reference", svg.contains("<use "))
    assertFalse(svg.contains("material-icon-materialicons-menu"))
    assertFalse("…and no unused namespace", svg.contains("xmlns:xlink"))
    assertTrue(
      "the paths inline exactly as before",
      svg.contains("""<path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z" fill="#112233"/>"""),
    )
    assertFalse("the identity annotation goes with them", svg.contains("data-material-icon"))
  }

  @Test
  fun drawCaptureIsNeverAMaterialIcon() {
    // Imperative chrome (a slider groove, a progress arc) is recorded from the node's own draw
    // lambda — never an ImageVector — so it can't claim an icon identity whatever its name says.
    val graphic =
      LayoutInspectorVectorGraphic(
        viewportWidth = 24f,
        viewportHeight = 24f,
        paths =
          listOf(LayoutInspectorVectorPath(pathData = "M0 0h24v24H0z", fillArgb = "#FF112233")),
        fromDrawCapture = true,
        vectorName = "Filled.Menu",
      )
    val svg = render(iconNode(graphic))
    assertFalse(svg.contains("data-material-icon"))
    assertFalse(svg.contains("<use "))
  }

  @Test
  fun placementTransformIsUnchangedByTheReference() {
    // The `<use>` stands in for the paths inside the same placement group, so an icon lands exactly
    // where its inline-path twin would.
    val withRef = render(iconNode(menu("Filled.Menu")))
    val inline =
      render(iconNode(menu("Filled.Menu")), FigmaLayeredSvg.Options(materialIconRefs = false))
    val transform = Regex("""<g transform="[^"]+"""").find(withRef)?.value
    assertEquals(transform, Regex("""<g transform="[^"]+"""").find(inline)?.value)
  }
}
