package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

  private fun jsonProps(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
  }

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
  fun `viewer menus are operated from the title row`() {
    val html =
      viewer(statePreviews(3) + ServePreview("checkbox__ideal__default__light", "Checkbox"))
    val primaryStart = html.indexOf("class=\"cp-preview-primary\"")
    for (id in listOf("cp-nav-toggle", "cp-theme-toggle", "cp-controls-toggle")) {
      val at = html.indexOf("id=\"$id\"")
      assertTrue(at in 0 until primaryStart, "$id belongs on the title row")
    }
    // …and nowhere else. The two drawer toggles used to live at either end of the viewer bar, which
    // is what made the page's disclosures feel like four unrelated buttons.
    assertFalse(html.contains("class=\"cp-viewer-bar\""), "the secondary viewer bar is removed")
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
  fun `variants live inside Components and the current component is first`() {
    val previews = statePreviews(5) + ServePreview("checkbox__ideal__default__light", "Checkbox")
    val html = viewer(previews, current = previews[2])
    val drawer = html.substringAfter("<aside class=\"cp-nav\"").substringBefore("</aside>")
    assertTrue(drawer.contains("class=\"cp-nav-current\""), drawer)
    assertTrue(drawer.contains("class=\"cp-tree cp-axes-tree\""), drawer)
    assertTrue(drawer.indexOf("Button") < drawer.indexOf("Checkbox"), "current component leads")
    assertFalse(html.contains("id=\"cp-axes-toggle\""), "variants have no standalone toggle")
  }

  @Test
  fun `a cross-product component can walk both axes from wherever it was entered`() {
    // state × props baked as a full matrix. The canonical variant set the landing tree draws holds
    // one axis at its default while walking the other, so from `pressed + RTL` it offers neither
    // `default + RTL` nor `pressed`. Both axes are folded out of the grid, so a subtree built from
    // that set alone would make the combination reachable from nowhere at all.
    val previews =
      listOf("default", "pressed", "disabled").flatMap { state ->
        listOf<String?>(null, "rtl").map { direction ->
          ServePreview(
            "button__ideal__${state}__light" + if (direction == null) "" else "__$direction",
            "Button",
            state = state,
            theme = "light",
            props = direction?.let { jsonProps("direction" to it) },
          )
        }
      }
    val pressedRtl = previews.first { it.state == "pressed" && it.props != null }
    val html = ServeWeb.viewerPage(pressedRtl, token, siblings = previews, basePath = "/c")
    val tree = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    // Its own state axis, RTL held fixed…
    assertTrue(
      tree.contains("/c/p/button__ideal__default__light__rtl?") &&
        tree.contains("/c/p/button__ideal__disabled__light__rtl?"),
      "the other states of THIS variant are one hop away: $tree",
    )
    // …and its own props axis, `pressed` held fixed.
    assertTrue(
      tree.contains("/c/p/button__ideal__pressed__light?"),
      "and so is the same state without the variant: $tree",
    )
    assertTrue(
      tree.contains("/c/p/button__ideal__pressed__light__rtl?token=t\" aria-current=\"page\""),
      "the render on screen is marked: $tree",
    )
    // With both axes in play a single-axis label is ambiguous, not terse: the row that resets the
    // state (`default + RTL`) and the row that resets the props (`pressed + default`) would BOTH
    // read "Default", and the current render would be labelled by whichever pass reached it first
    // — "Pressed", for something that is Pressed and RTL.
    assertTrue(tree.contains(">Default · RTL</a>"), "the state-reset row names both axes: $tree")
    assertTrue(tree.contains(">Pressed · Default</a>"), "…and so does the props-reset row: $tree")
    assertTrue(tree.contains(">Pressed · RTL</a>"), "…and the render on screen: $tree")
    assertFalse(tree.contains(">Default</a>"), "no two rows may share one name: $tree")
  }

  @Test
  fun `a single-axis component keeps the terse label`() {
    // The other side of the rule above: repeating "· Default" on every row of the components that
    // vary on one axis — nearly all of them — would be noise for a distinction they cannot make.
    val html = viewer(statePreviews(3))
    // Scoped to the child rows: the component row above them carries the preview's own display
    // name, which may legitimately hold a `·` of its own.
    val rows =
      html.substringAfter("class=\"cp-tree-children cp-tree-variants\"").substringBefore("</ul>")
    assertTrue(rows.contains(">State 1</a>") && rows.contains(">State 2</a>"), rows)
    assertFalse(rows.contains(" · "), "a one-axis component names one axis: $rows")
    // …and the default is NOT among them: it is the component row, which is where the reader
    // already is. Two rows for one render, one line apart, is what folding it up removed.
    assertFalse(rows.contains(">Default</a>"), "the default is the parent row, not a child: $rows")
  }

  @Test
  fun `a component with no second state or variant has no axes disclosure at all`() {
    val html = viewer(listOf(ServePreview("button__ideal__default__light", "Button")))
    assertFalse(html.contains("cp-axes-toggle"), "nothing to fold, so no control to fold it")
    assertFalse(html.contains("class=\"cp-axes\""), html)
  }

  @Test
  fun `the theme choices always live in a dropdown`() {
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
    assertTrue(few.contains("<details class=\"cp-theme-menu\">"), few)
    assertFalse(few.contains("class=\"cp-viewer-bar\""), few)
    // Six declared themes is the published compose-m3 shape, where the chips ellipsise into stubs
    // and the group scrolls within itself — worse than a toggle that spells the theme out.
    val many = viewer(previews, themes = (1..6).map { ServeTheme("Theme $it", "com.example.T$it") })
    assertTrue(many.contains("<div class=\"cp-theme-menu-panel\">"), many)
    assertFalse(many.contains("aria-label=\"Preview theme\" hidden"), many)
  }

  @Test
  fun `the theme toggle names the lane the preview is baked in, then follows the chips`() {
    val dark =
      viewer(listOf(ServePreview("button__ideal__default__dark", "Button", theme = "dark")))
    assertTrue(
      dark.contains("<span class=\"cp-toggle-value\" id=\"cp-theme-toggle-value\">Dark</span>"),
      dark,
    )
    val light =
      viewer(listOf(ServePreview("button__ideal__default__light", "Button", theme = "light")))
    assertTrue(
      light.contains("<span class=\"cp-toggle-value\" id=\"cp-theme-toggle-value\">Light</span>"),
      light,
    )
    // The label has to agree with the SELECT, which is the axis's state holder: a preview with no
    // uiMode and no light/dark id token opens on Day, so the toggle beside it must say Day rather
    // than contradicting the selected option until the observer catches up.
    val untagged = viewer(listOf(ServePreview("com.example.ButtonPreview", "Button")))
    assertTrue(
      untagged.contains("<option value=\"light\" selected>Light (Default)</option>"),
      untagged,
    )
    assertTrue(
      untagged.contains(
        "<span class=\"cp-toggle-value\" id=\"cp-theme-toggle-value\">Light</span>"
      ),
      "an untagged preview opens on Day; the toggle must not say Night: $untagged",
    )
    // The theme is picked without a page load, so the server-rendered label would go stale on the
    // first click; `<cp-viewer-drawers>` mirrors whichever chip viewer.js marks pressed. That it
    // does is asserted against the element in `cli/serve-web/test/viewerDrawers.test.ts`
    // ("mirrors the pressed theme chip into the toggle's value"), which a substring match on a
    // minified bundle could not do.
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
    // That closing the list says so out loud (`cp-nav-closed`), and that the fold keys are scoped
    // per catalog, are asserted against the real element and the rule module in
    // `cli/serve-web/test/viewerDrawers.test.ts` and `test/drawerState.test.ts`. What stays here
    // is the half Kotlin owns: that the server names the catalog those folds belong to.
    assertTrue(
      ServeWeb.viewerPage(
          ServePreview("button__ideal__default__light", "Button"),
          token,
          sessionId = "compose-m3",
          basePath = "/compose-m3",
        )
        .contains("data-fold-scope=\"compose-m3\""),
      "the viewer names the catalog its folds belong to",
    )
  }

  // `the phone's component sheet is transient, and the desktop default stays responsive` used to
  // live here as six substring matches on `viewer-drawers.js` — `if (isMobile()) return;`,
  // `setOpen("cp-nav-open", resolvedNavOpen());`, and so on. Every one of them proved a line
  // existed in a file, none proved a drawer behaved, and none can survive a minified bundle. The
  // rules are now a table in `cli/serve-web/test/drawerState.test.ts` (three viewport bands ×
  // stored preference × server default) and the wiring is exercised against the real element in
  // `test/viewerDrawers.test.ts`, including the resize this file could only assert as the presence
  // of an `addEventListener` call:
  //
  //   - "stores nothing about the drawers on a phone"
  //   - "is closed on a phone whatever a desktop visit stored"
  //   - "restores a stored choice over the server default"
  //   - "drops the nav when a wide window narrows to a phone"
}
