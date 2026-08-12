package ee.schimke.composeai.data.layoutinspector

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * [ExplodedSvg] is a pure SVG→SVG rewrite, so it is tested the way [FigmaLayeredSvg] is: feed it
 * markup shaped like a real layered export and assert on the markup that comes back.
 *
 * The load-bearing properties are (a) each drawing element lands on the plane matching its
 * composable nesting depth, (b) the enclosing transform / clip chain survives so nothing moves
 * within its plane, (c) resources are carried over exactly once, and (d) a malformed or geometry-
 * free input degrades to the untouched flat SVG rather than to a broken response.
 */
class ExplodedSvgTest {

  /**
   * A three-level layered export: the frame's own background rect, a `Card` with a fill, and a
   * `Text` inside it — the smallest input that exercises every plane rule.
   */
  private val layered =
    """
    <svg xmlns="http://www.w3.org/2000/svg" width="200" height="400" viewBox="0 0 200 400" font-family="sans-serif">
    <clipPath id="deviceRound"><rect x="0" y="0" width="200" height="400" rx="24"/></clipPath>
    <g transform="translate(0, 0)" clip-path="url(#deviceRound)">
      <rect x="0" y="0" width="200" height="400" fill="#FFFFFF"/>
      <g id="Card">
        <rect x="16" y="24" width="168" height="96" rx="16" fill="#E8DEF8"/>
        <g id="Text">
          <text x="32" y="64" font-size="16" fill="#1D1B20">Hello</text>
        </g>
      </g>
    </g>
    </svg>
    """
      .trimIndent()

  private fun parse(svg: String): Element =
    DocumentBuilderFactory.newInstance()
      .also { it.isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(svg.byteInputStream())
      .documentElement

  private fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagNameNS("http://www.w3.org/2000/svg", tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  private fun planes(svg: String): List<Element> =
    parse(svg).descendants("g").filter { it.getAttribute("class") == "cp-exploded-plane" }

  @Test
  fun `splits a layered export into one plane per composable nesting level`() {
    val out = ExplodedSvg.render(layered)
    val planes = planes(out)
    assertEquals("frame + Card + Text", 3, planes.size)
    assertEquals(listOf("0", "1", "2"), planes.map { it.getAttribute("data-plane") })
    assertEquals("Card", planes[1].getAttribute("data-layers"))
    assertEquals("Text", planes[2].getAttribute("data-layers"))
  }

  @Test
  fun `each drawing element lands on exactly one plane`() {
    val planes = planes(ExplodedSvg.render(layered))
    // Plane 0 is the frame's own background; the plate outline this adds is not part of the
    // source drawing, so it is excluded by class.
    fun drawn(plane: Element, tag: String) =
      plane.descendants(tag).filter { it.getAttribute("class") != "cp-exploded-plate" }
    assertEquals(1, drawn(planes[0], "rect").size)
    assertEquals("#FFFFFF", drawn(planes[0], "rect").single().getAttribute("fill"))
    assertEquals(1, drawn(planes[1], "rect").size)
    assertEquals("#E8DEF8", drawn(planes[1], "rect").single().getAttribute("fill"))
    assertEquals(0, drawn(planes[1], "text").size)
    assertEquals(1, drawn(planes[2], "text").size)
    assertEquals(0, drawn(planes[2], "rect").size)
  }

  @Test
  fun `the enclosing transform and clip chain is preserved on every plane`() {
    for (plane in planes(ExplodedSvg.render(layered))) {
      val wrapper = plane.descendants("g").first { it.hasAttribute("clip-path") }
      assertEquals("translate(0, 0)", wrapper.getAttribute("transform"))
      assertEquals("url(#deviceRound)", wrapper.getAttribute("clip-path"))
    }
  }

  @Test
  fun `a layer id rides only on the plane its own drawing belongs to`() {
    val planes = planes(ExplodedSvg.render(layered))
    fun ids(plane: Element) =
      plane.descendants("g").map { it.getAttribute("id") }.filter { it.isNotBlank() }
    assertEquals(emptyList<String>(), ids(planes[0]))
    // Plane 1 carries `Card`'s own fill, so the Card group keeps its name there…
    assertEquals(listOf("Card"), ids(planes[1]))
    // …and plane 2 holds a *transform-carrying* copy of the same Card group, which must not claim
    // the name a second time, plus the Text group whose drawing this actually is.
    assertEquals(listOf("Text"), ids(planes[2]))
  }

  @Test
  fun `resources are carried over exactly once`() {
    val root = parse(ExplodedSvg.render(layered))
    assertEquals(1, root.descendants("clipPath").size)
  }

  @Test
  fun `planes are emitted back to front and separated along the page`() {
    val planes = planes(ExplodedSvg.render(layered))
    fun ty(plane: Element): Double =
      Regex("matrix\\(([^)]*)\\)")
        .find(plane.getAttribute("transform"))!!
        .groupValues[1]
        .split(" ")[5]
        .toDouble()
    // Deeper planes float toward the viewer, which this camera projects as further UP the page —
    // a smaller y. Document order is therefore already back-to-front (no depth sorting needed).
    assertTrue("${ty(planes[0])} > ${ty(planes[1])}", ty(planes[0]) > ty(planes[1]))
    assertTrue("${ty(planes[1])} > ${ty(planes[2])}", ty(planes[1]) > ty(planes[2]))
  }

  /**
   * The separation must survive a face-on camera. Physically the sheets separate along the plane
   * normal, which projects to `sin(lean)` and so vanishes at 0° — meaning a physically-honest
   * camera could only explode a screen by first laying it flat, which is precisely the view this
   * feature is not supposed to force. Pinned as a test because it is the one property a future
   * "make the projection more correct" change would quietly delete.
   */
  @Test
  fun `a face-on lean still separates the sheets, and stops foreshortening them`() {
    fun matrixOf(plane: Element): List<Double> =
      Regex("matrix\\(([^)]*)\\)")
        .find(plane.getAttribute("transform"))!!
        .groupValues[1]
        .split(" ")
        .map { it.toDouble() }
    val flat = planes(ExplodedSvg.render(layered, ExplodedSvg.Options(tiltDeg = 0.0, gap = 90.0)))
    assertTrue("still one sheet per level", flat.size == 3)
    val offsets = flat.map { matrixOf(it)[5] }
    assertEquals(-90.0, offsets[1] - offsets[0], 0.001)
    assertEquals(-90.0, offsets[2] - offsets[1], 0.001)
    // At 0° the matrix is a pure rotation by the spin: the drawing keeps its proportions, so a
    // portrait preview still reads as a portrait preview.
    val m = matrixOf(flat[0])
    val a = m[0]
    val b = m[1]
    val c = m[2]
    val d = m[3]
    assertEquals(1.0, a * d - b * c, 0.001)
    assertEquals(a, d, 0.001)
    assertEquals(-c, b, 0.001)
  }

  @Test
  fun `the canvas grows to hold the whole stack`() {
    fun canvas(gap: Double): List<Double> =
      parse(ExplodedSvg.render(layered, ExplodedSvg.Options(gap = gap)))
        .getAttribute("viewBox")
        .split(" ")
        .map { it.toDouble() }
    // An SVG has no overflow to scroll into, so the viewBox is the only thing standing between a
    // wider separation and clipped sheets. Pulling the stack further apart must widen the canvas
    // by the same amount, not crop it.
    val tight = canvas(20.0)
    val loose = canvas(200.0)
    assertEquals("the spin is what widens it, and that is unchanged", tight[2], loose[2], 0.01)
    assertTrue("${loose[3]} > ${tight[3]}", loose[3] > tight[3] + 250)
    val root = parse(ExplodedSvg.render(layered))
    val viewBox = root.getAttribute("viewBox").split(" ").map { it.toDouble() }
    assertTrue("the spin widens it past the flat 200: ${viewBox[2]}", viewBox[2] > 200)
    assertEquals(viewBox[2], root.getAttribute("width").toDouble(), 0.01)
    assertEquals(viewBox[3], root.getAttribute("height").toDouble(), 0.01)
  }

  @Test
  fun `labels name the composables on each plane and can be turned off`() {
    val labelled = parse(ExplodedSvg.render(layered))
    val texts =
      labelled.descendants("text").filter { it.getAttribute("class") == "cp-exploded-label" }
    assertEquals(listOf("Text", "Card", "Frame"), texts.map { it.textContent })
    val bare = parse(ExplodedSvg.render(layered, ExplodedSvg.Options(labels = false)))
    assertTrue(bare.descendants("text").none { it.getAttribute("class") == "cp-exploded-label" })
  }

  @Test
  fun `maxDepth folds deeper composables into the last plane`() {
    val out = ExplodedSvg.render(layered, ExplodedSvg.Options(maxDepth = 1))
    val planes = planes(out)
    assertEquals(2, planes.size)
    // Both the Card's fill and the Text now paint on plane 1.
    assertEquals(1, planes[1].descendants("text").size)
    assertEquals(
      1,
      planes[1].descendants("rect").count { it.getAttribute("class") != "cp-exploded-plate" },
    )
  }

  @Test
  fun `a hoisted material icon def is a resource, not a nesting level`() {
    val withDefs =
      """
      <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="160" height="48" viewBox="0 0 160 48">
      <defs><g id="material-icon-materialicons-menu"><path d="M3 18 h18 v-2 Z" fill="#1D1B20"/></g></defs>
      <g transform="translate(0, 0)">
        <g id="Root">
          <g id="ReusableComposeNode" data-material-icon="menu">
            <use href="#material-icon-materialicons-menu"/>
          </g>
        </g>
      </g>
      </svg>
      """
        .trimIndent()
    val planes = planes(ExplodedSvg.render(withDefs))
    // Root (1) then the icon group (2) — the `<g id>` inside <defs> must not count as a third.
    assertEquals(3, planes.size)
    assertEquals(1, planes[2].descendants("use").size)
    // A fallback layer id is replaced by the icon it draws rather than repeated as
    // "ReusableComposeNode".
    assertEquals("menu icon", planes[2].getAttribute("data-layers"))
  }

  @Test
  fun `unparseable or geometry-free input degrades to the flat svg`() {
    assertEquals("not svg at all", ExplodedSvg.render("not svg at all"))
    val noGeometry = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>"""
    assertEquals(noGeometry, ExplodedSvg.render(noGeometry))
    val html = "<html><body>hi</body></html>"
    assertEquals(html, ExplodedSvg.render(html))
  }

  @Test
  fun `a doctype is refused rather than resolved`() {
    val xxe =
      """
      <!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10">
        <g id="Root"><rect width="10" height="10" fill="&xxe;"/></g>
      </svg>
      """
        .trimIndent()
    assertEquals(xxe, ExplodedSvg.render(xxe))
  }

  @Test
  fun `the real timepicker export explodes without losing its rasters`() {
    val svg =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="486" height="486" viewBox="0 0 486 486" text-rendering="geometricPrecision" font-family="sans-serif">
      <clipPath id="deviceRound"><circle cx="227" cy="227" r="227"/></clipPath>
      <g transform="translate(16, 16)" clip-path="url(#deviceRound)">
        <g id="Root">
          <g id="Box">
              <rect x="0" y="0" width="454" height="454" fill="#000000"/>
            <g id="Row">
              <g id="Column">
                <image href="figma-raster/5.png" x="94" y="2" width="128" height="450"/>
              </g>
            </g>
          </g>
        </g>
      </g>
      </svg>
      """
        .trimIndent()
    val out = ExplodedSvg.render(svg)
    val root = parse(out)
    assertEquals("figma-raster/5.png", root.descendants("image").single().getAttribute("href"))
    assertEquals("true", root.getAttribute("data-exploded"))
    assertNotNull(root.descendants("clipPath").singleOrNull())
    assertEquals(
      listOf("Box", "Row", "Column"),
      planes(out).drop(2).map { it.getAttribute("data-layers") },
    )
  }
}
