package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeIssueReportTest {

  private val full =
    ServeIssueReport.Context(
      repo = "yschimke/compose-samples",
      previewId = "Article__dark",
      previewLabel = "Article",
      system = "jetnews",
      sourceUrl = "https://github.com/yschimke/compose-samples/blob/previews/app/Article.kt",
      catalog = "yschimke/compose-samples@design-artifacts/jetnews",
      toolVersion = "0.19.37",
      viewerUrl = "https://preview.coo.ee/jetnews/p/Article__dark",
      renderUrl = "https://preview.coo.ee/jetnews/render/Article__dark.png?uiMode=dark",
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
  fun `the title names the preview and the system it was served from`() {
    assertEquals("Preview issue: Article (jetnews)", ServeIssueReport.title(full))
    // No label recorded ⇒ the id, which is always present.
    assertEquals(
      "Preview issue: Article__dark",
      ServeIssueReport.title(ServeIssueReport.Context(repo = "o/r", previewId = "Article__dark")),
    )
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
    assertTrue(body.contains("Copy PNG"), "the durable paste path is still offered")
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
  }
}
