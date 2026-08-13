package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewer's four **disclosures** — the component list, the state/variant axes, the theme chips
 * and the overrides drawer — and the one place they are operated from.
 *
 * The viewer used to spend most of the fold saying things it had already said: a component's whole
 * state axis as three wrapped rows of chips, eight ellipsised theme chips, and a 240px component
 * column nailed open on every desktop, all above a render that is what the page is *for*. Each of
 * those is now foldable, and the controls that fold them sit together on the title row rather than
 * scattered across the toolbar — so there is one answer to "what can I put away".
 *
 * The rule the tests below encode: a fold may never cost information. Every closed toggle names the
 * value its row was carrying (`State · M wide`, `Theme · Night`), which is why folding by default
 * is safe on the catalogs wide enough to need it.
 */
class ServeViewerDisclosuresTest {

  private val token = "t"

  /** A component with `n` baked states, in one theme lane. */
  private fun statePreviews(n: Int): List<ServePreview> =
    (0 until n).map { i ->
      val state = if (i == 0) "default" else "state-$i"
      ServePreview("button__ideal__${state}__light", "Button · $state", state = state)
    }

  private fun viewer(
    previews: List<ServePreview>,
    current: ServePreview = previews.first(),
    themes: List<ServeTheme> = emptyList(),
  ) = ServeWeb.viewerPage(current, token, siblings = previews, declaredThemes = themes)

  private fun head(html: String) =
    html.substringAfter("<div class=\"cp-preview-head\">").substringBefore("</div>")

  @Test
  fun `all four disclosures are operated from the title row`() {
    val html =
      viewer(statePreviews(3) + ServePreview("checkbox__ideal__default__light", "Checkbox"))
    val titleRow = head(html)
    for (id in listOf("cp-nav-toggle", "cp-axes-toggle", "cp-theme-toggle", "cp-controls-toggle")) {
      assertTrue(titleRow.contains("id=\"$id\""), "$id belongs on the title row: $titleRow")
    }
    // …and nowhere else. The two drawer toggles used to live at either end of the viewer bar, which
    // is what made the page's disclosures feel like four unrelated buttons.
    val bar = html.substringAfter("<div class=\"cp-viewer-bar\">").substringBefore("</div>")
    assertFalse(bar.contains("cp-drawer-toggle"), "the viewer bar keeps no disclosure of its own")
    for (id in listOf("cp-nav-toggle", "cp-controls-toggle")) {
      assertTrue(html.split("id=\"$id\"").size - 1 == 1, "$id is emitted once, not once per home")
    }
  }

  @Test
  fun `each disclosure points at the surface it folds`() {
    val html =
      viewer(statePreviews(3) + ServePreview("checkbox__ideal__default__light", "Checkbox"))
    for ((toggle, target) in
      listOf(
        "cp-nav-toggle" to "cp-nav",
        "cp-axes-toggle" to "cp-axes",
        "cp-theme-toggle" to "cp-theme-bar",
        "cp-controls-toggle" to "cp-controls",
      )) {
      assertTrue(
        html.contains(Regex("id=\"$toggle\"[^>]*aria-controls=\"$target\"")),
        "$toggle must name $target, or it is a button that looks like a disclosure",
      )
      assertTrue(html.contains("id=\"$target\""), "$target is in the DOM to be folded")
    }
  }

  @Test
  fun `a narrow state axis stays inline and a wide one arrives folded`() {
    val narrow = viewer(statePreviews(3))
    assertTrue(narrow.contains("<div class=\"cp-axes\" id=\"cp-axes\">"), "three states show")
    assertTrue(
      narrow.contains(Regex("id=\"cp-axes-toggle\" aria-expanded=\"true\"")),
      "…and the toggle says so",
    )
    // Past the inline threshold the rows arrive folded — server-side, so there is no expanded flash
    // before the drawer script runs.
    val wide = viewer(statePreviews(20))
    assertTrue(wide.contains("<div class=\"cp-axes\" id=\"cp-axes\" hidden>"), "twenty states fold")
    assertTrue(
      wide.contains(Regex("id=\"cp-axes-toggle\" aria-expanded=\"false\"")),
      "…and the toggle says so",
    )
    // Folded, but not silent: the chips are gone, the fact they carried is not.
    assertTrue(
      wide.contains(
        "<span class=\"cp-toggle-label\">State</span><span class=\"cp-toggle-value\">Default</span>"
      ),
      "a closed axis names the state being viewed",
    )
    val onState = viewer(statePreviews(20), current = statePreviews(20)[7])
    assertTrue(
      onState.contains("<span class=\"cp-toggle-value\">State 7</span>"),
      "…whichever state that is",
    )
  }

  @Test
  fun `a component with no second state or variant has no axes disclosure at all`() {
    val html = viewer(listOf(ServePreview("button__ideal__default__light", "Button")))
    assertFalse(html.contains("cp-axes-toggle"), "nothing to fold, so no control to fold it")
    assertFalse(html.contains("class=\"cp-axes\""), html)
  }

  @Test
  fun `the theme bar folds once a catalog declares more themes than the row can show`() {
    val previews = listOf(ServePreview("button__ideal__default__light", "Button"))
    // Day + Night + two declared: still a readable row.
    val few =
      viewer(
        previews,
        themes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog"),
          ),
      )
    assertFalse(
      few.contains("id=\"cp-theme-bar\" role=\"group\" aria-label=\"Preview theme\" hidden"),
      "a four-chip bar shows",
    )
    // Six declared themes is the published compose-m3 shape, where the chips ellipsise into stubs
    // and the group scrolls within itself — worse than a toggle that spells the theme out.
    val many = viewer(previews, themes = (1..6).map { ServeTheme("Theme $it", "com.example.T$it") })
    assertTrue(
      many.contains("id=\"cp-theme-bar\" role=\"group\" aria-label=\"Preview theme\" hidden"),
      "an eight-chip bar folds",
    )
    assertTrue(
      many.contains(Regex("id=\"cp-theme-toggle\" aria-expanded=\"false\"")),
      "…and the toggle says so",
    )
  }

  @Test
  fun `the theme toggle names the lane the preview is baked in, then follows the chips`() {
    val dark =
      viewer(listOf(ServePreview("button__ideal__default__dark", "Button", theme = "dark")))
    assertTrue(
      dark.contains("<span class=\"cp-toggle-value\" id=\"cp-theme-toggle-value\">Night</span>"),
      dark,
    )
    val light =
      viewer(listOf(ServePreview("button__ideal__default__light", "Button", theme = "light")))
    assertTrue(
      light.contains("<span class=\"cp-toggle-value\" id=\"cp-theme-toggle-value\">Day</span>"),
      light,
    )
    // The theme is picked without a page load, so the server-rendered label would go stale on the
    // first click; the drawer script mirrors whichever chip viewer.js marks pressed.
    val script = ServeWebAssets.load("viewer-drawers.js")!!.bytes.decodeToString()
    assertTrue(
      script.contains("""themeBar.querySelector('.cp-theme-btn[aria-pressed="true"]')"""),
      script,
    )
    assertTrue(script.contains("attributeFilter: [\"aria-pressed\"]"), script)
  }

  @Test
  fun `the component list is collapsible on a desktop too, and every fold is remembered`() {
    val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    // Three states, not two: no class (open on a desktop, closed below), `cp-nav-open` (open
    // everywhere), `cp-nav-closed` (closed everywhere). Without the third the title bar's toggle
    // would be inert at exactly the width where a 240px column costs the most.
    assertTrue(
      css.contains(".cp-viewer:not(.cp-nav-open):not(.cp-nav-closed) .cp-nav { display: flex; }"),
      css.substringAfter("@media (min-width: 1100px)").take(400),
    )
    val script = ServeWebAssets.load("viewer-drawers.js")!!.bytes.decodeToString()
    assertTrue(
      script.contains("""viewer.classList.toggle("cp-nav-closed", !open);"""),
      "closing the list must say so out loud, or the desktop default keeps winning",
    )
    // Remembered per visitor, the same way the override groups are (`cp-grp.<id>`): putting a wide
    // axis away is a statement about the catalog, not about one preview.
    assertTrue(script.contains("""return "cp-fold." + id;"""), script)
    for (id in listOf("cp-nav-toggle", "cp-controls-toggle", "cp-axes-toggle", "cp-theme-toggle")) {
      assertTrue(script.contains("\"$id\""), "$id participates in the remembered folds: $script")
    }
  }
}
