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
    // …and names them apart. Both rows would otherwise read "Sparse Dialog": the id-token
    // vocabulary has no entry for `192dp`, so the qualifier fell through to the bare label and the
    // palette offered two destinations spelled identically.
    assertEquals(
      listOf("Sparse Dialog · 192dp", "Sparse Dialog · 240dp"),
      ServeWeb.componentSearchEntries(sparse).map { it.label },
      "a retained lane entry is qualified by the catalog's own size name",
    )
  }

  @Test
  fun `the drawer names a sparse component once, in the theme being viewed`() {
    // Two survivors of ONE component (light at 192dp, dark at 240dp) carry different size tokens,
    // so `groupPreviews` cannot pair them into a single card. Without a dedupe after the lane pick
    // the drawer names the component twice, and one of the two links walks out of the theme on
    // screen — the opposite of what the drawer promises.
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
        ServePreview(
          id = "timetext__ideal__default__192dp__dark",
          label = "timetext__ideal__default__192dp__dark",
          componentId = "TimeText",
          state = "default",
          size = "192dp",
          theme = "dark",
          section = "Text",
          catalogOrder = 2,
        ),
      )

    val html =
      ServeWeb.viewerPage(
        sparse.last(),
        token = "t",
        basePath = "/sparse",
        siblings = sparse,
      )
    val drawer = html.substringAfter("<ul class=\"cp-nav-list\"").substringBefore("</ul>")
    val rows =
      Regex("class=\"cp-nav-name\">([^<]*)<").findAll(drawer).map { it.groupValues[1] }.toList()

    assertEquals(
      1,
      rows.count { it == "Sparse Dialog" },
      "the sparse component gets ONE drawer row, not one per surviving lane",
    )
    assertTrue(
      drawer.contains("/sparse/p/sparsedialog__ideal__default__240dp__dark"),
      "and it is the row in the dark theme the viewer is showing",
    )
    assertFalse(
      drawer.contains("sparsedialog__ideal__default__192dp__light"),
      "not the light render, which would walk the reader out of the current theme",
    )
  }

  @Test
  fun `lanes that disagree on breakpoint order still fold to one card`() {
    // A full theme × size product whose lanes enumerate their breakpoints in a DIFFERENT order —
    // what two per-theme preview functions produce. Resolving each lane's primary independently
    // would pick 192dp for light and 240dp for dark; the survivors' ids would then differ by size,
    // `baseKey` could not pair them, and a full product would publish two cards. The component-wide
    // primary wins in every lane that has it.
    val interleaved =
      listOf(
        ServePreview(
          id = "orderdialog__ideal__default__192dp__light",
          label = "orderdialog__ideal__default__192dp__light",
          componentId = "OrderDialog",
          state = "default",
          size = "192dp",
          theme = "light",
          section = "Containment",
          catalogOrder = 0,
        ),
        ServePreview(
          id = "orderdialog__ideal__default__240dp__dark",
          label = "orderdialog__ideal__default__240dp__dark",
          componentId = "OrderDialog",
          state = "default",
          size = "240dp",
          theme = "dark",
          section = "Containment",
          catalogOrder = 1,
        ),
        ServePreview(
          id = "orderdialog__ideal__default__240dp__light",
          label = "orderdialog__ideal__default__240dp__light",
          componentId = "OrderDialog",
          state = "default",
          size = "240dp",
          theme = "light",
          section = "Containment",
          catalogOrder = 2,
        ),
        ServePreview(
          id = "orderdialog__ideal__default__192dp__dark",
          label = "orderdialog__ideal__default__192dp__dark",
          componentId = "OrderDialog",
          state = "default",
          size = "192dp",
          theme = "dark",
          section = "Containment",
          catalogOrder = 3,
        ),
      )

    val html = ServeWeb.landingPage("order", interleaved, token = "t", basePath = "/order")

    assertEquals(
      1,
      Regex("class=\"cp-card\"").findAll(html).count(),
      "both lanes fold onto the component-wide primary, so it is one card",
    )
    for (id in
      listOf(
        "orderdialog__ideal__default__240dp__light",
        "orderdialog__ideal__default__240dp__dark",
      )) {
      assertFalse(html.contains(id), "$id is the non-primary breakpoint and folds")
    }
  }

  @Test
  fun `a breakpoint named light or dark is not mistaken for a theme`() {
    // `breakpoints[].size` is an arbitrary catalog-chosen string, so a size can be spelled `light`.
    // Inferring the fold's lane from the flattened id would read that SIZE as a baked theme, keep
    // both sizes as separate lane primaries, and let `groupPreviews` pair them into a light/dark
    // swap card — a Theme control that changes device size. The lane comes from declared metadata
    // only, so this catalog has one lane and folds to one card.
    val oddlyNamed =
      listOf("light", "dark").mapIndexed { i, size ->
        ServePreview(
          id = "moodboard__ideal__default__$size",
          label = "moodboard__ideal__default__$size",
          componentId = "Moodboard",
          state = "default",
          size = size,
          section = "Containment",
          catalogOrder = i,
        )
      }

    val html = ServeWeb.landingPage("odd", oddlyNamed, token = "t", basePath = "/odd")

    assertEquals(
      1,
      Regex("class=\"cp-card\"").findAll(html).count(),
      "one card — the second size is a breakpoint, not a theme to swap to",
    )
    assertTrue(
      html.contains("moodboard__ideal__default__light"),
      "drawn at its first declared size",
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
  fun `the viewer prints the component's caption, and the drawer offers it on hover`() {
    val captioned = catalog.map {
      if (it.componentId == "AlertDialog") it.copy(caption = "A decision the app needs.") else it
    }
    val html =
      ServeWeb.viewerPage(
        captioned.first { it.id == "openonphonedialog__ideal__default__192dp" },
        token = "t",
        basePath = "/wear",
        siblings = captioned,
      )

    // Its own caption is absent — OpenOnPhoneDialog authors none — so the line is simply not there
    // rather than an empty paragraph reserving space.
    assertFalse(html.contains("cp-preview-caption"), "a captionless component prints no caption")
    // …and the captioned sibling offers its caption as the drawer row's tooltip, where the id used
    // to be the only thing on offer.
    val drawer = html.substringAfter("<ul class=\"cp-nav-list\"").substringBefore("</ul>")
    assertTrue(
      drawer.contains("title=\"A decision the app needs.\""),
      "the drawer row's tooltip is the component's caption",
    )

    val onCaptioned =
      ServeWeb.viewerPage(
        captioned.first { it.id == "alertdialog__ideal__default__192dp" },
        token = "t",
        basePath = "/wear",
        siblings = captioned,
      )
    assertTrue(
      onCaptioned.contains("<p class=\"cp-preview-caption\">A decision the app needs.</p>"),
      "the viewer prints the caption under the component's name",
    )
  }

  @Test
  fun `a live-only preview carries its component's caption`() {
    // A wholly-deferred component never reaches `components[]`, so its caption exists only on the
    // deferred record — the component-map lookup alone would leave exactly the live-only cards
    // (which have no baked pixels to explain them either) unable to say what they are.
    val liveOnly =
      ServePreview(
        id = "livedialog__ideal__default__192dp",
        label = "livedialog__ideal__default__192dp",
        componentId = "LiveDialog",
        state = "default",
        size = "192dp",
        section = "Containment",
        catalogOrder = 0,
        caption = "Rendered on demand, and it says so.",
      )

    val html =
      ServeWeb.viewerPage(liveOnly, token = "t", basePath = "/wear", siblings = listOf(liveOnly))

    assertTrue(
      html.contains("<p class=\"cp-preview-caption\">Rendered on demand, and it says so.</p>"),
      "a live-only preview prints its caption like any other",
    )
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
