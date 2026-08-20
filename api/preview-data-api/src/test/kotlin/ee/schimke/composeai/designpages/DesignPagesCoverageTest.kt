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
    inventory: Boolean = true,
  ) =
    PageNode(
      nodeId = id,
      name = name,
      depth = depth,
      ref = "figma:file/$id",
      link = link,
      container = container,
      type = type,
      inventory = inventory,
    )

  private fun page(vararg nodes: PageNode, inventory: Boolean = true) =
    DesignPage(
      id = "shape",
      name = "Shape",
      nodeId = "0:1",
      frame = PageFrame(1200.0, 800.0),
      image = PageImage(uri = "shape.svg", format = PageImage.SVG),
      nodes = nodes.toList(),
      inventory = inventory,
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

  /**
   * The kit names the Shape page's header `.Header` and every other page's plain `Header`, so the
   * leading-dot rule alone cleared the one sheet it was written against and left the header red and
   * clickable on the other twenty-seven. The type is the durable fact: a header is an `INSTANCE`
   * wherever it is drawn, whatever the designer called the layer.
   */
  @Test
  fun `a page header is furniture even when the designer left the dot off`() {
    val subject =
      page(
        node("1:8", "Basic dialog", 2, PageNodeLink.UNLINKED, type = "COMPONENT_SET"),
        node("1:9", "Icon=False", 3, PageNodeLink.MANIFEST, type = "COMPONENT"),
        node("1:10", "Header", 2, PageNodeLink.UNLINKED, type = "INSTANCE"),
      )

    assertEquals(emptyList(), subject.coverageGaps.map { it.name })
    assertEquals(1, subject.coverageTotal)
    // Not a gap AND not a component: `isComponent` is what earns a node its outline and its hit
    // area, so this is the half of the fix that stops the header being selectable at all.
    assertEquals(listOf("Icon=False"), subject.nodes.filter { it.isComponent }.map { it.name })
  }

  /**
   * Why a placement is dropped rather than the walk being taught to skip instances: the definition
   * is on the sheet too. The kit's Sheets page draws four `Side Sheet` instances beside the variant
   * set that defines them, so counting both reported one missing component twice.
   */
  @Test
  fun `an instance beside the set that defines it is not a second missing component`() {
    val subject =
      page(
        node("1:1", "Side Sheet", 2, PageNodeLink.UNLINKED, type = "INSTANCE"),
        node("1:2", "Side Sheet", 2, PageNodeLink.UNLINKED, type = "COMPONENT_SET"),
        node("1:3", "Type=Modal", 3, PageNodeLink.UNLINKED, type = "COMPONENT"),
      )

    assertEquals(listOf("Type=Modal"), subject.coverageGaps.map { it.name })
    assertEquals(1, subject.coverageTotal)
  }

  /**
   * The exception, and the reason this is gated on the link rather than on the type alone. Naming
   * an instance's node id in `design-map.json` is a deliberate claim that this placement is the
   * thing we draw — the Snackbar sheet does it for six of its ten snackbars — and an authored
   * mapping outranks the node's type.
   */
  @Test
  fun `an instance the manifest maps is still a component we implement`() {
    val subject =
      page(
        node("1:1", "Snackbar", 3, PageNodeLink.MANIFEST, type = "INSTANCE"),
        node("1:2", "Snackbar", 3, PageNodeLink.UNLINKED, type = "INSTANCE"),
      )

    assertEquals(listOf("Snackbar"), subject.linked.map { it.name })
    assertEquals(emptyList(), subject.coverageGaps.map { it.name })
    assertEquals(1, subject.coverageTotal)
  }

  /**
   * The kit's own base parts. `Base / SelectionControl / Switch` and friends are what a published
   * set is assembled FROM, not something a catalog implements, and `kit-sets.json` already excludes
   * them from the kit walk — but the Buttons sheet counted 24 of them and reported missing work no
   * code could ever clear.
   *
   * Read off the node, like every other exclusion here, because the flat list has no ancestors: a
   * bare `Selected=Yes, Disabled=No` variant does not say which set it came out of.
   */
  @Test
  fun `the kit's own base parts are not components we owe`() {
    val subject =
      page(
        node(
          "1:1",
          "Base / SelectionControl / Switch",
          2,
          PageNodeLink.UNLINKED,
          type = "COMPONENT_SET",
          inventory = false,
        ),
        node(
          "1:2",
          "Selected=Yes",
          3,
          PageNodeLink.UNLINKED,
          type = "COMPONENT",
          inventory = false,
        ),
        node("1:3", "Toggle+Selection-Buttons", 2, PageNodeLink.UNLINKED, type = "COMPONENT_SET"),
        node("1:4", "Type=Switch", 3, PageNodeLink.MANIFEST, type = "COMPONENT"),
        node("1:5", "Type=Custom - Task", 3, PageNodeLink.UNLINKED, type = "COMPONENT"),
      )

    assertEquals(listOf("Type=Custom - Task"), subject.coverageGaps.map { it.name })
    assertEquals(listOf("Type=Switch"), subject.linked.map { it.name })
    assertEquals(2, subject.coverageTotal)
    // Uncounted AND unpointable, the same outcome a private component gets: a base part is not
    // something a reader of this sheet has any use for selecting.
    assertEquals(
      listOf("Type=Switch", "Type=Custom - Task"),
      subject.nodes.filter { it.isComponent }.map { it.name },
    )
  }

  /**
   * A whole sheet that is not a component inventory — the kit's icon page, 499 `COMPONENT` nodes
   * that are an icon set. It reported `0 of 499` and was a third of the entire kit's apparent gap.
   *
   * The page is still imported and still browsable; what changes is that it makes no claim. The
   * numerator goes with the denominator, so no consumer can print `12 of 0`.
   */
  @Test
  fun `a sheet that is not a component inventory states no fraction`() {
    val subject =
      page(
        node("1:1", "Icon=Alarm", 2, PageNodeLink.MANIFEST, type = "COMPONENT"),
        node("1:2", "Icon=Bell", 2, PageNodeLink.UNLINKED, type = "COMPONENT"),
        inventory = false,
      )

    assertEquals(0, subject.coverageTotal)
    assertEquals(emptyList(), subject.linked.map { it.name })
    assertEquals(emptyList(), subject.coverageGaps.map { it.name })
    // `nodes` stays an honest query — the sheet still draws, and still says how much is on it.
    assertEquals(2, subject.nodes.size)
  }

  /** A manifest published before either field existed counts exactly what it counted before. */
  @Test
  fun `both flags default to counted`() {
    val subject = page(node("1:1", "Shape=Circle", 3, PageNodeLink.MANIFEST, type = "COMPONENT"))

    assertEquals(1, subject.coverageTotal)
    assertEquals(true, subject.inventory)
    assertEquals(true, subject.nodes.single().inventory)
  }
}
