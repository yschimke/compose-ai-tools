package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Pins the **component-state toggle** wiring in [ServeWeb]: baked non-default states
 * (`unchecked`/`pressed`/…) are folded out of the landing grid so a component shows ONE card, and
 * the viewer grows a `<nav class="cp-states">` switcher of plain links to the component's other
 * states *in the same theme*. Stateless previews (a plain uploaded bundle) are untouched.
 */
class ServeWebTest {

  private fun jsonProps(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
  }

  /**
   * A themed, state-bearing preview (id carries the theme token so the grid's theme swap still
   * pairs it).
   */
  private fun preview(slug: String, state: String, theme: String) =
    ServePreview(
      id = "${slug}__ideal__${state}__${theme}",
      label = slug,
      state = state,
      theme = theme,
    )

  // A checkbox with a default + an unchecked render, each in light and dark.
  private val checkbox =
    listOf(
      preview("checkbox", "default", "light"),
      preview("checkbox", "default", "dark"),
      preview("checkbox", "unchecked", "light"),
      preview("checkbox", "unchecked", "dark"),
    )

  @Test
  fun `catalog component ids become readable labels without changing preview routes`() {
    val previews =
      listOf(
          "appcard" to "AppCard",
          "buttongroup" to "ButtonGroup",
          "edgebutton" to "EdgeButton",
          "transforminglazycolumn" to "TransformingLazyColumn",
          "podcastdetails" to "PodcastDetails",
          "listitem" to "ListItem",
          "urlbutton" to "URLButton",
        )
        .map { (slug, componentId) ->
          ServePreview(
            id = "${slug}__ideal__default__light",
            label = "${slug}__ideal__default__light",
            componentId = componentId,
          )
        }

    val html = ServeWeb.landingPage("catalog", previews, token = "t", basePath = "/catalog")

    for (label in
      listOf(
        "App Card",
        "Button Group",
        "Edge Button",
        "Transforming Lazy Column",
        "Podcast Details",
        "List Item",
        "URL Button",
      )) {
      assertTrue(html.contains(">$label</"), "$label is shown with readable word boundaries")
    }
    assertTrue(
      html.contains("/catalog/p/appcard__ideal__default__light"),
      "the human label does not alter the stable preview route",
    )
  }

  @Test
  fun `the grid folds a non-default state into the default card`() {
    val html = ServeWeb.landingPage("compose-m3", checkbox, token = "t", basePath = "/compose-m3")

    // Exactly one card — the default (a light/dark swap card), no separate 'unchecked' card.
    assertEquals(1, Regex("class=\"cp-card\"").findAll(html).count(), "one card per component")
    assertTrue(html.contains("checkbox__ideal__default__light"), "default render is the card")
    assertFalse(html.contains("unchecked"), "the non-default state is folded out of the grid")
  }

  @Test
  fun `the viewer renders a same-theme state switcher with the current state active`() {
    val current = checkbox[0] // default, light
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/compose-m3", siblings = checkbox)

    assertTrue(html.contains("class=\"cp-states\""), "state switcher rendered")
    // Isolate the switcher nav — other page chrome (the component nav drawer) also links siblings,
    // so the theme-scoping assertion must look only inside `<nav class="cp-states">…</nav>`.
    val nav = html.substringAfter("class=\"cp-states\"").substringBefore("</nav>")
    // Links to the SAME-THEME (light) unchecked sibling…
    assertTrue(
      nav.contains("/compose-m3/p/checkbox__ideal__unchecked__light"),
      "switcher links the same-theme sibling state",
    )
    // …and never to the dark render (that would jump the visitor's theme).
    assertFalse(
      nav.contains("/compose-m3/p/checkbox__ideal__unchecked__dark"),
      "switcher stays within the current theme",
    )
    // The current (default) state is marked active with a human label.
    assertTrue(
      nav.contains("aria-current=\"page\">Default</a>"),
      "the current state is marked active",
    )
  }

  @Test
  fun `a single-state component renders no switcher`() {
    val button = listOf(preview("button", "default", "light"), preview("button", "default", "dark"))
    val html =
      ServeWeb.viewerPage(button[0], token = "t", basePath = "/compose-m3", siblings = button)
    // The `.cp-states` CSS rule ships on every page; assert the absence of the nav *element*.
    assertFalse(html.contains("class=\"cp-states\""), "no switcher for a one-state component")
  }

  @Test
  fun `the state switcher stays within the current variant axis, not just the slug`() {
    // Button/Filled varies on BOTH a state axis (default/pressed) and a content-props axis
    // (label-only default vs a content=icon+label render, which keeps state=default). All share the
    // `button-filled` slug, so keying on slug alone would cross-link the two axes.
    val labelDefault =
      ServePreview(
        "button-filled__ideal__default__light",
        "Filled",
        state = "default",
        theme = "light",
      )
    val labelPressed =
      ServePreview(
        "button-filled__ideal__pressed__light",
        "Filled",
        state = "pressed",
        theme = "light",
      )
    val iconLabel =
      ServePreview(
        "button-filled__ideal__default__light__content-icon-label",
        "Filled · icon+label",
        state = "default",
        theme = "light",
      )
    val all = listOf(labelDefault, labelPressed, iconLabel)

    // The label-only default page toggles between its OWN states (default/pressed) and never links
    // the icon+label render (a different variant axis).
    val labelHtml =
      ServeWeb.viewerPage(labelDefault, token = "t", basePath = "/compose-m3", siblings = all)
    val labelNav = labelHtml.substringAfter("class=\"cp-states\"").substringBefore("</nav>")
    assertTrue(labelNav.contains("aria-current=\"page\">Default</a>"), "current state active")
    assertTrue(
      labelNav.contains("/p/button-filled__ideal__pressed__light"),
      "links its own pressed state",
    )
    assertFalse(labelNav.contains("content-icon-label"), "does not cross into the content axis")

    // The icon+label render has no sibling state of its own, so it shows no switcher (rather than a
    // switcher that navigates back to the label-only button).
    val iconHtml =
      ServeWeb.viewerPage(iconLabel, token = "t", basePath = "/compose-m3", siblings = all)
    assertFalse(
      iconHtml.contains("class=\"cp-states\""),
      "the content variant with no state siblings shows no switcher",
    )
  }

  @Test
  fun `a plain stateless catalog renders grid and viewer unchanged`() {
    val plain =
      listOf(
        ServePreview(id = "com.example.Red", label = "Red"),
        ServePreview(id = "com.example.Blue", label = "Blue"),
      )

    val grid = ServeWeb.landingPage("bundle", plain, token = "t", basePath = "/bundle")
    assertEquals(2, Regex("class=\"cp-card\"").findAll(grid).count(), "both stateless cards shown")

    val viewer = ServeWeb.viewerPage(plain[0], token = "t", basePath = "/bundle", siblings = plain)
    assertFalse(viewer.contains("class=\"cp-states\""), "no switcher without state metadata")
  }

  // Button/Filled with its default render plus two props-axis variants (an RTL render and an ar-XB
  // pseudo-locale), each in light + dark — the shape the compose-m3 catalog folds via `variants`.
  private val buttonVariants =
    listOf(
      ServePreview(
        "button-filled__ideal__default__light",
        "Filled",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "button-filled__ideal__default__dark",
        "Filled",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "button-filled__ideal__default__light__direction-rtl",
        "Filled · RTL",
        state = "default",
        theme = "light",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__direction-rtl",
        "Filled · RTL",
        state = "default",
        theme = "dark",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__locale-ar-xb",
        "Filled · ar-XB",
        state = "default",
        theme = "light",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__locale-ar-xb",
        "Filled · ar-XB",
        state = "default",
        theme = "dark",
        props = jsonProps("locale" to "ar-XB"),
      ),
    )

  @Test
  fun `the grid folds props variants into the default card`() {
    val html =
      ServeWeb.landingPage("compose-m3", buttonVariants, token = "t", basePath = "/compose-m3")

    // Exactly one card — the default (a light/dark swap card), no separate RTL / locale card.
    assertEquals(1, Regex("class=\"cp-card\"").findAll(html).count(), "one card per component")
    assertTrue(html.contains("button-filled__ideal__default__light"), "default render is the card")
    assertFalse(html.contains("direction-rtl"), "the RTL variant is folded out of the grid")
    assertFalse(html.contains("locale-ar-xb"), "the locale variant is folded out of the grid")
  }

  @Test
  fun `the viewer renders a same-theme variant switcher with the current variant active`() {
    val current = buttonVariants[0] // default, light
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/compose-m3", siblings = buttonVariants)

    assertTrue(html.contains("aria-label=\"Component variant\""), "variant switcher rendered")
    val nav = html.substringAfter("aria-label=\"Component variant\"").substringBefore("</nav>")
    // Links the SAME-THEME (light) RTL + locale variants…
    assertTrue(
      nav.contains("/compose-m3/p/button-filled__ideal__default__light__direction-rtl"),
      "switcher links the same-theme RTL variant",
    )
    // …and never the dark render (that would jump the visitor's theme).
    assertFalse(nav.contains("__dark__direction-rtl"), "switcher stays within the current theme")
    // The default is marked active, and the variants carry human labels.
    assertTrue(nav.contains("aria-current=\"page\">Default</a>"), "the default is marked active")
    assertTrue(
      nav.contains(">RTL</a>") && nav.contains(">Locale ar-XB</a>"),
      "props variants render human labels",
    )
  }

  @Test
  fun `a component with no props variants renders no variant switcher`() {
    val plain =
      listOf(
        ServePreview("button__ideal__default__light", "button", state = "default", theme = "light"),
        ServePreview("button__ideal__default__dark", "button", state = "default", theme = "dark"),
      )
    val html =
      ServeWeb.viewerPage(plain[0], token = "t", basePath = "/compose-m3", siblings = plain)
    assertFalse(
      html.contains("aria-label=\"Component variant\""),
      "no variant switcher for a component without props variants",
    )
  }

  @Test
  fun `viewer advertises its rendered png to link unfurlers`() {
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("red", "Red & \"Blue\""),
        token = "unused",
        unfurl =
          ServeWeb.UnfurlMetadata(
            pageUrl = "https://preview.example/p/red?theme=dark&fontScale=1.5",
            imageUrl = "https://preview.example/render/red.png?theme=dark&fontScale=1.5",
          ),
      )

    assertTrue(
      html.contains("<meta property=\"og:title\" content=\"Red &amp; &quot;Blue&quot;\">"),
      "Open Graph title is present and escaped",
    )
    assertTrue(
      html.contains(
        "<meta property=\"og:image\" content=\"https://preview.example/render/red.png?" +
          "theme=dark&amp;fontScale=1.5\">"
      ),
      "Open Graph image points at the rendered PNG",
    )
    assertTrue(
      html.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"),
      "large-image Twitter card is present",
    )
    assertTrue(
      html.contains(
        "<meta name=\"twitter:image\" content=\"https://preview.example/render/red.png?" +
          "theme=dark&amp;fontScale=1.5\">"
      ),
      "Twitter card uses the same rendered PNG",
    )
    assertTrue(
      html.contains("<title>Red &amp; &quot;Blue&quot; — compose-preview</title>"),
      "document title is escaped exactly once",
    )
  }

  @Test
  fun `the rc backend selector renders every backend with the unavailable ones disabled`() {
    // A Remote Compose preview on an Android daemon: js (client canvas) + java + cmp-android are
    // enabled; the opt-in CMP/Wasm and unadvertised cmp-jvm lanes remain disabled.
    val preview = ServePreview(id = "widget.Chip", label = "chip")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/remote-m3",
        siblings = listOf(preview),
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android"),
      )

    assertTrue(html.contains("id=\"cp-rc-backends\""), "selector rendered")
    // Every universe entry appears as a chip.
    for (wire in listOf("js", "cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      assertTrue(html.contains("data-rc-backend=\"$wire\""), "chip for $wire present")
    }
    // Java is the seeded default (the server-side snapshot player).
    assertTrue(html.contains("data-default=\"java\""), "java is the default backend")
    // cmp-jvm is the disabled chip; the enabled ones are not.
    val jvmChip = Regex("<button[^>]*data-rc-backend=\"cmp-jvm\"[^>]*>").find(html)?.value ?: ""
    assertTrue(jvmChip.contains(" disabled"), "cmp-jvm chip is disabled: '$jvmChip'")
    val androidChip =
      Regex("<button[^>]*data-rc-backend=\"cmp-android\"[^>]*>").find(html)?.value ?: ""
    assertFalse(androidChip.contains(" disabled"), "cmp-android chip is enabled: '$androidChip'")
  }

  @Test
  fun `a js-only host disables the server-side backend chips`() {
    // A static bundle carries the `.rc` doc (js works client-side) but has no daemon, so the
    // server-side java / cmp-android lanes are disabled alongside the never-available cmp-jvm.
    val preview = ServePreview(id = "widget.Chip", label = "chip")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/remote-m3",
        siblings = listOf(preview),
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js"),
      )

    assertTrue(html.contains("data-default=\"js\""), "js is the default when it is the only lane")
    for (wire in listOf("cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      val chip = Regex("<button[^>]*data-rc-backend=\"$wire\"[^>]*>").find(html)?.value ?: ""
      assertTrue(chip.contains(" disabled"), "$wire chip disabled on a js-only host: '$chip'")
    }
    val jsChip = Regex("<button[^>]*data-rc-backend=\"js\"[^>]*>").find(html)?.value ?: ""
    assertFalse(jsChip.contains(" disabled"), "js chip enabled: '$jsChip'")
  }

  @Test
  fun `cmp wasm backend gets its own iframe and mode`() {
    val preview =
      ServePreview(
        id = "widget.Chip",
        label = "chip",
        remoteComposeKnobs =
          listOf(RemoteComposeKnobDeclaration("label", RemoteNamedValue.StringValue("Hello"))),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "cmp-wasm"),
      )

    val chip = Regex("<button[^>]*data-rc-backend=\"cmp-wasm\"[^>]*>").find(html)?.value ?: ""
    assertFalse(chip.contains(" disabled"), "cmp-wasm chip enabled: '$chip'")
    assertTrue(html.contains("id=\"cp-rc-wasm\""), "dedicated CMP/Wasm iframe is present")
    assertTrue(html.contains("value=\"rc-wasm\""), "dedicated CMP/Wasm mode is present")
    assertTrue(
      html.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "repository-owned player can fetch the tokened document from its own origin",
    )
    val knob = Regex("<input[^>]*data-rc-name=\"label\"[^>]*>").find(html)?.value ?: ""
    assertFalse(knob.contains(" disabled"), "CMP/Wasm can apply named values: '$knob'")
    val viewerJs = ServeWebAssets.load("viewer.js")!!.bytes.decodeToString()
    assertTrue(viewerJs.contains("namedValues="), "named values are passed to the isolated host")
    assertTrue(viewerJs.contains("e.origin !== location.origin"), "messages are origin checked")
    assertTrue(
      viewerJs.contains("new CustomEvent(e.data.type"),
      "validated host actions are exposed without executing their payload",
    )
  }

  @Test
  fun `a non-rc preview renders no backend selector`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("id=\"cp-rc-backends\""), "no selector for a non-rc preview")
  }

  @Test
  fun `the viewer links the preview source when a source href is supplied`() {
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        sourceHref = "https://github.com/o/r/blob/main/src/main/A.kt",
      )
    assertTrue(html.contains("class=\"cp-source\""), "source link block rendered")
    assertTrue(
      html.contains("href=\"https://github.com/o/r/blob/main/src/main/A.kt\""),
      "links the resolved blob url",
    )
    // The module-relative path is surfaced as the link tooltip.
    assertTrue(html.contains("title=\"src/main/A.kt\""), "source path shown as tooltip")
  }

  @Test
  fun `landing and viewer surface preview engagement counts`() {
    val previews =
      listOf(
        ServePreview(id = "plain.Button", label = "button"),
        ServePreview(id = "plain.Card", label = "card"),
      )
    val landing =
      ServeWeb.landingPage(
        "bundle",
        previews,
        token = "t",
        engagement = mapOf("plain.Button" to ServeWeb.PreviewEngagement(12)),
        systemViews = 1234,
      )
    assertTrue(landing.contains("""<div class="cp-engage">12 views</div>"""), landing)
    assertTrue(landing.contains("2 preview(s) · 1.2k views"), landing)

    val viewer =
      ServeWeb.viewerPage(
        previews[0],
        token = "t",
        siblings = previews,
        engagement = ServeWeb.PreviewEngagement(13),
      )
    assertTrue(viewer.contains("""<p class="cp-viewer-engage">13 views</p>"""), viewer)
  }

  @Test
  fun `home cards subtly surface catalog engagement`() {
    val html =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "compose-m3",
              title = "Material 3",
              subtitle = null,
              previewCount = 42,
              trust = null,
              heroPreviewId = null,
              views = 12_345,
            )
          ),
        token = "t",
      )
    assertTrue(html.contains("42 preview(s) · 12.3k views"), html)
  }

  @Test
  fun `the viewer renders no source link without a source href`() {
    // A local / unprovenanced session (or a preview with no recorded source) passes sourceHref
    // null.
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("class=\"cp-source\""), "no source link when no href is supplied")
  }
}
