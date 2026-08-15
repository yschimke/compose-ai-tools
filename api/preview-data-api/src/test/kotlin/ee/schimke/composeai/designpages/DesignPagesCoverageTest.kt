package ee.schimke.composeai.designpages

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the page means by *what we haven't implemented yet*.
 *
 * The distinction is not cosmetic: the kit's Shape page has all 35 of its shapes implemented and
 * reported `35 of 38` with three dashed-red marks on it, none of which any amount of code could
 * ever clear.
 */
class DesignPagesCoverageTest {

  private fun node(
    id: String,
    name: String,
    depth: Int,
    link: PageNodeLink,
    container: Boolean = false,
    type: String? = null,
  ) =
    PageNode(
      nodeId = id,
      name = name,
      depth = depth,
      ref = "figma:file/$id",
      link = link,
      container = container,
      type = type,
    )

  private fun page(vararg nodes: PageNode) =
    DesignPage(
      id = "shape",
      name = "Shape",
      nodeId = "0:1",
      frame = PageFrame(1200.0, 800.0),
      image = PageImage(uri = "shape.svg", format = PageImage.SVG),
      nodes = nodes.toList(),
    )

  @Test
  fun `a variant set and a private component are structure, not missing work`() {
    val subject =
      page(
        node("1:8", "Shape Set", 2, PageNodeLink.UNLINKED, container = true),
        node("1:1", "Shape=Circle", 3, PageNodeLink.MANIFEST),
        node("1:2", "Shape=Square", 3, PageNodeLink.MANIFEST),
        node("1:9", ".Header", 2, PageNodeLink.UNLINKED),
      )

    // Every one of the three unlinked-or-not nodes above is on the sheet; none of them is work.
    assertEquals(emptyList(), subject.coverageGaps.map { it.name })
    assertEquals(2, subject.coverageTotal)
  }

  @Test
  fun `a component with no code behind it is the gap the filter exists to show`() {
    val subject =
      page(
        node("1:8", "Shape Set", 2, PageNodeLink.UNLINKED, container = true),
        node("1:1", "Shape=Circle", 3, PageNodeLink.MANIFEST),
        node("1:12", "Shape=Gem", 3, PageNodeLink.UNLINKED),
      )

    assertEquals(listOf("Shape=Gem"), subject.coverageGaps.map { it.name })
    assertEquals(2, subject.coverageTotal)
  }

  @Test
  fun `a component set type is a grouping not a giant component hotspot`() {
    val subject =
      page(
        node("1:8", "Input chip", 2, PageNodeLink.UNLINKED, type = "COMPONENT_SET"),
        node("1:9", "State=Enabled", 3, PageNodeLink.MANIFEST),
        node("1:10", "State=Dragged", 3, PageNodeLink.UNLINKED),
      )

    assertEquals(listOf("State=Dragged"), subject.coverageGaps.map { it.name })
    assertEquals(listOf("State=Enabled"), subject.linked.map { it.name })
    assertEquals(2, subject.coverageTotal)
  }

  /**
   * The reason container-ness is STATED rather than worked out from depth ordering.
   *
   * A manifest lists components and nothing else. An unlisted frame between two of them lets a
   * shallower node be followed by a deeper one that is not inside it — and the inference this
   * replaced read exactly that shape and called the shallower node a grouping. Here that node is a
   * genuinely missing component, so the old rule swallowed a real gap as "structure", which is the
   * one direction this must never fail in.
   */
  @Test
  fun `a shallow component followed by a deeper unrelated one is still a gap`() {
    val subject =
      page(
        node("1:30", "Banner", 2, PageNodeLink.UNLINKED),
        node("1:31", "Shape=Circle", 3, PageNodeLink.MANIFEST),
      )

    assertEquals(listOf("Banner"), subject.coverageGaps.map { it.name })
  }
}
