package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeIssueReportTest {

  private val full =
    ServeIssueReport.Context(
      repo = "yschimke/compose-samples",
      previewId = "Article__dark",
      previewLabel = "Article",
      system = "jetnews",
      componentId = "Article/Card",
      referenceId = "article-card-figma",
      variant = "dark",
      overrides = linkedMapOf("uiMode" to "dark"),
      sourceUrl = "https://github.com/yschimke/compose-samples/blob/previews/app/Article.kt",
      catalog = "yschimke/compose-samples@design-artifacts/jetnews",
      toolVersion = "0.19.37",
      viewerUrl = "https://preview.coo.ee/jetnews/p/Article__dark",
      comparisonUrl =
        "https://preview.coo.ee/jetnews/compare/Article__dark?reference=article-card-figma",
      renderUrl = "https://preview.coo.ee/jetnews/render/Article__dark.png?uiMode=dark",
      publicRender = true,
    )

  /** The same report as filed from the focused comparison, which has the reference too. */
  private val pair =
    full.copy(
      referenceUrl = "https://preview.coo.ee/jetnews/reference/article-card-figma.png?uiMode=dark"
    )

  @Test
  fun `a preview's bug is filed against the catalog's source repo`() {
    // The source repo owns the Kotlin that misrendered — even when it is a fork (Android's samples
    // are rendered from preview branches in yschimke/compose-samples), that fork is where the
    // preview code lives, so that is where the report belongs.
    assertEquals(
      "yschimke/compose-samples",
      ServeIssueReport.repoFor(
        ServeWeb.CatalogSource("yschimke/compose-samples", "previews", ":app"),
        ServeWeb.CatalogProvenance("yschimke/other-repo", "design-artifacts/jetnews"),
      ),
    )
  }

  @Test
  fun `without a source the delivery repo takes over, and without either the renderer's own`() {
    assertEquals(
      "yschimke/other-repo",
      ServeIssueReport.repoFor(
        null,
        ServeWeb.CatalogProvenance("yschimke/other-repo", "design-artifacts/jetnews"),
      ),
    )
    // A plain uploaded bundle or a local session names neither. The rendering pipeline is ours, so
    // that is the best guess for where a "this preview looks wrong" report should land.
    assertEquals("yschimke/compose-ai-tools", ServeIssueReport.repoFor(null, null))
  }

  @Test
  fun `the body carries the facts a triager would otherwise have to ask for`() {
    val body = ServeIssueReport.body(full)
    assertTrue(body.contains("| Design system | `jetnews` |"), body)
    assertTrue(body.contains("| Preview | `Article__dark` |"), body)
    assertTrue(
      body.contains(
        "| Source | https://github.com/yschimke/compose-samples/blob/previews/app/Article.kt |"
      ),
      body,
    )
    assertTrue(
      body.contains("| Catalog | `yschimke/compose-samples@design-artifacts/jetnews` |"),
      body,
    )
    assertTrue(body.contains("| Rendered by | compose-ai-tools 0.19.37 |"), body)
    assertTrue(
      body.contains("[Open this preview](https://preview.coo.ee/jetnews/p/Article__dark)"),
      body,
    )
    // The screenshot is asked for as a paste, because that lands the pixels on GitHub's own CDN
    // rather than leaving the evidence pointed at a URL that re-renders later.
    assertTrue(body.contains("Copy PNG"), "the body tells the reporter how to attach the render")
    assertTrue(body.contains("```compose-parity-locator/v1"), body)
  }

  @Test
  fun `locator block round trips arbitrary override text`() {
    val overrides =
      linkedMapOf("knob.label" to "Send;knob.color=red\n日本語", "knob.quote" to "a=\"b\"")
    val context = full.copy(overrides = overrides)
    assertEquals(
      ServeIssueReport.locator(context),
      ServeIssueReport.locatorFromBody(ServeIssueReport.body(context)),
    )
  }

  @Test
  fun `canonical override JSON is stable across insertion order and uses code point order`() {
    val first = linkedMapOf("z" to "last", "😀" to "astral", "a" to "first")
    val second = linkedMapOf("a" to "first", "z" to "last", "😀" to "astral")
    val expected = "{\"a\":\"first\",\"z\":\"last\",\"😀\":\"astral\"}"
    assertEquals(expected, ServeIssueReport.canonicalOverrides(first))
    assertEquals(expected, ServeIssueReport.canonicalOverrides(second))
  }

  @Test
  fun `comparison template exposes placeholders for browser-computed values`() {
    val template = ServeIssueReport.body(full, renderPlaceholder = true)
    assertTrue(template.contains("{{render}}"), template)
    assertTrue(template.contains("{{rawScores}}"), template)
  }

  @Test
  fun `a public render is embedded as an image, not just linked`() {
    val body = ServeIssueReport.body(full)
    // GitHub renders this inline (via its camo proxy), so the reporter's evidence is visible in the
    // issue without anyone clicking through.
    assertTrue(
      body.contains(
        "![Article](https://preview.coo.ee/jetnews/render/Article__dark.png?uiMode=dark)"
      ),
      body,
    )
    // …and the separate link is dropped, since the image already carries that URL.
    assertFalse(body.contains("[PNG at these settings]"), body)
    // The embed is honest about what it is: a live render that moves when the catalog does.
    assertTrue(body.contains("LIVE render"), body)
    assertTrue(body.contains("Camo proxies the source URL"), body)
    assertTrue(body.contains("does not make a versioned snapshot"), body)
    assertTrue(body.contains("Copy PNG"), "the durable paste path is still offered")
  }

  @Test
  fun `a comparison report embeds both panels, not just the render`() {
    // #4765: an issue about the reference and the render disagreeing arrived showing one of them,
    // so the picture contradicted the complaint and a triager had to open the comparison to see
    // what was being reported.
    val body = ServeIssueReport.body(pair)
    assertTrue(body.contains("| Design reference | Render |"), body)
    assertTrue(
      body.contains(
        "| ![reference](https://preview.coo.ee/jetnews/reference/article-card-figma.png" +
          "?uiMode=dark) | " +
          "![Article](https://preview.coo.ee/jetnews/render/Article__dark.png?uiMode=dark) |"
      ),
      body,
    )
    // The third panel has no URL — the browser composes it — so the body says so and asks for it.
    assertTrue(body.contains("The DIFF between those two panels"), body)
    assertTrue(body.contains("capture control"), body)
    // Neither panel is offered a second time as a bare link: the images already carry both URLs.
    assertFalse(body.contains("[PNG at these settings]"), body)
    assertFalse(body.contains("[Design reference PNG]"), body)
  }

  @Test
  fun `the pair leaves exactly one render placeholder for the page's script to fill`() {
    // `fillReport` in `annotate/report.ts` substitutes the FIRST `]({{render}})` it finds. The
    // reference cell is a literal URL and comes first in the row, so a second occurrence would
    // send the swap to the wrong panel and file a body still carrying the placeholder.
    val tpl = ServeIssueReport.body(pair, renderPlaceholder = true)
    assertEquals(1, tpl.split("]({{render}})").size - 1, tpl)
    assertTrue(tpl.contains("![Article]({{render}})"), tpl)
    assertTrue(tpl.contains("![reference](https://preview.coo.ee/jetnews/reference/"), tpl)
  }

  @Test
  fun `half a comparison is never embedded, and both halves are still linked`() {
    // One panel of a pair reads as "the render" and is worse evidence than the render admitting
    // what it is — so an unreachable half takes the whole pair back to the link form, which still
    // names both sides for a triager who can reach the box.
    val local = pair.copy(referenceUrl = "http://127.0.0.1:8080/reference/article-card-figma.png")
    val body = ServeIssueReport.body(local)
    assertFalse(body.contains("| Design reference | Render |"), body)
    assertTrue(body.contains("![Article](https://preview.coo.ee/jetnews/render/"), body)
    assertTrue(body.contains("[Design reference PNG](http://127.0.0.1:8080/reference/"), body)
  }

  @Test
  fun `a token-gated comparison links both panels rather than embedding either`() {
    val body = ServeIssueReport.body(pair.copy(publicRender = false))
    assertFalse(body.contains("!["), body)
    assertTrue(
      body.contains("[PNG at these settings](https://preview.coo.ee/jetnews/render/"),
      body,
    )
    assertTrue(
      body.contains("[Design reference PNG](https://preview.coo.ee/jetnews/reference/"),
      body,
    )
  }

  @Test
  fun `a report with no reference is unchanged by the pair form existing`() {
    // The viewer's plain lane has nothing on the stage beside the render, and its spec lane keeps
    // the picked source out of the URL — so neither grows a panel this writer would be inventing.
    val body = ServeIssueReport.body(full)
    assertFalse(body.contains("| Design reference | Render |"), body)
    assertFalse(body.contains("Design reference PNG"), body)
  }

  @Test
  fun `a render GitHub cannot reach stays a link rather than a broken image`() {
    // A developer's local `compose-preview serve`. Camo cannot fetch this, so an embed would put a
    // broken-image icon in their issue where a working link belongs.
    val local = full.copy(renderUrl = "http://127.0.0.1:8080/render/Article__dark.png")
    val body = ServeIssueReport.body(local)
    assertFalse(body.contains("!["), body)
    assertTrue(body.contains("[PNG at these settings](http://127.0.0.1:8080/"), body)
  }

  @Test
  fun `a token-gated server keeps the link form even on a public hostname`() {
    // withoutToken strips the session token from every URL in the body, and a non-public render
    // lane 404s a tokenless request — so camo would fetch a 404 and every filed issue would show a
    // broken screenshot. Reachability is not authorization.
    val gated = full.copy(publicRender = false)
    val body = ServeIssueReport.body(gated)
    assertFalse(body.contains("!["), body)
    assertTrue(
      body.contains("[PNG at these settings](https://preview.coo.ee/jetnews/render/"),
      body,
    )
    // …and the template agrees, so the viewer's live swap cannot reintroduce the embed.
    assertFalse(ServeIssueReport.body(gated, renderPlaceholder = true).contains("!["))
  }

  @Test
  fun `only a publicly reachable https URL is embeddable`() {
    assertTrue(ServeIssueReport.isEmbeddable("https://preview.coo.ee/x/render/a.png"))
    assertTrue(ServeIssueReport.isEmbeddable("https://previews.example.com:8443/render/a.png"))
    // Plain HTTP, loopback, RFC 1918, single-label intranet names and `.local` are all unreachable
    // from GitHub's proxy.
    assertFalse(ServeIssueReport.isEmbeddable("http://preview.coo.ee/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://localhost:8080/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://127.0.0.1/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://10.1.2.3/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://192.168.1.10/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://172.20.0.5/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://build-box/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable("https://previews.local/x.png"))
    assertFalse(ServeIssueReport.isEmbeddable(null))
    assertFalse(ServeIssueReport.isEmbeddable("  "))
    // …but a public address that merely looks private-ish is fine.
    assertTrue(ServeIssueReport.isEmbeddable("https://172.32.0.5/x.png"))
  }

  @Test
  fun `the template keeps the shape the real URL earned`() {
    // The placeholder is not itself a URL, so embeddability is decided by the real render URL —
    // otherwise the JS swap could turn a working image into a broken one, or vice versa.
    assertTrue(
      ServeIssueReport.body(full, renderPlaceholder = true).contains("![Article]({{render}})")
    )
    val local = full.copy(renderUrl = "http://127.0.0.1:8080/render/a.png")
    val tpl = ServeIssueReport.body(local, renderPlaceholder = true)
    assertFalse(tpl.contains("!["), tpl)
    assertTrue(tpl.contains("[PNG at these settings]({{render}})"), tpl)
  }

  @Test
  fun `unknown facts drop their row rather than filing a half-empty template`() {
    val body = ServeIssueReport.body(ServeIssueReport.Context(repo = "o/r", previewId = "Solo"))
    assertTrue(body.contains("| Preview | `Solo` |"), body)
    assertFalse(body.contains("Design system"), body)
    assertFalse(body.contains("Catalog"), body)
    assertFalse(body.contains("Rendered by"), body)
    assertFalse(body.contains("Open this preview"), body)
  }

  @Test
  fun `the form action is the target repo's issue form`() {
    // A literal the viewer's JS never touches — see ServeIssueReport.action for why the affordance
    // is a GET form rather than a link whose href gets rewritten.
    assertEquals(
      "https://github.com/yschimke/compose-samples/issues/new",
      ServeIssueReport.action(full.repo),
    )
  }

  @Test
  fun `the template form leaves the render link as a placeholder the viewer JS can substitute`() {
    val tpl = ServeIssueReport.body(full, renderPlaceholder = true)
    assertTrue(tpl.contains("({{render}})"), tpl)
    assertFalse(
      tpl.contains("Article__dark.png?uiMode=dark"),
      "the served render URL is not baked into the template",
    )
  }

  @Test
  fun `a page-scoped report names the page rather than inventing a preview`() {
    // The comparison wall shows every comparable component and singles out none, so its report
    // carries the page — with the lane its query names — and drops the preview-shaped rows the
    // same way every other unknown fact is dropped (issue #4289).
    val wall =
      ServeIssueReport.Context(
        repo = "yschimke/wear-m3-catalog",
        system = "wear-m3-catalog",
        catalog = "yschimke/wear-m3-catalog@design-artifacts/wear-m3-catalog",
        toolVersion = "1.18.0",
        pageUrl = "https://preview.coo.ee/wear-m3-catalog/compare?format=reference",
        publicRender = true,
      )
    val body = ServeIssueReport.body(wall)
    assertTrue(body.contains("### Which page"), body)
    assertFalse(body.contains("### Which preview"), body)
    assertFalse(body.contains("| Preview |"), body)
    assertTrue(body.contains("| Design system | `wear-m3-catalog` |"), body)
    assertTrue(
      body.contains("| Catalog | `yschimke/wear-m3-catalog@design-artifacts/wear-m3-catalog` |"),
      body,
    )
    // The lane is in the query, and it is the whole of what "which comparison" means here.
    assertTrue(
      body.contains(
        "[Open this page](https://preview.coo.ee/wear-m3-catalog/compare?format=reference)"
      ),
      body,
    )
    // No preview, no parity locator: the fence keys a report to one preview/reference pair, and
    // this report is about neither.
    assertNull(ServeIssueReport.locator(wall))
    assertFalse(body.contains("compose-parity-locator/v1"), body)
    // And no advice to press a Copy PNG button that only exists in the viewer's export panel.
    assertFalse(body.contains("Export & direct links"), body)
    assertTrue(body.contains("Report a problem"), body)
  }

  @Test
  fun `a report with no render leaves no placeholder for a substitution that never comes`() {
    // The wall runs no script over its report body, so a `{{render}}` in the template would be
    // filed verbatim. The placeholder stands in for a render URL the report actually has.
    val wall =
      ServeIssueReport.Context(repo = "o/r", system = "wear-m3-catalog", pageUrl = "https://host/c")
    val template = ServeIssueReport.body(wall, renderPlaceholder = true)
    assertFalse(template.contains("{{render}}"), template)
    assertFalse(template.contains("{{rawScores}}"), template)
    assertEquals(ServeIssueReport.body(wall), template)
  }

  @Test
  fun `a session token never rides along into an issue body`() {
    // The token IS the capability to drive a token-gated server; an issue is public.
    assertEquals(
      "https://host/p/x?uiMode=dark",
      ServeIssueReport.withoutToken("https://host/p/x?token=s3cret&uiMode=dark"),
    )
    assertEquals("https://host/p/x", ServeIssueReport.withoutToken("https://host/p/x?token=s3cret"))
    assertEquals("https://host/p/x", ServeIssueReport.withoutToken("https://host/p/x"))
    assertNull(ServeIssueReport.withoutToken(null))
    assertNull(ServeIssueReport.withoutToken("  "))
    val tokenBearing =
      full.copy(
        sourceUrl = "https://github.com/o/r/blob/main/A.kt?token=source",
        viewerUrl = "https://host/p/x?token=viewer",
        comparisonUrl = "https://host/compare/x?token=comparison",
        renderUrl = "https://host/render/x.png?token=render",
      )
    val body = ServeIssueReport.body(tokenBearing)
    assertFalse(body.contains("token="), body)
  }

  @Test
  fun `the writer emits the shared locator fixture byte for byte`() {
    // The other half of `compose-parity-locator/v1`. This side asserts the bytes the writer puts in
    // an issue body; scripts/design-artifacts/parity-issues.test.mjs asserts the producer parses
    // those same bytes back. Without one file both read, each engine only ever tests itself — which
    // is how the producer came to reject an omitted `revision`, an empty `variant`, and an override
    // map ordered by code point, none of which the writer can be talked out of emitting.
    val fixture =
      Json.parseToJsonElement(
          File(repoRoot(), "scripts/design-artifacts/fixtures/parity-locators.json").readText()
        )
        .jsonObject
    assertEquals(ServeIssueReport.LOCATOR_FENCE, fixture["schema"]?.jsonPrimitive?.contentOrNull)
    val written =
      fixture["cases"]!!.jsonArray.map { it.jsonObject }.filter { it.containsKey("writer") }
    // A fixture that silently stopped carrying writer cases would pass every assertion below.
    assertEquals(7, written.size, "the fixture must keep exercising the writer")
    for (case in written) {
      val name = case["name"]!!.jsonPrimitive.content
      val writer = case["writer"]!!.jsonObject
      val locator =
        ServeIssueReport.Locator(
          repository = writer["repository"]!!.jsonPrimitive.content,
          system = writer["system"]!!.jsonPrimitive.content,
          componentId = writer["componentId"]!!.jsonPrimitive.content,
          previewId = writer["previewId"]!!.jsonPrimitive.content,
          referenceId = writer["referenceId"]!!.jsonPrimitive.content,
          variant = writer["variant"]!!.jsonPrimitive.content,
          overrides =
            writer["overrides"]!!.jsonObject.entries.associate {
              it.key to it.value.jsonPrimitive.content
            },
          element = writer["element"]?.jsonPrimitive?.contentOrNull,
          bounds = writer["bounds"]?.jsonObject?.let(::boundsOf),
          revision = writer["revision"]?.jsonPrimitive?.contentOrNull,
        )
      val block = case["block"]!!.jsonPrimitive.content
      assertEquals(block, ServeIssueReport.locatorBlock(locator), name)
      assertEquals(locator, ServeIssueReport.locatorFromBody(block), name)
    }
  }

  @Test
  fun `the writer emits one block per component of an umbrella report`() {
    // The other half of the multi-component contract: `parity-issues.test.mjs` asserts the producer
    // reads these bodies back as one row per block. An issue like m3-catalog#42 names three
    // components; one block can name one, so the body is their concatenation, in order.
    val fixture =
      Json.parseToJsonElement(
          File(repoRoot(), "scripts/design-artifacts/fixtures/parity-locators.json").readText()
        )
        .jsonObject
    val bodies =
      fixture["bodies"]!!.jsonArray.map { it.jsonObject }.filter { it.containsKey("writers") }
    assertEquals(1, bodies.size, "the fixture must keep exercising the writer")
    for (case in bodies) {
      val name = case["name"]!!.jsonPrimitive.content
      val locators =
        case["writers"]!!
          .jsonArray
          .map { it.jsonObject }
          .map { writer ->
            ServeIssueReport.Locator(
              repository = writer["repository"]!!.jsonPrimitive.content,
              system = writer["system"]!!.jsonPrimitive.content,
              componentId = writer["componentId"]!!.jsonPrimitive.content,
              previewId = writer["previewId"]!!.jsonPrimitive.content,
              referenceId = writer["referenceId"]!!.jsonPrimitive.content,
              variant = writer["variant"]!!.jsonPrimitive.content,
              overrides =
                writer["overrides"]!!.jsonObject.entries.associate {
                  it.key to it.value.jsonPrimitive.content
                },
            )
          }
      val body = case["body"]!!.jsonPrimitive.content
      assertEquals(body, locators.joinToString("") { ServeIssueReport.locatorBlock(it) }, name)
      assertEquals(locators, ServeIssueReport.locatorsFromBody(body), name)
      // The single-locator reader keeps its old answer: the first block a body carries.
      assertEquals(locators.first(), ServeIssueReport.locatorFromBody(body), name)
    }
  }

  @Test
  fun `a bounds rectangle is refused unless it names the plane v1 settled on`() {
    // D1: both tag-index producers publish render pixels and the canonical-plane transform belongs
    // to the comparison. A rectangle carrying any other space would be compared against a baseline
    // measured somewhere else, which is how an element that never moved reports as `moved`.
    val bounds = ServeIssueReport.Bounds(x = 18, y = 18, width = 24, height = 24)
    assertEquals(
      """{"height":24,"space":"render-pixels","width":24,"x":18,"y":18}""",
      ServeIssueReport.canonicalBounds(bounds),
    )
    val block =
      ServeIssueReport.locatorBlock(
        ServeIssueReport.Locator(
          repository = "yschimke/m3-catalog",
          system = "m3-catalog",
          componentId = "IconButton/Tonal",
          previewId = "iconbutton-tonal__ideal__default__light",
          referenceId = "iconbutton-tonal-figma",
          variant = "ideal/default/light",
          overrides = emptyMap(),
          element = "glyph",
          bounds = bounds,
        )
      )
    assertEquals(bounds, ServeIssueReport.locatorFromBody(block)?.bounds)
    assertEquals("glyph", ServeIssueReport.locatorFromBody(block)?.element)
    assertTrue("""element: "glyph"""" in block, "the tag is written as a JSON string")
    assertNull(
      ServeIssueReport.locatorFromBody(block.replace("render-pixels", "display-pixels")),
      "another plane is refused rather than stored as a guess",
    )
    assertNull(
      ServeIssueReport.locatorFromBody(
        block.replace(
          """{"height":24,"space":"render-pixels","width":24,"x":18,"y":18}""",
          """{"x":18,"y":18,"width":24,"height":24,"space":"render-pixels"}""",
        )
      ),
      "non-canonical key order is refused, as it is for overrides",
    )
  }

  @Test
  fun `a tag keeps its edge whitespace, and a field value is read the way both engines read it`() {
    // A tag index keys on the exact string, so `" glyph "` and `"glyph"` are different elements and
    // normalising one into the other would point an acceptance at the wrong one — or at none. The
    // quoting is what makes keeping it safe: both parsers trim the *line* value, and the spaces
    // live
    // inside the quotes where that trim cannot reach them.
    val locator =
      ServeIssueReport.locator(
        ServeIssueReport.Context(
          repo = "yschimke/m3-catalog",
          previewId = "iconbutton-tonal__ideal__default__light",
          system = "m3-catalog",
          componentId = "IconButton/Tonal",
          referenceId = "iconbutton-tonal-figma",
          element = "  glyph  ",
        )
      )
    assertEquals("  glyph  ", locator?.element)
    val block = ServeIssueReport.locatorBlock(locator!!)
    assertTrue("""element: "  glyph  """" in block, block)
    assertEquals(locator, ServeIssueReport.locatorFromBody(block), "the block round-trips")
    // And the line is read the same way here and in the producer: both trim both ends, so a body
    // hand-edited to pad *outside* the quotes still reads the same tag.
    val padded = block.replace("""element: "  glyph  """", """element: "  glyph  "  """)
    assertEquals("  glyph  ", ServeIssueReport.locatorFromBody(padded)?.element)
  }

  @Test
  fun `a tag cannot become syntax, however it is spelled`() {
    // A `testTag` is arbitrary text and the block is line-oriented, so a bare value carrying a
    // newline would not stay one field: `row\nrevision: injected` would read back as an element
    // plus a revision nobody wrote, and a fence delimiter inside a tag could end the block early
    // and drop the whole issue from the index. JSON quoting is what makes the value inert.
    val locator =
      ServeIssueReport.locator(
        ServeIssueReport.Context(
          repo = "yschimke/m3-catalog",
          previewId = "iconbutton-tonal__ideal__default__light",
          system = "m3-catalog",
          componentId = "IconButton/Tonal",
          referenceId = "iconbutton-tonal-figma",
          element = "row\nrevision: injected",
        )
      )!!
    val block = ServeIssueReport.locatorBlock(locator)
    assertTrue("""element: "row\nrevision: injected"""" in block, block)
    val read = ServeIssueReport.locatorFromBody(block)
    assertEquals("row\nrevision: injected", read?.element)
    assertNull(read?.revision, "the injected line is part of the tag, not a field of its own")
    // A bare tag is refused rather than read as syntax.
    assertNull(
      ServeIssueReport.locatorFromBody(block.replace(""""row\nrevision: injected"""", "row"))
    )
  }

  @Test
  fun `an invalid rectangle cannot be constructed, let alone written into a report`() {
    // The writer must not be able to emit a rectangle its own producer refuses: the reporter would
    // file a body that looks right and the whole issue would drop out of the index when the
    // workflow next ran. Batch 03's drag selection starts in display pixels, so the missed
    // conversion is a real path — and this is where it stops, at construction.
    assertFailsWith<IllegalArgumentException> {
      ServeIssueReport.Bounds(x = 0, y = 0, width = 24, height = 24, space = "display-pixels")
    }
    // A negative origin is NOT invalid: a tagged node can extend above or left of the render root,
    // and both tag-index producers publish signed coordinates for exactly that. Refusing it would
    // leave batch 03 unable to record the bounds the index handed it.
    assertEquals(-4, ServeIssueReport.Bounds(x = -4, y = -2, width = 24, height = 24).x)
    assertFailsWith<IllegalArgumentException> {
      ServeIssueReport.Bounds(x = 0, y = 0, width = 0, height = 24)
    }
  }

  @Test
  fun `the selection placeholder occupies a whole line and vanishes without a trace`() {
    // The substitution has to reproduce the block this writer emits on its own when nothing was
    // selected — otherwise every unselected report filed through the page differs from the format's
    // own baseline, and a blank line left inside the fence makes the producer's line parser read a
    // field short.
    val locator = fixtureLocator()
    val template = ServeIssueReport.locatorBlock(locator, selectionPlaceholder = true)
    assertTrue("${ServeIssueReport.SELECTION_PLACEHOLDER}\n" in template, template)
    assertEquals(
      ServeIssueReport.locatorBlock(locator),
      template.replace("${ServeIssueReport.SELECTION_PLACEHOLDER}\n", ""),
    )
  }

  @Test
  fun `substituting the placeholder yields exactly what the writer would have emitted`() {
    // The cross-engine contract, stated from this side. `cli/serve-web`'s `report/locator.ts`
    // produces those two lines in the browser, and `reportLocator.test.ts` pins it to the same
    // shared fixture — so what the page files and what this writer would have written are the same
    // bytes, which is what makes a filed report and a server-composed one comparable.
    val locator = fixtureLocator()
    val selected =
      locator.copy(
        element = "glyph",
        bounds = ServeIssueReport.Bounds(x = 18, y = 18, width = 24, height = 24),
      )
    val lines =
      "element: ${ServeIssueReport.canonicalElement("glyph")}\n" +
        "bounds: ${ServeIssueReport.canonicalBounds(selected.bounds!!)}\n"
    assertEquals(
      ServeIssueReport.locatorBlock(selected),
      ServeIssueReport.locatorBlock(locator, selectionPlaceholder = true)
        .replace("${ServeIssueReport.SELECTION_PLACEHOLDER}\n", lines),
    )
    // …and the result parses back to the selection it names, in both directions.
    assertEquals(
      selected,
      ServeIssueReport.locatorFromBody(ServeIssueReport.locatorBlock(selected)),
    )
  }

  @Test
  fun `only the template carries the placeholder`() {
    // A body filed with JS off must never carry a token nothing will substitute — it would reach
    // GitHub verbatim and the producer would index `{{selection}}` as a field.
    val ctx =
      ServeIssueReport.Context(
        repo = "yschimke/m3-catalog",
        previewId = "iconbutton-tonal",
        system = "m3-catalog",
        componentId = "IconButton/Tonal",
        referenceId = "iconbutton-tonal-figma",
      )
    assertFalse(ServeIssueReport.SELECTION_PLACEHOLDER in ServeIssueReport.body(ctx))
    assertTrue(
      ServeIssueReport.SELECTION_PLACEHOLDER in
        ServeIssueReport.body(ctx, renderPlaceholder = true, selectionPlaceholder = true)
    )
  }

  private fun fixtureLocator(): ServeIssueReport.Locator =
    ServeIssueReport.Locator(
      repository = "yschimke/m3-catalog",
      system = "m3-catalog",
      componentId = "IconButton/Tonal",
      previewId = "iconbutton-tonal__ideal__default__light",
      referenceId = "iconbutton-tonal-figma",
      variant = "ideal/default/light",
      overrides = emptyMap(),
      revision = "yschimke/m3-catalog@design-artifacts/m3-catalog",
    )

  private fun boundsOf(writer: JsonObject): ServeIssueReport.Bounds =
    ServeIssueReport.Bounds(
      x = writer["x"]!!.jsonPrimitive.int,
      y = writer["y"]!!.jsonPrimitive.int,
      width = writer["width"]!!.jsonPrimitive.int,
      height = writer["height"]!!.jsonPrimitive.int,
      space = writer["space"]!!.jsonPrimitive.content,
    )
}
