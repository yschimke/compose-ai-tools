package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ServeComponentBrowserTest {
  private val token = "test-token"

  @Test
  fun `catalog browser shows search and provenance without operational chrome`() {
    val html =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "compose-m3",
              title = "Material 3",
              subtitle = "androidx.compose.material3",
              previewCount = 84,
              trust = "branch:androidx/androidx@main",
              sourceRepo = "androidx/androidx",
              heroPreviewId = "button-filled",
              views = 120,
            )
          ),
        token = token,
        componentBrowser = true,
      )

    assertTrue(html.contains("id=\"cp-browser-catalog-search\""))
    assertTrue(html.contains("class=\"cp-browser-search-input\""))
    assertTrue(html.contains("aria-label=\"Interface mode\""))
    assertFalse(html.contains("Catalog / Dev mode"))
    assertTrue(html.contains(">Dev</button>"))
    assertTrue(html.contains("data-cp-interface-mode=\"catalog\" aria-pressed=\"true\""))
    // The choice is remembered in the cookie the server reads, not in the URL: nothing appends
    // `?chrome=` to the page's own links any more.
    assertTrue(html.contains("var key=\"cp_chrome\""))
    assertFalse(html.contains("document.querySelectorAll('a[href]')"))
    assertTrue(
      html.indexOf("</main>") < html.indexOf("querySelectorAll(\"[data-cp-interface-mode]\")")
    )
    assertTrue(html.contains("androidx/androidx"))
    // The card names itself through the title link's own TEXT rather than an `aria-label` on a
    // whole-tile anchor: the card is a div now (it has to be able to hold the compare chip), and
    // the link's text is both the visible title and its accessible name.
    assertTrue(
      html.contains("<a class=\"cp-sys-open\" href=\"/compose-m3/?token=$token\">Material 3</a>"),
      html,
    )
    assertTrue(html.contains("No catalogs match your search."))
    assertTrue(html.contains("h.hidden=!!g&&!Array.prototype.some.call(g.children"))
    assertTrue(html.contains("class=\"cp-component-browser\""))
    assertFalse(html.contains("84 preview(s)"))
    assertFalse(html.contains("<div class=\"cp-id\">compose-m3</div>"))
    assertFalse(html.contains("class=\"cp-settings"))
    assertFalse(html.contains("keyboard-navigation.js"))
    assertFalse(html.contains("class=\"cp-site-footer"))
  }

  @Test
  fun `single catalog keeps visual navigation and removes advanced destinations`() {
    val failure =
      ServePreview(
        id = "broken-card",
        label = "Broken card",
        renderFailure = CatalogRenderFailure(message = "boom"),
      )
    val previews =
      listOf(
        ServePreview(
          id = "button-filled__default__light",
          label = "Filled button",
          componentId = "Button/Filled",
          section = "Components",
          group = "Buttons",
          theme = "light",
        ),
        ServePreview(
          id = "button-filled__default__dark",
          label = "Filled button",
          componentId = "Button/Filled",
          section = "Components",
          group = "Buttons",
          theme = "dark",
        ),
        failure,
      )
    val html =
      ServeWeb.landingPage(
        moduleLabel = "compose-m3",
        displayTitle = "Material 3",
        previews = previews,
        token = token,
        hasHomeIndex = true,
        hasSvgComparison = true,
        hasRcComparison = true,
        hasReferenceComparison = true,
        hasParityView = true,
        playgroundHref = "/playground",
        componentBrowser = true,
      )

    assertTrue(html.contains("Catalog menu"))
    assertTrue(html.contains("Filter previews"))
    val catalogHeadStart = html.indexOf("class=\"cp-catalog-head-row\"")
    val headTogglesStart = html.indexOf("class=\"cp-head-toggles\"")
    val catalogBodyStart = html.indexOf("class=\"cp-catalog-body\"")
    assertTrue(headTogglesStart in (catalogHeadStart + 1) until catalogBodyStart)
    assertFalse(html.contains("class=\"cp-catalog-tools\""))
    assertTrue(html.contains("Button Filled"))
    assertFalse(html.contains("Broken card"))
    assertFalse(html.contains("compare SVG"))
    assertFalse(html.contains("compare RC players"))
    assertFalse(html.contains("design parity"))
    assertFalse(html.contains("try in playground"))
    assertFalse(html.contains("download all (.zip)"))
    assertFalse(html.contains("class=\"cp-catalog-id\""))
  }

  @Test
  fun `component page is visual source and authored controls focused`() {
    val current =
      ServePreview(
        id = "button-filled__pressed__light",
        label = "Filled button",
        componentId = "Button/Filled",
        state = "pressed",
        theme = "light",
        section = "Components",
        group = "Buttons",
        props = JsonObject(mapOf("size" to JsonPrimitive("large"))),
        motion = listOf(ServeMotion("button-press", "interaction", "Press")),
      )
    val siblings =
      listOf(
        ServePreview("button-outlined", "Outlined button", componentId = "Button/Outlined"),
        current,
        ServePreview("card-filled", "Filled card", componentId = "Card/Filled"),
      )
    val html =
      ServeWeb.viewerPage(
        preview = current,
        token = token,
        catalogTitle = "Material 3",
        catalogName = "Material 3",
        basePath = "/compose-m3",
        siblings = siblings,
        canRenderOverrides = true,
        hasLiveStream = true,
        wasmSrc = "/wasm/compose-m3/",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java"),
        hasA11yOverlay = true,
        hasDesignAnnotations = true,
        hasSvgExport = true,
        hasScrollExport = true,
        usageHref = "/compose-m3/usage/button-filled",
        componentBrowser = true,
      )

    assertTrue(html.contains("class=\"cp-browser-breadcrumb\""))
    assertTrue(html.contains("Components"))
    assertTrue(html.contains("Buttons"))
    assertTrue(html.contains("Pressed · size large"))
    assertTrue(html.contains("id=\"cp-browser-preview-tab\""))
    assertTrue(html.contains("id=\"cp-source-chip\""))
    assertTrue(html.contains("id=\"cp-motion-chip\""))
    assertTrue(html.contains("id=\"cp-wasm\""))
    assertTrue(html.contains("id=\"cp-wasm-toggle\""))
    assertTrue(html.contains("data-wasm-src=\"/wasm/compose-m3/\""))
    // The Wasm lane is available but NOT entered on load: Catalog mode opens on the same baked
    // snapshot Dev mode does. Auto-enabling it bypassed viewer.js's "wait for the snapshot to
    // land" gate, which cancelled the in-flight render and left the iframe sized to a src-less
    // <img>'s placeholder box — a blank stage on every component page (#4091).
    assertTrue(html.contains("data-mode=\"snapshot\""))
    assertFalse(html.contains("getElementById(\"cp-wasm-toggle\")"))
    assertTrue(html.contains("id=\"cp-localeTag\""))
    assertTrue(html.contains("id=\"cp-fontScale\""))
    assertTrue(html.contains("class=\"cp-browser-siblings\""))
    assertTrue(html.contains("Copy PNG"))
    assertTrue(html.contains("Copy SVG"))

    assertFalse(html.contains("id=\"cp-live-toggle\""))
    // The switcher survives here, because this preview carries a `.rc` document — which player drew
    // a document is the subject of a Remote Compose catalog, not operational chrome. See `catalog
    // mode keeps the whole remote compose facet`.
    assertTrue(html.contains("id=\"cp-lane-select\""))
    assertTrue(html.contains("value=\"rc:js\""))
    assertTrue(html.contains("value=\"rc:java\""))
    assertFalse(html.contains("id=\"cp-svg-toggle\""))
    assertFalse(html.contains("id=\"cp-explode-toggle\""))
    assertFalse(html.contains("Accessibility</label>"))
    assertFalse(html.contains("Typography</label>"))
    assertFalse(html.contains("Theme attributes</label>"))
    assertFalse(html.contains("data-cp-group=\"size\""))
    assertFalse(html.contains("class=\"cp-preview-id\""))
    assertFalse(html.contains("class=\"cp-note\""))
  }

  /**
   * Catalog mode keeps the **catalog** report, though it drops every developer affordance beside
   * it.
   *
   * This mode is the presentation a design reviewer is handed, and a reviewer noticing that a
   * component draws the wrong thing is precisely who the report exists for. Stripping it with the
   * source link and the playground left the floating launcher with no `#cp-report` to unhide its
   * catalog half against, so the only route out of a wrong preview was the preview SERVER's tracker
   * — which does not own the component (issue #4704).
   */
  @Test
  fun `catalog mode keeps the catalog report beside the preview`() {
    val report =
      ServeWeb.ReportIssue(
        action = "https://github.com/yschimke/wear-m3-catalog/issues/new",
        body = "### Which preview",
        bodyTemplate = "### Which preview",
        repo = "yschimke/wear-m3-catalog",
      )
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("button-filled", "Filled button", componentId = "Button/Filled"),
        token = token,
        catalogTitle = "Wear Material 3",
        componentBrowser = true,
        sourceHref = "https://github.com/yschimke/wear-m3-catalog/blob/main/Button.kt",
        playgroundHref = "/playground",
        reportIssue = report,
      )

    assertTrue(
      html.contains("id=\"cp-report\" data-cp-repo=\"yschimke/wear-m3-catalog\""),
      "the launcher's catalog half has something to point at: $html",
    )
    // The developer affordances that share its row still go.
    assertFalse(html.contains("cp-source-link"), html)
    assertFalse(html.contains("playground</a>"), html)
  }

  @Test
  fun `component page keeps the snapshot fallback when wasm is unavailable`() {
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("java-card", "Java card", componentId = "Card/Java"),
        token = token,
        catalogTitle = "Java components",
        componentBrowser = true,
      )

    assertTrue(html.contains("data-mode=\"snapshot\""))
    assertTrue(html.contains("id=\"cp-img\""))
    assertFalse(html.contains("id=\"cp-wasm\""))
    assertFalse(html.contains("id=\"cp-wasm-toggle\""))
    assertFalse(html.contains("id=\"cp-lane-select\""))
  }

  /**
   * A Remote Compose preview keeps the **whole** player facet in Catalog mode — embedded included.
   *
   * It used to come off with the rest of the dev surface, which made a shared `?rcPlayer=…` link
   * inert here: no canvas, no chips, no lane select, and no control owning the param, so
   * `url-state.js` cleared it from the address bar and the link quietly became an ordinary baked
   * snapshot. Which player drew a document is the *subject* of a Remote Compose catalog rather than
   * an operational detail, so the reader of one is exactly who wants to switch between them.
   *
   * The landing lane matching Dev's is the load-bearing half: both modes open on the embedded
   * player, so the two cannot disagree about what the default rendering of a document is.
   */
  @Test
  fun `catalog mode keeps the whole remote compose facet`() {
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("appcard-time", "App card time", componentId = "Card/AppCard"),
        token = token,
        catalogTitle = "Remote Compose Material 3",
        componentBrowser = true,
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android", "cmp-jvm", "cmp-wasm"),
      )

    // The canvas lane is present, so the `.rc` document has something to paint into.
    assertTrue(html.contains("data-has-rc-doc=\"1\""))
    assertTrue(html.contains("id=\"cp-rc-canvas\""))
    // …and the switcher offers every player the host reported, browser and server-side alike.
    assertTrue(html.contains("id=\"cp-lane-select\""))
    for (wire in listOf("js", "cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      assertTrue(html.contains("value=\"rc:$wire\""), "$wire is offered in Catalog mode")
    }
    // The lane it opens on is the embedded player, exactly as in Dev. Asserted on both attributes
    // because it is the visible consequence of the change and the thing a reviewer should weigh: an
    // RC preview in Catalog mode now lands on a rendered player rather than the baked PNG.
    assertTrue(html.contains("data-rc-default=\"cmp-android\""))
    assertTrue(html.contains("data-default=\"rc:cmp-android\""))
  }

  /**
   * Catalog mode drops the renderer **chip** with the rest of the Live control, and that chip is
   * what made the combo a *command* menu: "Switch renderer…" at rest is only honest while something
   * beside it names the renderer in use. Without a chip the combo is the sole indicator, so the
   * server marks it as a state field and `viewer.js` keeps the selection in it — otherwise picking
   * Java left nothing on the page, or in the accessibility tree, saying Java was drawing.
   */
  @Test
  fun `catalog mode marks the renderer combo as a state field, having no chip`() {
    val catalog =
      ServeWeb.viewerPage(
        preview = ServePreview("appcard-time", "App card time", componentId = "Card/AppCard"),
        token = token,
        catalogTitle = "Remote Compose Material 3",
        componentBrowser = true,
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android"),
      )
    assertFalse(catalog.contains("id=\"cp-live-toggle\""), "no chip in Catalog mode")
    assertTrue(catalog.contains("data-lane-state=\"1\""), "so the combo holds the state instead")

    // Dev keeps both, and the combo stays the command menu it was — the chip is the state there.
    val dev =
      ServeWeb.viewerPage(
        preview = ServePreview("appcard-time", "App card time", componentId = "Card/AppCard"),
        token = token,
        catalogTitle = "Remote Compose Material 3",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android"),
      )
    assertTrue(dev.contains("id=\"cp-live-toggle\""))
    assertFalse(dev.contains("data-lane-state"))
  }

  /**
   * Dev mode is untouched: every player stays on offer, the unavailable ones as disabled options.
   */
  @Test
  fun `dev mode still offers the server-side remote compose players`() {
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("appcard-time", "App card time", componentId = "Card/AppCard"),
        token = token,
        catalogTitle = "Remote Compose Material 3",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android"),
      )

    assertTrue(html.contains("value=\"rc:java\""))
    assertTrue(html.contains("value=\"rc:cmp-android\""))
    assertTrue(html.contains("value=\"rc:cmp-jvm\""), "an unavailable player is still listed")
    assertTrue(html.contains("data-rc-default=\"cmp-android\""))
  }

  @Test
  fun `dev mode exposes the same sticky global switch with dev selected`() {
    val html =
      ServeWeb.landingPage(
        moduleLabel = "app",
        previews = listOf(ServePreview("button", "Button")),
        token = token,
      )

    assertTrue(html.contains("aria-label=\"Interface mode\""))
    assertTrue(html.contains("data-cp-interface-mode=\"dev\" aria-pressed=\"true\""))
    // Clicking a mode writes the cookie and drops any `?chrome=` permalink the URL pinned, so the
    // switch wins over the link that was followed to get here.
    assertTrue(html.contains("document.cookie=key+\"=\"+mode"))
    assertTrue(html.contains("u.searchParams.delete(\"chrome\")"))
    assertFalse(html.contains("class=\"cp-component-browser\""))
  }

  @Test
  fun `dev home exposes catalog search too`() {
    val html =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "compose-m3",
              title = "Material 3",
              subtitle = null,
              previewCount = 1,
              trust = null,
              heroPreviewId = null,
            )
          ),
        token = token,
      )

    assertTrue(html.contains("id=\"cp-browser-catalog-search\""))
    assertTrue(html.contains("data-browser-search=\"material 3 compose-m3"))
    assertTrue(html.contains("id=\"cp-browser-catalog-empty\""))
  }

  @Test
  fun `catalog mode paints no render-server badge`() {
    // The badge is a count ON the header's Status link, and Catalog mode carries neither the nav
    // that holds it nor a `/status` page to read the detail from. It used to be created anyway and
    // appended to `<header>`, where the header's two-column grid pushed it into an implicit second
    // row and stretched it across the `1fr` track — the count painted as a full-width bar under the
    // brand. Both halves are asserted: no slot in Catalog mode, and no code path that manufactures
    // one when the slot is missing.
    val catalog =
      ServeWeb.viewerPage(
        preview = ServePreview("button-filled", "Filled button", componentId = "Button/Filled"),
        token = token,
        catalogTitle = "Material 3",
        presenceUrl = "/api/presence/compose-m3",
        componentBrowser = true,
      )

    // The heartbeat stays — it is what warms the daemon for the catalog being read. Only the badge
    // goes. Asserted on the slot's markup rather than the bare class name, which the (now inert)
    // poller still mentions in its `getElementById` lookup.
    assertTrue(catalog.contains("/api/presence/compose-m3"))
    assertFalse(catalog.contains("id=\"cp-daemon-status\""))
    assertFalse(catalog.contains("document.querySelector(\"header\")"))
    assertFalse(catalog.contains("daemonBadge.id ="))

    // Dev mode is unchanged: the server renders the slot inside the Status link, hidden until the
    // first poll answers, so filling it never moves the brand or the nav.
    val dev =
      ServeWeb.viewerPage(
        preview = ServePreview("button-filled", "Filled button", componentId = "Button/Filled"),
        token = token,
        catalogTitle = "Material 3",
        presenceUrl = "/api/presence/compose-m3",
      )

    assertTrue(dev.contains("id=\"cp-status-link\""))
    assertTrue(dev.contains("class=\"cp-daemon-status\" id=\"cp-daemon-status\""))
  }

  @Test
  fun `sticky browser controls reserve the global header height`() {
    val css =
      checkNotNull(javaClass.getResource("/ee/schimke/composeai/cli/serve/assets/serve.css"))
        .readText()

    assertTrue(css.contains(".cp-catalog-tools { position: sticky; top: var(--site-header-height)"))
    assertTrue(css.contains(".cp-preview-head { position: sticky; top: var(--site-header-height)"))
    assertTrue(
      css.contains(".cp-browser-home-tools { position: sticky; top: var(--site-header-height)")
    )
  }
}
