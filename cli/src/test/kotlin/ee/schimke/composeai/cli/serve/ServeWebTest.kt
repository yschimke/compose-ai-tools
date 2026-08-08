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
  fun `the renderer combo lists every player with the unavailable ones disabled`() {
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

    assertTrue(html.contains("id=\"cp-lane-select\""), "the renderer combo is rendered")
    // Every universe entry is an option — the unavailable ones included, so the set of players
    // stays legible from any session.
    for (wire in listOf("js", "cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      assertTrue(html.contains("value=\"rc:$wire\""), "option for $wire present")
    }
    // Java is the seeded default: both the combo's selection and the chip's opening label.
    assertTrue(html.contains("data-rc-default=\"java\""), "java is the default player")
    assertTrue(html.contains("<option value=\"rc:java\">Java</option>"), html)
    // The combo itself rests on its placeholder — the chip is what names the current lane, and a
    // combo repeating that name beside it read as two controls arguing about the same fact.
    assertTrue(html.contains("<option value=\"\" selected>Switch renderer…</option>"), html)
    assertTrue(
      html.contains("<span id=\"cp-live-toggle-label\">Java</span>"),
      "the chip names the lane it opens on",
    )
    // cmp-jvm is the disabled option (and says why in its own label); the enabled ones are not.
    assertTrue(
      html.contains("<option value=\"rc:cmp-jvm\" disabled>CMP JVM (unavailable)</option>"),
      html,
    )
    val android = Regex("<option value=\"rc:cmp-android\"[^>]*>").find(html)?.value ?: ""
    assertFalse(android.contains(" disabled"), "cmp-android is offered: '$android'")
    // …and the step out to every player side by side.
    assertTrue(
      html.contains("href=\"/remote-m3/compare?format=rc&preview=widget.Chip&token=t\""),
      html,
    )
    assertTrue(html.contains(">compare players →</a>"), "the compare link names what it does")
  }

  @Test
  fun `a js-only host disables the server-side player options and offers no comparison`() {
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

    assertTrue(
      html.contains("data-rc-default=\"js\""),
      "js is the default when it is the only lane",
    )
    for (wire in listOf("cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      val option = Regex("<option value=\"rc:$wire\"[^>]*>").find(html)?.value ?: ""
      assertTrue(option.contains(" disabled"), "$wire disabled on a js-only host: '$option'")
    }
    val js = Regex("<option value=\"rc:js\"[^>]*>").find(html)?.value ?: ""
    assertFalse(js.contains(" disabled"), "js is offered: '$js'")
    // One player is nothing to compare against, so the link stays off.
    assertFalse(html.contains("compare players"), "no comparison link with a single player")
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

    val option = Regex("<option value=\"rc:cmp-wasm\"[^>]*>").find(html)?.value ?: ""
    assertFalse(option.contains(" disabled"), "cmp-wasm is offered: '$option'")
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
    assertFalse(html.contains("id=\"cp-lane-select\""), "no combo for a single-lane preview")
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
    assertTrue(viewer.contains("""<span class="cp-viewer-engage">13 views</span>"""), viewer)
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
  fun `the viewer offers a prefilled issue link beside the source link`() {
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        sourceHref = "https://github.com/o/r/blob/main/src/main/A.kt",
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/o/r/issues/new",
            title = "Preview issue: button",
            body = "render: https://host/render/x.png",
            bodyTemplate = "render: {{render}}",
            repo = "o/r",
            login = "octocat",
          ),
      )
    assertTrue(html.contains("class=\"cp-preview-links\""), "source + report share one row")
    assertTrue(html.contains("id=\"cp-report\""), "report affordance rendered")
    // A GET form, not a link: nothing page-derived may reach a navigation sink, so the action is a
    // server-rendered literal and the prefill rides in hidden inputs the browser encodes on submit.
    assertTrue(
      html.contains("<form class=\"cp-report\" id=\"cp-report\" method=\"get\"") &&
        html.contains("action=\"https://github.com/o/r/issues/new\""),
      "the issue form posts to the resolved repo",
    )
    assertTrue(
      html.contains("name=\"title\" value=\"Preview issue: button\"") &&
        html.contains("name=\"body\" id=\"cp-report-body\"") &&
        html.contains("value=\"render: https://host/render/x.png\""),
      "the server-filled prefill works without JS",
    )
    assertTrue(
      html.contains("data-report-template=\"render: {{render}}\""),
      "carries the template the viewer JS re-substitutes at the current overrides",
    )
    // The tooltip names the repo the issue lands on, and — when this box knows the visitor's GitHub
    // session — whose account will author it.
    assertTrue(html.contains("title=\"File an issue on o/r as @octocat\""), html)
  }

  @Test
  fun `the viewer links the figma node a preview is specified by`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        figmaSpec =
          ServeWeb.FigmaSpec(
            url = "https://www.figma.com/design/abc123?node-id=73-6",
            label = "Contact chat",
          ),
      )
    assertTrue(html.contains("class=\"cp-preview-links\""), "the provenance row is rendered")
    assertTrue(html.contains("class=\"cp-figma-link\""), "figma spec link rendered")
    assertTrue(
      html.contains("href=\"https://www.figma.com/design/abc123?node-id=73-6\""),
      "links the resolved node",
    )
    // Opened in a new tab, and the label names which spec it is.
    assertTrue(html.contains("rel=\"noopener noreferrer\""), html)
    assertTrue(html.contains("specified by — Contact chat"), "the tooltip names the reference")
  }

  @Test
  fun `the viewer offers the imported spec as a lane beside the renderers`() {
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "contact-chat-figma",
            previewId = preview.id,
            label = "Contact chat",
            raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-lane\""), "the spec lane carrier is rendered")
    // The lane is one option in the renderer combo, named for the provider it imported from, and
    // the raster is served from THIS server's reference route — nothing points at figma.com.
    assertTrue(
      html.contains("<option value=\"spec\">Figma spec</option>"),
      "the spec is offered as a renderer option",
    )
    assertTrue(
      html.contains("data-spec-src=\"/meshcore-mobile/reference/contact-chat-figma.png?token=t\""),
      html,
    )
    // A hidden mode radio + a stage image, so the lane joins the same mode machinery as the
    // player lanes (bookmarkable `?mode=spec`, Back/Forward, one lane on the stage at a time).
    assertTrue(html.contains("value=\"spec\" id=\"cp-spec-toggle\""), "the mode radio is rendered")
    assertTrue(html.contains("id=\"cp-spec-img\""), "the stage image is rendered")
    // …and the step from "look at the spec" to "diff it" against this render.
    assertTrue(
      html.contains(
        "/meshcore-mobile/compare/com.example.ProfileScreenPreview?token=t" +
          "&amp;reference=contact-chat-figma"
      ),
      html,
    )
  }

  @Test
  fun `the spec lane offers diff triptych and slider beside the plain spec`() {
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "contact-chat-figma",
            previewId = preview.id,
            label = "Contact chat",
            raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
      )
    // Four ways to look at the same pair, all four on the stage rather than behind a navigation to
    // /compare — which is the point: the render worth comparing is the one the viewer's overrides,
    // knobs and theme just produced, and leaving the page loses it.
    listOf("spec", "diff", "triptych", "slider").forEach { view ->
      assertTrue(html.contains("data-cp-spec-view=\"$view\""), "the $view view is offered: $html")
    }
    // `spec` is the default and the only pressed one, so a visitor who ignores the group sees
    // exactly what this lane always showed.
    assertTrue(
      html.contains("data-cp-spec-view=\"spec\" aria-pressed=\"true\""),
      "the plain spec is the default view",
    )
    assertEquals(1, Regex("aria-pressed=\"true\"").findAll(html).count(), "one view is pressed")
    // Hidden until the lane is entered — while a render is on the stage there is no pair to
    // compare, and spec-compare.js reveals the group from openSpec().
    assertTrue(
      html.contains("id=\"cp-spec-views\" role=\"group\" aria-label=\"Design comparison\" hidden"),
      html,
    )
    // The comparison surface: three canvas panels plus the wipe, hidden until a view is picked,
    // and carrying the reference raster it normalises against.
    assertTrue(
      html.contains(
        "id=\"cp-spec-compare\" hidden data-view=\"spec\" " +
          "data-reference=\"/meshcore-mobile/reference/contact-chat-figma.png?token=t\""
      ),
      html,
    )
    listOf("cp-spec-reference", "cp-spec-diff", "cp-spec-actual", "cp-spec-wipe-canvas").forEach {
      assertTrue(html.contains("id=\"$it\""), "the $it canvas is rendered: $html")
    }
    assertTrue(html.contains("id=\"cp-spec-wipe-range\""), "the wipe carries a range control")
    assertTrue(html.contains("id=\"cp-spec-score\""), "the match readout is rendered")
    // Load order is load-bearing: viewer.js calls window.cpSpecCompare on the way into the lane,
    // and spec-compare.js draws every surface from format-compare.js's primitives.
    val formatCompare = html.indexOf("format-compare.js")
    val specCompare = html.indexOf("spec-compare.js")
    val viewer = html.indexOf("/viewer.js")
    assertTrue(formatCompare in 1 until specCompare, "format-compare.js precedes spec-compare.js")
    assertTrue(specCompare in 1 until viewer, "spec-compare.js precedes viewer.js")
  }

  @Test
  fun `the viewer offers no spec lane when the catalog publishes no reference`() {
    // Every catalog that has not adopted design-parity: no lane, no stage image, no mode radio.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("cp-spec-lane"), "no spec lane without a reference")
    assertFalse(html.contains("cp-spec-img"), "no spec stage image without a reference")
    assertFalse(html.contains("id=\"cp-spec-toggle\""), "no spec mode radio without a reference")
    // …and none of the comparison surface either: no canvases, no view group, and no request for
    // the script that drives them.
    assertFalse(html.contains("cp-spec-compare"), "no comparison surface without a reference")
    assertFalse(html.contains("data-cp-spec-view"), "no diff options without a reference")
    assertFalse(html.contains("spec-compare.js"), "spec-compare.js is not loaded without a lane")
  }

  @Test
  fun `a non-figma design reference is still offered as a spec lane`() {
    // design-parity's other adapters (a committed PNG bundle, an HTML export, Stitch) publish the
    // same canonical raster, so the lane is provider-neutral — only the chip's wording changes.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "button-primary",
            previewId = preview.id,
            label = "Button / Primary",
            raster = DesignReferenceRaster(path = "references/button-primary.png"),
            source = DesignReferenceSource(provider = "png"),
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-lane\""), "the lane is offered for any provider")
    assertTrue(
      html.contains("<option value=\"spec\">Design spec</option>"),
      "a non-Figma provider reads as a plain design spec",
    )
  }

  @Test
  fun `the viewer renders no figma link when the catalog names no spec`() {
    // The common case: a catalog with no references, or whose references are HTML/PNG exports.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("cp-figma-link"), "no figma link when no spec is supplied")
    assertFalse(
      html.contains("class=\"cp-preview-links\""),
      "the links row is omitted entirely when nothing fills it",
    )
  }

  @Test
  fun `the viewer renders no report link without a report target`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("id=\"cp-report\""), "no report link when no target is supplied")
    assertFalse(
      html.contains("class=\"cp-preview-links\""),
      "the links row is omitted entirely when neither link exists",
    )
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

  @Test
  fun `the viewer carries history attributes when the catalog has delivery provenance`() {
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyManifestUrl =
          ServeUrls.historyManifestUrl("yschimke/compose-ai-tools", "compose-preview/main"),
        historyRepo = "yschimke/compose-ai-tools",
      )

    assertTrue(
      html.contains(
        "data-history-url=\"https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/main/history.json\""
      ),
      "viewer must tell the client where the manifest lives",
    )
    assertTrue(html.contains("data-history-repo=\"yschimke/compose-ai-tools\""))
    assertTrue(html.contains("viewer-history.js"), "the timeline script is loaded")
  }

  @Test
  fun `the viewer omits history attributes without delivery provenance`() {
    // An uploaded bundle or local project has no branch to read history from. Emitting the
    // attributes anyway would ship a timeline that can only fail to load.
    val html = ServeWeb.viewerPage(checkbox[0], token = "t", basePath = "/compose-m3")

    assertFalse(html.contains("data-history-url"))
    assertFalse(html.contains("data-history-repo"))
  }

  @Test
  fun `a half-configured history is omitted rather than half-rendered`() {
    // A timeline the visitor cannot click through to an old render is worse than no timeline.
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyManifestUrl = "https://raw.githubusercontent.com/o/r/b/history.json",
        historyRepo = null,
      )

    assertFalse(html.contains("data-history-url"))
  }

  @Test
  fun `an inline history payload is embedded for offline rendering`() {
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyRepo = "o/r",
        historyInlineJson = """{"previews":{}}""",
      )

    assertTrue(html.contains("<script type=\"application/json\" id=\"cp-history-data\">"))
    assertTrue(html.contains("""{"previews":{}}"""))
  }

  @Test
  fun `an inline payload cannot close the script element early`() {
    // The only sequence that can break out of <script> is `</`. A payload carrying it verbatim
    // would end the element and spill the rest into the document as markup.
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyRepo = "o/r",
        historyInlineJson = """{"x":"</script><img src=x onerror=alert(1)>"}""",
      )

    assertFalse(html.contains("</script><img"), "raw </script> must not survive into the page")
    assertTrue(html.contains("<\\/script>"), "it is escaped, not dropped")
  }

  @Test
  fun `no inline payload emits no data element`() {
    val html =
      ServeWeb.viewerPage(checkbox[0], token = "t", basePath = "/compose-m3", historyRepo = "o/r")

    assertFalse(html.contains("cp-history-data"))
  }
}
