package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the **breakpoint (size) axis** in [ServeWeb]: a component documented at several declared
 * screen sizes shows ONE landing card — its first declared size — with the others reachable from
 * the viewer's component subtree, exactly as a non-default state or props variant is.
 *
 * The bug this answers is wear-m3-catalog#41 ("So many duplicate components"). That catalog renders
 * every full-screen component at the five sizes its Figma kit declares (`192dp` … `240dp`); the
 * export tags each image with its `size`, but the serve layer dropped the tag, so the axis was
 * invisible and 14 components published as 70 cards — five apiece, all wearing the same name.
 */
class ServeWebBreakpointFoldTest {

  /**
   * One render of a full-screen component: a state at a declared breakpoint. Sectioned, because a
   * catalog that documents breakpoints is a catalog with an authored inventory, and the tabbed
   * landing tree this pins is only built for one.
   */
  private fun render(slug: String, componentId: String, state: String, size: String, order: Int) =
    ServePreview(
      id = "${slug}__ideal__${state}__${size}",
      label = "${slug}__ideal__${state}__${size}",
      componentId = componentId,
      state = state,
      size = size,
      section = "Containment",
      group = "Dialogs",
      catalogOrder = order,
    )

  /** The five kit sizes, smallest first, in the order the spec's `breakpoints` declares them. */
  private val sizes = listOf("192dp", "204dp", "216dp", "225dp", "240dp")

  /** `AlertDialog` at all five sizes with its three button arrangements — 15 renders. */
  private val alertDialog = sizes.flatMapIndexed { i, size ->
    listOf("default", "edge-button", "no-buttons").mapIndexed { j, state ->
      render("alertdialog", "AlertDialog", state, size, order = i * 3 + j)
    }
  }

  /** A second, stateless component at the same five sizes — 5 renders. */
  private val openOnPhone = sizes.mapIndexed { i, size ->
    render("openonphonedialog", "OpenOnPhoneDialog", "default", size, order = 100 + i)
  }

  private val catalog = alertDialog + openOnPhone

  @Test
  fun `the grid folds a component's other breakpoints into its one card`() {
    val html = ServeWeb.landingPage("wear-m3-catalog", catalog, token = "t", basePath = "/wear")

    assertEquals(
      2,
      Regex("class=\"cp-card\"").findAll(html).count(),
      "one card per component, not one per breakpoint",
    )
    assertTrue(
      html.contains("alertdialog__ideal__default__192dp"),
      "the first declared breakpoint is the card",
    )
    for (size in sizes.drop(1)) {
      assertFalse(
        html.contains("alertdialog__ideal__default__$size"),
        "$size is folded out of the grid",
      )
    }
  }

  @Test
  fun `the nav tree names each component once`() {
    val html = ServeWeb.landingPage("wear-m3-catalog", catalog, token = "t", basePath = "/wear")

    val rows =
      Regex("class=\"cp-tree-component cp-tree-link\"[^>]*>([^<]*)<")
        .findAll(html)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(
      listOf("Alert Dialog", "Open On Phone Dialog"),
      rows,
      "the tree lists each component once, not once per breakpoint",
    )
  }

  @Test
  fun `the viewer offers every other breakpoint in one hop`() {
    val html =
      ServeWeb.viewerPage(
        alertDialog.first(),
        token = "t",
        basePath = "/wear",
        siblings = catalog,
      )
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")

    for (size in sizes.drop(1)) {
      assertTrue(
        nav.contains("/wear/p/alertdialog__ideal__default__$size"),
        "the size switcher links $size",
      )
    }
    assertTrue(nav.contains(">240dp<"), "a size row is labelled with the catalog's own size name")
  }

  @Test
  fun `a size row holds the state fixed`() {
    val noButtons = alertDialog.first { it.id == "alertdialog__ideal__no-buttons__192dp" }
    val html = ServeWeb.viewerPage(noButtons, token = "t", basePath = "/wear", siblings = catalog)
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")

    assertTrue(
      nav.contains("/wear/p/alertdialog__ideal__no-buttons__204dp"),
      "from `no-buttons` the size axis walks `no-buttons`",
    )
    assertFalse(
      nav.contains("/wear/p/alertdialog__ideal__default__204dp"),
      "a size row never also resets the state",
    )
  }

  @Test
  fun `the viewer's component drawer names each component once`() {
    val html =
      ServeWeb.viewerPage(alertDialog.first(), token = "t", basePath = "/wear", siblings = catalog)
    val drawer = html.substringAfter("<ul class=\"cp-nav-list\"").substringBefore("</ul>")

    val rows =
      Regex("class=\"cp-nav-name\">([^<]*)<").findAll(drawer).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf("Open On Phone Dialog"),
      rows,
      "the drawer lists each OTHER component once, not once per breakpoint",
    )
    assertTrue(
      drawer.contains("/wear/p/openonphonedialog__ideal__default__192dp"),
      "the entry links the component's first declared breakpoint",
    )
    for (size in sizes.drop(1)) {
      assertFalse(
        drawer.contains("openonphonedialog__ideal__default__$size"),
        "$size is folded out of the drawer",
      )
    }
  }

  @Test
  fun `the command palette offers each component once`() {
    val entries = ServeWeb.componentSearchEntries(catalog)

    assertEquals(
      listOf("Alert Dialog", "Open On Phone Dialog"),
      entries.map { it.label },
      "the palette offers a component once, not once per breakpoint",
    )
    assertEquals(
      listOf("alertdialog__ideal__default__192dp", "openonphonedialog__ideal__default__192dp"),
      entries.map { it.previewId },
      "each entry points at the component's first declared breakpoint",
    )
  }

  @Test
  fun `a lane whose only render is at a non-primary size keeps it`() {
    // The theme × size product is not always full. Here the component is drawn light at its first
    // declared breakpoint and dark ONLY at another one — so folding every non-primary size would
    // take the dark render off the grid, out of the drawer, and out of the palette, while the size
    // switcher (which holds the theme lane fixed) could never offer it from the light page. Each
    // lane resolves its own primary, so both renders survive.
    val sparse =
      listOf(
        ServePreview(
          id = "sparsedialog__ideal__default__192dp__light",
          label = "sparsedialog__ideal__default__192dp__light",
          componentId = "SparseDialog",
          state = "default",
          size = "192dp",
          theme = "light",
          section = "Containment",
          catalogOrder = 0,
        ),
        ServePreview(
          id = "sparsedialog__ideal__default__240dp__dark",
          label = "sparsedialog__ideal__default__240dp__dark",
          componentId = "SparseDialog",
          state = "default",
          size = "240dp",
          theme = "dark",
          section = "Containment",
          catalogOrder = 1,
        ),
      )

    val html = ServeWeb.landingPage("sparse", sparse, token = "t", basePath = "/sparse")
    assertTrue(
      html.contains("sparsedialog__ideal__default__192dp__light"),
      "the light lane's only render is on the grid",
    )
    assertTrue(
      html.contains("sparsedialog__ideal__default__240dp__dark"),
      "the dark lane's only render is not folded away with nothing to reach it from",
    )

    assertEquals(
      listOf(
        "sparsedialog__ideal__default__192dp__light",
        "sparsedialog__ideal__default__240dp__dark",
      ),
      ServeWeb.componentSearchEntries(sparse).map { it.previewId },
      "the palette keeps a representative for each lane",
    )
  }

  @Test
  fun `a full theme by size product still folds to one card`() {
    // The guard above must not weaken the ordinary case: both lanes resolve the SAME primary, so
    // every other breakpoint folds exactly as it did before.
    val full = sizes.flatMapIndexed { i, size ->
      listOf("light", "dark").map { theme ->
        ServePreview(
          id = "fulldialog__ideal__default__${size}__$theme",
          label = "fulldialog__ideal__default__${size}__$theme",
          componentId = "FullDialog",
          state = "default",
          size = size,
          theme = theme,
          section = "Containment",
          catalogOrder = i,
        )
      }
    }

    val html = ServeWeb.landingPage("full", full, token = "t", basePath = "/full")

    assertEquals(
      1,
      Regex("class=\"cp-card\"").findAll(html).count(),
      "one card, with its light/dark pair swapped in place",
    )
    for (size in sizes.drop(1)) {
      assertFalse(html.contains("fulldialog__ideal__default__${size}__"), "$size is folded out")
    }
  }

  @Test
  fun `a catalog that declares no sizes keeps a card per render`() {
    // The same fan-out as above with the metadata a pre-breakpoint export never wrote. Folding on
    // the id alone would make these renders unreachable — there would be no switcher to reach them
    // from — so each stays its own card, disambiguated by its size token.
    val untagged = openOnPhone.map { it.copy(size = null) }
    val html = ServeWeb.landingPage("legacy", untagged, token = "t", basePath = "/legacy")

    assertEquals(
      sizes.size,
      Regex("class=\"cp-card\"").findAll(html).count(),
      "without declared sizes every render stays a card of its own",
    )
  }
}
