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
    assertTrue(html.contains("aria-label=\"Catalog / Dev mode\""))
    assertTrue(html.contains("data-cp-interface-mode=\"catalog\" aria-pressed=\"true\""))
    assertTrue(html.contains("localStorage.getItem(\"cp-interface-mode\")"))
    assertTrue(html.indexOf("</main>") < html.indexOf("document.querySelectorAll('a[href]')"))
    assertTrue(html.contains("androidx/androidx"))
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
    assertTrue(
      html.contains("if(w&amp;&amp;!requested)wasm()") || html.contains("if(w&&!requested)wasm()")
    )
    assertTrue(html.contains("id=\"cp-localeTag\""))
    assertTrue(html.contains("id=\"cp-fontScale\""))
    assertTrue(html.contains("class=\"cp-browser-siblings\""))
    assertTrue(html.contains("Copy PNG"))
    assertTrue(html.contains("Copy SVG"))

    assertFalse(html.contains("id=\"cp-live-toggle\""))
    assertFalse(html.contains("id=\"cp-lane-select\""))
    assertFalse(html.contains("id=\"cp-svg-toggle\""))
    assertFalse(html.contains("id=\"cp-explode-toggle\""))
    assertFalse(html.contains("Accessibility</label>"))
    assertFalse(html.contains("Typography</label>"))
    assertFalse(html.contains("Theme attributes</label>"))
    assertFalse(html.contains("data-cp-group=\"size\""))
    assertFalse(html.contains("class=\"cp-preview-id\""))
    assertFalse(html.contains("class=\"cp-note\""))
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

  @Test
  fun `dev mode exposes the same sticky global switch with dev selected`() {
    val html =
      ServeWeb.landingPage(
        moduleLabel = "app",
        previews = listOf(ServePreview("button", "Button")),
        token = token,
      )

    assertTrue(html.contains("aria-label=\"Catalog / Dev mode\""))
    assertTrue(html.contains("data-cp-interface-mode=\"dev\" aria-pressed=\"true\""))
    assertTrue(html.contains("q.set(\"chrome\",s)"))
    assertFalse(html.contains("class=\"cp-component-browser\""))
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
