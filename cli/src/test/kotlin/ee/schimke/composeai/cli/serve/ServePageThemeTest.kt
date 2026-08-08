package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **Page theme** setting: the site chrome follows the selected preview theme (`?theme=dark`, a
 * Light/Dark chip, the viewer's Theme select) unless the visitor turns it off in the header's
 * Settings menu.
 *
 * Structural assertions on what the server emits — the pre-paint script, the `<html>` attributes it
 * reads, the Settings control, and the wiring that keeps the class in step when a chip is clicked.
 * The behaviour itself (pick Dark on a light machine, watch the page turn over; turn the setting
 * off, watch it revert) is captured in a real browser by the `serve-landing-catalog-palette`
 * `theme-sync` / `theme-sync-menu` / `theme-sync-off` states in `preview-harness`.
 */
class ServePageThemeTest {

  private val previews =
    listOf(
      ServePreview("button__ideal__default__light", "Button (light)", theme = "light"),
      ServePreview("button__ideal__default__dark", "Button (dark)", theme = "dark"),
    )

  private fun landing() =
    ServeWeb.landingPage("wear-m3", previews, token = "t", basePath = "/wear-m3")

  private fun viewer() = ServeWeb.viewerPage(previews.first(), token = "t", basePath = "/wear-m3")

  private val status =
    ServeWeb.StatusView(
      version = "0",
      public = true,
      nowMillis = 0L,
      overallOk = true,
      summary = emptyList(),
      config = emptyList(),
      catalogs = emptyList(),
      servers = emptyList(),
      failures = emptyList(),
    )

  @Test
  fun `the resolved scheme is pinned before first paint, not after the page loads`() {
    // Deferring this to page-theme.js would paint the page in the wrong mode and correct it a frame
    // later — a full-screen flash on a dark-to-light swap. It has to be inline, in the head, and
    // ahead of the body.
    val html = landing()
    val script = html.substringAfter("<script>try{var p=new URLSearchParams").substringBefore("\n")
    assertTrue(script.isNotBlank(), "no pre-paint page-theme script emitted")
    assertTrue(
      html.indexOf("cp-scheme-") < html.indexOf("<body>"),
      "the scheme must be pinned in the head, before the body paints",
    )
    // The URL outranks the remembered choice, exactly as the theme itself does.
    assertTrue(script.contains("p.get(\"theme\")||p.get(\"uiMode\")"), script)
    assertTrue(script.contains("localStorage.getItem(\"cp-theme:wear-m3\")"), script)
    // …and only an explicit light/dark says anything about the page's mode.
    assertTrue(script.contains("if(t===\"light\"||t===\"dark\")"), script)
  }

  @Test
  fun `the setting can turn the whole thing off`() {
    val script = landing().substringAfter("<script>try{var p=new URLSearchParams")
    assertTrue(
      script.contains("localStorage.getItem(\"cp-page-theme\")===\"system\"?\"\""),
      "the stored setting must be able to resolve to no pin at all",
    )
  }

  @Test
  fun `every page carries the Settings menu and the script that wires it`() {
    for ((name, html) in
      mapOf(
        "landing" to landing(),
        "viewer" to viewer(),
        "front door" to ServeWeb.homeIndexPage(emptyList(), token = "t", version = "0"),
        "status" to ServeWeb.statusPage(status, token = "t"),
      )) {
      assertTrue(html.contains("class=\"cp-settings\""), "$name has no Settings menu")
      assertTrue(
        html.contains("data-cp-page-theme value=\"match\"") ||
          html.contains("value=\"match\" data-cp-page-theme"),
        "$name offers no Page theme choice",
      )
      assertTrue(
        html.contains("""<script src="${ServeWebAssets.href("page-theme.js")}"></script>"""),
        "$name never loads page-theme.js",
      )
    }
  }

  @Test
  fun `only a page with a theme control publishes a theme key to resolve from`() {
    assertTrue(landing().contains("data-cp-theme-key=\"cp-theme:wear-m3\""))
    assertTrue(viewer().contains("data-cp-theme-key=\"cp-theme:wear-m3\""))
    // The front door has no theme control, so there is nothing to follow and no key to read.
    assertFalse(
      ServeWeb.homeIndexPage(emptyList(), token = "t", version = "0").contains("data-cp-theme-key")
    )
  }

  @Test
  fun `picking a theme turns the page over with the previews`() {
    assertTrue(
      landing().contains("if (window.cpPageTheme) window.cpPageTheme.follow(theme);"),
      "the grid's theme apply must hand the choice to page-theme.js",
    )
    assertTrue(
      viewer().contains("if (window.cpPageTheme) window.cpPageTheme.follow(el.value);"),
      "the viewer's Theme select must hand the choice to page-theme.js",
    )
    val compare = ServeWebAssets.load("format-compare.js")!!.bytes.decodeToString()
    assertTrue(
      compare.contains("window.cpPageTheme.follow(theme)"),
      "the comparison page's Theme control must too",
    )
  }

  @Test
  fun `Back and Forward repaint the chrome with the entry they restore`() {
    // Every pop path restores its theme by ASSIGNING the control's value, which fires no `change`
    // — so each one has to hand the restored choice over itself. Missing this left Back from Dark
    // to a Light entry re-rendering the preview light inside a page still pinned dark.
    //
    // It must hand over the ACTIVE choice, not the displayed one: a viewer opened with no theme
    // anywhere shows its baked default under `data-theme-active="0"`, and passing `.value` there
    // pins the page to a mode nobody picked. #3544 fixed that in `viewer.js` and left this
    // assertion on the old spelling, so it has been failing on `main` since.
    val viewerJs = ServeWebAssets.load("viewer.js")!!.bytes.decodeToString()
    assertTrue(
      viewerJs
        .substringAfter("function hydrateFromUrl")
        .contains("window.cpPageTheme.follow(activeThemeChoice())"),
      "the viewer's Back/Forward hydrate must repaint the chrome, from the active choice",
    )
    val compare = ServeWebAssets.load("format-compare.js")!!.bytes.decodeToString()
    assertTrue(
      compare.substringAfter("cpUrlState.onPop").contains("window.cpPageTheme.follow(theme)"),
      "so must the comparison page's",
    )
  }

  @Test
  fun `the stylesheet resolves both modes from color-scheme alone`() {
    // The setting is implemented as `color-scheme` on <html>, which can only re-resolve values
    // written as `light-dark()` pairs. A `prefers-color-scheme` block anywhere in the sheet would
    // be a rule the pin cannot move — the page would go dark while that rule stayed light.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertFalse(
      sheet.contains("@media (prefers-color-scheme"),
      "serve.css must express modes as light-dark() pairs, not a media query",
    )
    assertTrue(sheet.contains(":root.cp-scheme-light { color-scheme: light; }"))
    assertTrue(sheet.contains(":root.cp-scheme-dark { color-scheme: dark; }"))
    val playground = ServeWebAssets.load("playground.css")!!.bytes.decodeToString()
    assertFalse(
      playground.contains("@media (prefers-color-scheme"),
      "the playground's editor must follow the pinned scheme too",
    )
  }
}
