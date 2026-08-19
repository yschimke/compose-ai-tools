package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the landing's Theme pill and `⋯` menu sit — and, with them, whether the page spends a row
 * on holding nothing.
 *
 * `.cp-catalog-tools` is the FILTER's row: a full-width sticky band, and the filter field is what
 * earns it. A sectioned catalog's filter belongs to the tree's sidebar, so that row was left
 * carrying two pills at its trailing edge and nothing else — 80px of empty page between the heading
 * and the first card, and for a catalog with a single theme just one lone `⋯` in it (issue #4224,
 * reported against a 190-preview Wear catalog). Browser mode was given the identity row for exactly
 * this reason in #4077; this pins the same rule for the sectioned case.
 *
 * The phone shape is unchanged and is not this test's subject: `<cp-catalog-toolbar>` builds the
 * row below 640px and moves the filter and the pills into it, which `catalogToolbar.test.ts`
 * covers.
 */
class ServeCatalogToolsRowTest {

  private fun sectioned(vararg sections: String) = sections.mapIndexed { i, section ->
    ServePreview(
      id = "preview-$i",
      label = "Preview $i",
      componentId = "Component/$i",
      section = section,
      group = "Buttons",
    )
  }

  private fun landing(previews: List<ServePreview>) =
    ServeWeb.landingPage(
      moduleLabel = "wear-m3-catalog",
      previews = previews,
      token = "t",
      hasSvgComparison = true,
    )

  @Test
  fun `a sectioned catalog carries its pills on the identity row, not in a band of their own`() {
    val html = landing(sectioned("Actions", "Actions", "Communication"))

    assertTrue(html.contains("class=\"cp-catalog-body\""), "the catalog renders its tree sidebar")
    assertFalse(
      html.contains("class=\"cp-catalog-tools\""),
      "and no toolbar row, which would hold only the pills",
    )
    val headRow = html.indexOf("class=\"cp-catalog-head-row\"")
    val body = html.indexOf("class=\"cp-catalog-body\"")
    val toggles = html.indexOf("class=\"cp-head-toggles\"")
    assertTrue(toggles in (headRow + 1) until body, "the pills ride on the identity row")
    // The filter is still the sidebar's, unmoved by any of this.
    assertTrue(html.contains("class=\"cp-catalog-menu\""))
    assertTrue(html.indexOf("class=\"cp-searchbar\"") > body)
  }

  @Test
  fun `a flat catalog keeps its pills in the toolbar, beside the filter field`() {
    // Two previews: too few to synthesize families from, so there is no tree and the filter field
    // is emitted into the toolbar — which is then a toolbar, not a band of pills.
    val html =
      landing(
        listOf(
          ServePreview(id = "solo-a", label = "Solo A"),
          ServePreview(id = "solo-b", label = "Solo B"),
        )
      )

    val tools = html.indexOf("class=\"cp-catalog-tools\"")
    assertTrue(tools > 0, "the toolbar row is emitted")
    val search = html.indexOf("class=\"cp-searchbar\"")
    val toggles = html.indexOf("class=\"cp-head-toggles\"")
    assertTrue(search > tools, "with the filter field in it")
    assertTrue(toggles > search, "and the pills at its trailing edge")
    val headRow = html.indexOf("class=\"cp-catalog-head-row\"")
    assertTrue(headRow in 0 until tools, "the identity row keeps none of them")
  }
}
