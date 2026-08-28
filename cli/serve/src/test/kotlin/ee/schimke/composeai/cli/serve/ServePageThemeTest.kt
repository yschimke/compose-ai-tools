package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParams
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `Wear themes infer explicit modes before falling back to dark`() {
    fun wearTheme(name: String, fqn: String) =
      PreviewInfo(
        id = name,
        functionName = name,
        className = "Catalog",
        params = PreviewParams(name = name, kind = "WEAR_THEME_CATALOG", wrapperClassName = fqn),
      )

    val themes =
      declaredThemesFromPreviews(
        listOf(
          wearTheme("Light", "com.example.LightWearTheme"),
          wearTheme("Coral", "com.example.CoralWearTheme"),
        )
      )

    assertTrue(themes.first { it.name == "Light" }.mode == "light")
    assertTrue(themes.first { it.name == "Coral" }.mode == "dark")
  }

  /**
   * A catalog that DECLARES a dark stage offers no Light chip, whatever its id reads like —
   * yschimke/wear-m3-catalog#99.
   *
   * The viewer used to decide this from the system id alone (`SystemDisplay.isDarkFirst`) while the
   * stage under the pixels went through the declaration-first `resolveDarkFirst`. `remote-m3` —
   * dark-only Remote Compose documents, `display.surface: "dark"`, an id with no `wear`/`watch`
   * token in it — landed between the two: dark stage, and a Light chip on top of it that no lane
   * behind the page could honour, because every document carries explicit dark-first colours.
   */
  @Test
  fun `a catalog declaring a dark surface offers no day-night choice`() {
    val darkOnly =
      ServeWeb.viewerPage(
        ServePreview("appcard__ideal__default__compact", "AppCard"),
        token = "t",
        basePath = "/remote-m3",
        sessionId = "remote-m3",
        declaredSurface = "dark",
      )

    assertTrue(darkOnly.contains("data-always-dark=\"1\""), darkOnly)
    assertFalse(
      darkOnly.contains("data-theme-choice=\"light\""),
      "a declared-dark catalog must not offer a Light chip: $darkOnly",
    )

    // …and a catalog that declares nothing, on an id that reads like neither a watch nor a dark
    // surface, keeps the pair it always had.
    val unstated =
      ServeWeb.viewerPage(
        ServePreview("button__ideal__default__light", "Button", theme = "light"),
        token = "t",
        basePath = "/compose-m3",
        sessionId = "compose-m3",
      )

    assertFalse(unstated.contains("data-always-dark=\"1\""), unstated)
    assertTrue(unstated.contains("data-theme-choice=\"light\""), unstated)
  }

  /**
   * A dark STAGE is not a dark-only catalog.
   *
   * `display.surface` is a stage colour in the spec schema, declared independently of what a
   * catalog bakes: a non-Wear catalog can perfectly well publish a light/dark pair and ask for a
   * dark ground under both. Suppressing its Light chip would leave `previewTheme` labelling the
   * light render on screen as Dark, with no way back to it. Only a declared-dark catalog with no
   * light render anywhere in the session is dark-only.
   */
  @Test
  fun `a declared dark stage keeps the pair when the catalog bakes a light render`() {
    val html =
      ServeWeb.viewerPage(
        previews[1],
        token = "t",
        basePath = "/compose-m3",
        sessionId = "compose-m3",
        siblings = previews,
        declaredSurface = "dark",
      )

    assertFalse(html.contains("data-always-dark=\"1\""), html)
    assertTrue(html.contains("data-theme-choice=\"light\""), html)
  }

  /**
   * A declaration can ADD an always-dark catalog; it cannot take one away from a Wear id.
   *
   * `SystemDisplay.normalizeOverrideParams` drops `uiMode` for a Wear/watch id unconditionally, on
   * every render and socket lane, and it is handed a system id with no declaration to read. So a
   * Wear catalog declaring `display.surface: "light"` must not sprout an enabled Light choice: it
   * would move the control and the URL while the server returned the same pixels.
   */
  @Test
  fun `a Wear id keeps its veto over a declared light surface`() {
    val wearLight =
      ServeWeb.viewerPage(
        ServePreview("button__ideal__default__light", "Button", theme = "light"),
        token = "t",
        basePath = "/confetti-wear",
        sessionId = "confetti-wear",
        declaredSurface = "light",
      )

    assertTrue(wearLight.contains("data-always-dark=\"1\""), wearLight)
    assertFalse(
      wearLight.contains("data-theme-choice=\"light\""),
      "the render lane drops uiMode for a Wear id, so the control must not offer Light: $wearLight",
    )
    assertTrue(
      ServeWeb.SystemDisplay.normalizeOverrideParams(
          "confetti-wear",
          mapOf("uiMode" to "light"),
        )
        .isEmpty(),
      "the premise of the assertion above",
    )
  }

  @Test
  fun `the resolved scheme is pinned before first paint, not after the page loads`() {
    // Deferring this to the shell bundle would paint the page in the wrong mode and correct it a
    // frame
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
    assertTrue(
      script.contains(
        "p.get(\"theme\")||(p.get(\"themeProvider\")?\"theme:\"+p.get(\"themeProvider\"):\"\")||p.get(\"uiMode\")"
      ),
      script,
    )
    assertTrue(script.contains("localStorage.getItem(\"cp-theme:wear-m3\")"), script)
    assertTrue(
      script.contains("match(/(?:^|__)(light|dark)(?:__|$)/)"),
      "a clean baked preview URL must recover its light/dark variant before first paint: $script",
    )
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
      val hasPreview = name == "landing" || name == "viewer"
      assertEquals(
        hasPreview,
        html.contains("data-cp-page-theme value=\"match\"") ||
          html.contains("value=\"match\" data-cp-page-theme"),
        "$name Page theme setting visibility",
      )
      // The setting ships in the page-shell bundle now (`cli/serve-web/src/chrome/pageTheme.ts`),
      // which every page emits; its behaviour is covered in `cli/serve-web/test/chrome.test.ts`.
      assertTrue(
        html.contains("""<script src="${ServeWebAssets.href("serve-chrome.js")}"></script>"""),
        "$name never loads the shell bundle",
      )
      assertTrue(
        html.contains("data-cp-keyboard-navigation"),
        "$name offers no power-user navigation setting",
      )
      assertTrue(
        html.contains(
          """<script src="${ServeWebAssets.href("keyboard-navigation.js")}"></script>"""
        ),
        "$name never loads keyboard-navigation.js",
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
    assertTrue(
      landing().contains("c.setAttribute(\"aria-label\", lbl);"),
      "a swapped card's accessible name must follow its visible theme variant",
    )
    // The comparison page's Theme control moved to `<cp-compare-wall>` with the port, and is tested
    // there as behaviour: `compareWallElement.test.ts` clicks the control against a stubbed
    // `cpPageTheme` and asserts the choice is handed over.
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
    val viewerJs = viewerSource()
    assertTrue(
      viewerJs
        .substringAfter("function hydrateFromUrl")
        .contains("window.cpPageTheme.follow(activeThemeChoice())"),
      "the viewer's Back/Forward hydrate must repaint the chrome, from the active choice",
    )
    // The comparison page's pop path moved to `<cp-compare-wall>`; the same element test drives its
    // `onPop` handler and asserts the restored theme is handed over too — which a grep could only
    // ever claim was present, not that it ran.
  }

  @Test
  fun `theme chips with a resolved mode are painted in their own theme`() {
    // The chips are a taster, not two labels: each pins its own `color-scheme`, which re-resolves
    // every `light-dark()` pair — including a served catalog's palette — in THAT chip's mode. The
    // same property the page-theme setting uses, applied one level down.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(
      sheet.contains("""[data-compare-theme="light"]) { color-scheme: light; }"""),
      "the Light chip must resolve the token layer in light",
    )
    assertTrue(
      sheet.contains("""[data-compare-theme="dark"]) { color-scheme: dark; }"""),
      "the Dark chip must resolve it in dark",
    )
    // Selection is a ring rather than a fill swap: the fill IS the swatch, so replacing it would
    // hide the theme at the moment it is picked. The ring must be INSET — the viewer's theme bar is
    // an `overflow` scroller padded on one edge, so an outward ring is clipped to three sides
    // there and stops reading as a selection marker at all.
    val pressed =
      sheet
        .substringAfter("""[data-compare-theme="dark"])[aria-pressed="true"] {""")
        .substringBefore("}")
    assertTrue(pressed.contains("box-shadow: inset 0 0 0 2px"), pressed)
    assertTrue(pressed.contains("--md-sys-color-surface-container-low"), pressed)
  }

  @Test
  fun `a named declared theme paints the page and card stage in its mode`() {
    val darkTheme = ServeTheme("Dark Medium Contrast", "com.example.DarkMediumContrastTheme")
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        previews,
        token = "t",
        declaredThemes = listOf(darkTheme),
        canRenderThemeFor = { true },
      )

    assertTrue(
      html.contains(
        "data-theme-choice=\"theme:${darkTheme.providerFqn}\" data-theme-mode=\"dark\""
      ),
      "the declared-theme chip must carry its resolved mode",
    )
    assertTrue(
      html.contains("\"theme:${darkTheme.providerFqn}\":\"dark\""),
      "the pre-paint script must resolve a shared declared-theme URL before first paint",
    )
    assertTrue(
      html.contains("if (selectedThemeMode) c.setAttribute(\"data-bg-theme\", selectedThemeMode)"),
      "a themed render's card stage must follow that theme's mode",
    )
  }

  @Test
  fun `a disabled chip is dimmed inside its own scheme, not against the page`() {
    // The viewer greys the Day/Night pair on a fixed lane. The shared disabled treatment fades the
    // label to 38% of `on-surface`; on a chip pinned to the opposite scheme, resolving that against
    // the PAGE would paint near-white text on a light page (or near-black on a dark one) — an
    // unavailable option that has vanished rather than one that reads as unavailable. So the chip
    // keeps its own surface under the dimmed label, and the scheme pin stays unconditional: every
    // colour on the chip resolves in one mode, in every state.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    val disabled =
      sheet.substringAfter("""[data-compare-theme="dark"]):disabled {""").substringBefore("}")
    assertTrue(disabled.contains("background: var(--md-sys-color-surface-container-low)"), disabled)
    assertTrue(disabled.contains("--md-sys-color-on-surface) 38%"), disabled)
    assertFalse(
      sheet.contains("""[data-theme-choice="dark"]:not(:disabled) { color-scheme"""),
      "the scheme pin must not be dropped on a state change",
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
