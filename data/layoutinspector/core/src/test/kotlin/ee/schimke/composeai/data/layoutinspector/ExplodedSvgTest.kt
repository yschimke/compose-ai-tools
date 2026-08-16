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

  /**
   * An elevation shadow is a `filter` on the named group that wraps a composable's surface *and*
   * its descendants. A filter renders from whatever its element actually draws, so copying it onto
   * every plane's fragment of that group gives the `Card` its intended silhouette shadow **and**
   * mints a second, text-shaped shadow on the plane holding its `Text` — something nothing casts in
   * the flat export.
   */
  @Test
  fun `an elevation filter rides only the plane holding the elevated surface`() {
    val elevated =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
      <filter id="shadow-2"><feDropShadow dx="0" dy="2" stdDeviation="2"/></filter>
      <g transform="translate(0, 0)">
        <g id="Card" filter="url(#shadow-2)">
          <rect x="10" y="10" width="80" height="60" rx="8" fill="#FFFFFF"/>
          <g id="Text"><text x="20" y="40">Hello</text></g>
        </g>
      </g>
      </svg>
      """
        .trimIndent()
    val planes = planes(ExplodedSvg.render(elevated))
    fun filters(plane: Element) =
      plane.descendants("g").map { it.getAttribute("filter") }.filter { it.isNotBlank() }
    // Logical plane 1 owns the Card's own surface, so the shadow belongs there…
    assertEquals(listOf("url(#shadow-2)"), filters(planes[0]))
    // …and nowhere else: logical plane 2 holds only a transform-carrying fragment of the group.
    assertEquals(emptyList<String>(), filters(planes[1]))
    // The def still rides along for the plane that does reference it.
    assertEquals(1, parse(ExplodedSvg.render(elevated)).descendants("filter").size)
  }

  /**
   * The other half of the filter rule. An elevated layer that paints nothing itself — a wrapper
   * whose whole job is the shadow, drawing only through a nested named child — is retained on no
   * plane at its own nominal depth. Keying the filter on that depth would strip it from every
   * fragment and lose the shadow entirely, which is worse than the duplication it replaced. It
   * rides the shallowest plane the group survives on instead, which for such a wrapper is its
   * child's.
   */
  @Test
  fun `an elevated wrapper that paints only through a child keeps its shadow`() {
    val wrapper =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
      <filter id="shadow-3"><feDropShadow dx="0" dy="2" stdDeviation="2"/></filter>
      <g transform="translate(0, 0)">
        <g id="Elevated" filter="url(#shadow-3)">
          <g id="Surface"><rect x="10" y="10" width="80" height="60" rx="8" fill="#FFFFFF"/></g>
        </g>
      </g>
      </svg>
      """
        .trimIndent()
    val out = ExplodedSvg.render(wrapper)
    val filters =
      parse(out).descendants("g").map { it.getAttribute("filter") }.filter { it.isNotBlank() }
    assertEquals("kept exactly once, not lost and not duplicated", 1, filters.size)
    assertEquals("url(#shadow-3)", filters.single())
    // It rides the plane the wrapper first appears on — the one holding the drawing it elevates.
    val planes = planes(out)
    assertTrue(
      "the shadow is on the plane that draws the surface",
      planes.last().descendants("g").any { it.getAttribute("filter") == "url(#shadow-3)" },
    )
    // The wrapper's structural-only depth does not become a giant empty sheet. Its name is folded
    // into the next visible sheet's breadcrumb, so the nesting remains legible.
    assertEquals(1, planes.size)
    assertEquals("Surface", planes.single().getAttribute("data-layers"))
    val label =
      parse(out).descendants("text").single { it.getAttribute("class") == "cp-exploded-label" }
    assertEquals("Elevated › Surface", label.textContent)
  }

  @Test
  fun `structural-only depths are folded into the next visible sheet`() {
    val structural =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="200" height="400" viewBox="0 0 200 400">
        <g id="Root">
          <g id="Column">
            <g id="Card"><rect x="16" y="24" width="168" height="96" fill="#E8DEF8"/></g>
          </g>
        </g>
      </svg>
      """
        .trimIndent()
    val out = ExplodedSvg.render(structural)
    val planes = planes(out)
    assertEquals("one drawing depth means one visible sheet", 1, planes.size)
    assertEquals("3", planes.single().getAttribute("data-plane"))
    val labels =
      parse(out).descendants("text").filter { it.getAttribute("class") == "cp-exploded-label" }
    assertEquals(listOf("Column › Card"), labels.map { it.textContent })
  }

  @Test
  fun `a raw exploded svg fits the browser viewport without losing intrinsic dimensions`() {
    val root = parse(ExplodedSvg.render(layered))
    assertEquals(root.getAttribute("viewBox").split(" ")[2], root.getAttribute("width"))
    assertEquals(root.getAttribute("viewBox").split(" ")[3], root.getAttribute("height"))
    val css = root.descendants("style").joinToString("\n") { it.textContent }
    assertTrue("raw SVG should fit viewport width: $css", css.contains("max-width:100vw"))
    assertTrue("raw SVG should fit viewport height: $css", css.contains("max-height:100vh"))
  }

  @Test
  fun `resources are carried over exactly once`() {
    val root = parse(ExplodedSvg.render(layered))
    assertEquals(1, root.descendants("clipPath").size)
  }

  /**
   * `FigmaLayeredSvg` emits a `Modifier.clip` mask *inline* — as a sibling of the named layer it
   * masks, deep in the content tree — not as a child of the SVG root. Collecting only the root's
   * resources dropped every one of them while the copied `<g clip-path="url(#clip-0)">` wrapper
   * kept referencing the missing id, so a rounded image spilled out of its mask (or vanished,
   * depending on how the renderer treats a dangling reference). Every plane that carries the
   * reference must therefore find the def.
   */
  @Test
  fun `a clip mask nested in the content tree is hoisted, not dropped`() {
    val nestedClip =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
      <g transform="translate(0, 0)">
        <g id="Card">
          <rect x="0" y="0" width="100" height="100" fill="#FFFFFF"/>
          <clipPath id="clip-0"><rect x="10" y="10" width="80" height="80" rx="12"/></clipPath>
          <g clip-path="url(#clip-0)">
            <g id="Image"><rect x="0" y="0" width="100" height="100" fill="#E8DEF8"/></g>
          </g>
        </g>
      </g>
      </svg>
      """
        .trimIndent()
    val out = ExplodedSvg.render(nestedClip)
    val root = parse(out)
    val defs = root.descendants("clipPath")
    assertEquals("hoisted exactly once", 1, defs.size)
    assertEquals("clip-0", defs.single().getAttribute("id"))
    // …and hoisted to the root, so it is not carried inside (or dropped with) any one sheet.
    assertEquals("svg", (defs.single().parentNode as Element).localName)
    // The plane that holds the masked drawing still references it.
    val masked = planes(out).last().descendants("g").filter { it.hasAttribute("clip-path") }
    assertEquals(listOf("url(#clip-0)"), masked.map { it.getAttribute("clip-path") })
  }

  /**
   * Labels are nudged apart to keep a readable line spacing, which at a small separation carries
   * the column past the lowest sheet corner. An SVG has no overflow to scroll into, so a canvas
   * measured from the sheets alone would simply crop them.
   */
  @Test
  fun `a label pushed clear of its sheet still fits the canvas`() {
    val out = ExplodedSvg.render(layered, ExplodedSvg.Options(gap = 1.0))
    val root = parse(out)
    val viewBox = root.getAttribute("viewBox").split(" ").map { it.toDouble() }
    val bottom = viewBox[1] + viewBox[3]
    val labels = root.descendants("text").filter { it.getAttribute("class") == "cp-exploded-label" }
    assertEquals(3, labels.size)
    // The nudges are what makes this a real test: with a 1-unit gap the sheets are effectively
    // stacked, so the labels can only be told apart by having been pushed down.
    val ys = labels.map { it.getAttribute("y").toDouble() }
    assertTrue("labels were spread: $ys", ys.max() - ys.min() > 10.0)
    for (y in ys) assertTrue("label at $y is inside the canvas (bottom $bottom)", y < bottom)
  }

  /**
   * A hand-typed or stale `?explodeGap=3000000` must produce a picture, not a canvas whose numbers
   * no longer survive formatting — `(v * 1000).roundToInt()` saturates past ~2.1e6 and would
   * collapse every dimension onto the same value.
   */
  @Test
  fun `an absurd separation is bounded rather than overflowing the canvas`() {
    val root = parse(ExplodedSvg.render(layered, ExplodedSvg.Options(gap = 3_000_000.0)))
    val viewBox = root.getAttribute("viewBox").split(" ").map { it.toDouble() }
    assertTrue("height is finite and sane: ${viewBox[3]}", viewBox[3] in 100.0..20_000.0)
    // Still genuinely exploded — the bound loosens the stack, it doesn't collapse it.
    val planes = planes(ExplodedSvg.render(layered, ExplodedSvg.Options(gap = 3_000_000.0)))
    assertEquals(3, planes.size)
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
    // Root (1) then the icon group (2) — the `<g id>` inside <defs> must not count as a third, and
    // the two non-drawing depths must not become empty sheets.
    assertEquals(1, planes.size)
    assertEquals("2", planes.single().getAttribute("data-plane"))
    assertEquals(1, planes.single().descendants("use").size)
    // A fallback layer id is replaced by the icon it draws rather than repeated as
    // "ReusableComposeNode".
    assertEquals("menu icon", planes.single().getAttribute("data-layers"))
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
    assertEquals(listOf("Box", "Column"), planes(out).map { it.getAttribute("data-layers") })
    val labels = root.descendants("text").filter { it.getAttribute("class") == "cp-exploded-label" }
    assertEquals(listOf("Row › Column", "Box"), labels.map { it.textContent })
  }
}
