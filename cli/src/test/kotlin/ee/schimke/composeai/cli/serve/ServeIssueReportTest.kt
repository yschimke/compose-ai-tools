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
    assertTrue(
      body.contains(
        "[PNG at these settings](https://preview.coo.ee/jetnews/render/Article__dark.png?uiMode=dark)"
      ),
      body,
    )
    // The screenshot is asked for as a paste, because that lands the pixels on GitHub's own CDN
    // rather than leaving the evidence pointed at a URL that re-renders later.
    assertTrue(body.contains("Copy PNG"), "the body tells the reporter how to attach the render")
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
    assertTrue(tpl.contains("[PNG at these settings]({{render}})"), tpl)
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
