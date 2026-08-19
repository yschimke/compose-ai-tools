package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeBugReportTest {

  private val server =
    ServeBugReport.Server(
      version = "1.9.0",
      public = true,
      uptimeSeconds = 3 * 3600 + 12 * 60,
      java = "17.0.11 (Eclipse Adoptium)",
      os = "Linux 6.18.5 (amd64)",
    )

  private val page =
    ServeBugReport.Page(
      path = "/jetnews/p/Article__dark",
      url = "https://preview.coo.ee/jetnews/p/Article__dark",
      system = "jetnews",
      previewId = "Article__dark",
      catalog = "yschimke/compose-samples@design-artifacts/jetnews",
      catalogToolVersion = "0.19.37",
      trust = "verified",
      renderLane = "live daemon",
      renderUrl = "https://preview.coo.ee/jetnews/render/Article__dark.png",
      publicRender = true,
    )

  @Test
  fun `a server bug is always filed against the repo that ships the server`() {
    // The whole point of the split from ServeIssueReport: a catalog's repo cannot fix the server.
    assertEquals("yschimke/compose-ai-tools", ServeBugReport.REPO)
    assertEquals("https://github.com/yschimke/compose-ai-tools/issues/new", ServeBugReport.action())
    assertEquals("ui-report,bug,daemon", ServeBugReport.LABELS)
  }

  @Test
  fun `the body carries the deployment facts a triager would otherwise have to ask for`() {
    val body = ServeBugReport.body(server, page)
    assertTrue(body.contains("### What went wrong"), body)
    assertTrue(body.contains("| compose-preview | `1.9.0` |"), body)
    assertTrue(body.contains("| Mode | public (open) |"), body)
    assertTrue(body.contains("| Uptime | 3h 12m |"), body)
    assertTrue(body.contains("| Server JVM | `17.0.11 (Eclipse Adoptium)` |"), body)
    assertTrue(body.contains("| Server OS | `Linux 6.18.5 (amd64)` |"), body)
    assertTrue(body.contains("| Design system | `jetnews` |"), body)
    assertTrue(body.contains("| Preview | `Article__dark` |"), body)
    assertTrue(
      body.contains("| Catalog | `yschimke/compose-samples@design-artifacts/jetnews` |"),
      body,
    )
    assertTrue(body.contains("| Catalog rendered by | compose-ai-tools 0.19.37 |"), body)
    assertTrue(body.contains("| Render lane | live daemon |"), body)
  }

  @Test
  fun `a page section with nothing in it is omitted rather than filled with unknowns`() {
    // Pressing the affordance on the front door: there is no catalog, no preview and no render
    // lane, and rows saying so would be noise a reporter has to scroll past.
    val body = ServeBugReport.body(server, ServeBugReport.Page())
    assertFalse(body.contains("### Page"), body)
    assertTrue(body.contains("### Server"), body)
  }

  @Test
  fun `the page row links the URL but reads as the path`() {
    val body = ServeBugReport.body(server, page)
    assertTrue(
      body.contains(
        "| Page | [`/jetnews/p/Article__dark`](https://preview.coo.ee/jetnews/p/Article__dark) |"
      ),
      body,
    )
  }

  @Test
  fun `a publicly reachable render is embedded, a local one is only linked`() {
    val embedded = ServeBugReport.body(server, page)
    assertTrue(embedded.contains("![render](https://preview.coo.ee/"))
    assertTrue(embedded.contains("Camo proxies the source URL"), embedded)
    assertTrue(embedded.contains("does not make a versioned snapshot"), embedded)

    val local =
      page.copy(
        url = "http://127.0.0.1:8080/p/Article__dark",
        renderUrl = "http://127.0.0.1:8080/render/Article__dark.png",
      )
    val body = ServeBugReport.body(server, local)
    assertFalse(body.contains("![render]"), body)
    assertTrue(body.contains("[PNG at these settings](http://127.0.0.1:8080/"), body)
  }

  @Test
  fun `a token-gated render is not embedded even from a public hostname`() {
    // The body strips the token, and the lane 404s a tokenless request — so an embed would be a
    // broken image in every filed issue no matter how reachable the host is.
    val gated = page.copy(publicRender = false)
    val body = ServeBugReport.body(server, gated)
    assertFalse(body.contains("![render]"), body)
  }

  @Test
  fun `session tokens never reach the body`() {
    val gated =
      page.copy(
        url = "https://preview.coo.ee/jetnews/p/Article__dark?token=s3cret&uiMode=dark",
        renderUrl = "https://preview.coo.ee/jetnews/render/Article__dark.png?token=s3cret",
      )
    val body = ServeBugReport.body(server, gated)
    assertFalse(body.contains("s3cret"), body)
    // The rest of the query survives — it is what makes the link reproduce what the reporter saw.
    assertTrue(body.contains("uiMode=dark"), body)
  }

  @Test
  fun `unhealthy catalogs and recent failures ride along, fenced so text cannot shear the body`() {
    val noisy =
      server.copy(
        unhealthyCatalogs = listOf("`wear-m3`: failed — bundle signature invalid"),
        recentFailures = listOf("12:04  wear-m3: boom ``` and | a pipe"),
      )
    val body = ServeBugReport.body(noisy, page)
    assertTrue(body.contains("### Catalogs not loaded"), body)
    assertTrue(body.contains("`wear-m3`: failed — bundle signature invalid"), body)
    assertTrue(body.substringAfter("### Catalogs not loaded").startsWith("\n\n```"), body)
    assertTrue(body.contains("### Recent failures"), body)
    // A fence marker inside the failure text would close the block early and let the rest render.
    assertFalse(body.substringAfter("### Recent failures").contains("boom ```"), body)
    assertTrue(body.contains("boom ''' and | a pipe"), body)
  }

  @Test
  fun `table syntax in a diagnostic value cannot shear the row or escape its code span`() {
    // Almost every value here is text this server did not write — a degradation detail, a
    // catalog's trust string, a load error. A `|` splits the row into extra columns and a backtick
    // closes the code span, so a report about a broken catalog would arrive visibly mangled.
    val hostile =
      page.copy(
        system = "we|ird",
        trust = "branch:owner/repo | forged",
        renderLane = "live `daemon`",
        degradations = listOf("broken — a\\b | c"),
      )
    val body = ServeBugReport.body(server, hostile)
    assertTrue(body.contains("| Design system | `we\\|ird` |"), body)
    assertTrue(body.contains("| Trust | branch:owner/repo \\| forged |"), body)
    assertTrue(body.contains("| Render lane | live \\`daemon\\` |"), body)
    // Backslash escaped FIRST, or it would double the escapes added after it.
    assertTrue(body.contains("| Degraded | broken — a\\\\b \\| c |"), body)
  }

  @Test
  fun `the JVM and OS rows say whose they are`() {
    // A project whose `daemon-launch.json` names a javaLauncher renders on THAT JDK. Calling this
    // "Java" would file a render failure under the wrong runtime.
    val body = ServeBugReport.body(server, page)
    assertTrue(body.contains("| Server JVM |"), body)
    assertTrue(body.contains("| Server OS |"), body)
    assertFalse(body.contains("| Java |"), body)
  }

  @Test
  fun `query-mode catalog routes are not mistaken for a design system`() {
    // `/pages/foo?session=…` and `/parity?session=…` ARE catalog pages, but the catalog is named
    // by the query, not the first segment — reading `pages` as a system id invents one.
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/pages/shape"))
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/parity"))
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/usage/Button"))
    // …and the motion browser, which is the same shape: a catalog page at a constant top-level
    // segment, plus the capture bytes one level under it.
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/motion"))
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/motion/switch-on.apng"))
    // The path-mode form still names its catalog, exactly as `/m3-catalog/parity` does.
    assertEquals(
      ServeBugReport.PageRef(system = "m3-catalog"),
      ServeBugReport.parsePath("/m3-catalog/motion"),
    )
  }

  @Test
  fun `the client placeholder is present only for the template the page script rewrites`() {
    assertTrue(
      ServeBugReport.body(server, page, clientPlaceholder = true)
        .contains(ServeBugReport.CLIENT_PLACEHOLDER)
    )
    // The visible copy must not show a literal {{client}} — with JS off that is what would be
    // filed.
    assertFalse(ServeBugReport.body(server, page).contains(ServeBugReport.CLIENT_PLACEHOLDER))
  }

  @Test
  fun `only a same-origin path is accepted as the page the visitor came from`() {
    assertEquals("/jetnews/p/Article", ServeBugReport.sanitizeFrom("/jetnews/p/Article"))
    assertEquals("/p/A?uiMode=dark", ServeBugReport.sanitizeFrom("/p/A?uiMode=dark"))
    // A protocol-relative URL is a different origin wearing a path's shape.
    assertNull(ServeBugReport.sanitizeFrom("//evil.example/phish"))
    assertNull(ServeBugReport.sanitizeFrom("https://evil.example/phish"))
    assertNull(ServeBugReport.sanitizeFrom("javascript:alert(1)"))
    assertNull(ServeBugReport.sanitizeFrom("p/relative"))
    assertNull(ServeBugReport.sanitizeFrom("/p/A#frag"))
    assertNull(ServeBugReport.sanitizeFrom("/p/A\nX-Injected: 1"))
    assertNull(ServeBugReport.sanitizeFrom("/" + "a".repeat(4096)))
    assertNull(ServeBugReport.sanitizeFrom(null))
    assertNull(ServeBugReport.sanitizeFrom("   "))
  }

  @Test
  fun `the token is stripped from the page path before it is quoted anywhere`() {
    assertEquals("/p/A?uiMode=dark", ServeBugReport.sanitizeFrom("/p/A?token=s3cret&uiMode=dark"))
    assertEquals("/p/A", ServeBugReport.sanitizeFrom("/p/A?token=s3cret"))
  }

  @Test
  fun `a served path says which system and preview it is showing`() {
    assertEquals(
      ServeBugReport.PageRef("jetnews", "Article__dark"),
      ServeBugReport.parsePath("/jetnews/p/Article__dark"),
    )
    assertEquals(
      ServeBugReport.PageRef("jetnews", "Article__dark"),
      ServeBugReport.parsePath("/jetnews/compare/Article__dark?reference=x"),
    )
    // The rooted single-session form has no system prefix.
    assertEquals(
      ServeBugReport.PageRef(previewSegment = "Article__dark"),
      ServeBugReport.parsePath("/p/Article__dark"),
    )
    // A catalog landing names the system and no preview.
    assertEquals(ServeBugReport.PageRef("jetnews"), ServeBugReport.parsePath("/jetnews/"))
    // Any other page of a system keeps the system.
    assertEquals(ServeBugReport.PageRef("jetnews"), ServeBugReport.parsePath("/jetnews/parity"))
    // The box's own pages belong to no system.
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/status"))
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath("/"))
    assertEquals(ServeBugReport.PageRef(), ServeBugReport.parsePath(null))
  }

  @Test
  fun `an escaped preview id is left encoded for the caller to match, never decoded here`() {
    // Decoding would turn a legitimate %2B into a space and %2F into a separator — the exact bug
    // the usage route documents.
    assertEquals(
      ServeBugReport.PageRef("jetnews", "A%2Bb%2Fc"),
      ServeBugReport.parsePath("/jetnews/p/A%2Bb%2Fc"),
    )
  }

  @Test
  fun `uptime reads in the two units that matter`() {
    assertEquals("45s", ServeBugReport.duration(45))
    assertEquals("12m", ServeBugReport.duration(12 * 60))
    assertEquals("3h", ServeBugReport.duration(3 * 3600))
    assertEquals("3h 12m", ServeBugReport.duration(3 * 3600 + 12 * 60))
    assertEquals("2d", ServeBugReport.duration(2 * 86400))
    assertEquals("2d 5h", ServeBugReport.duration(2 * 86400 + 5 * 3600))
  }

  @Test
  fun `the browser block is the same two-column table the server sections use`() {
    val block = ServeBugReport.clientBlock(listOf("Viewport" to "1280×800 CSS px"))
    assertTrue(block.startsWith("### Browser"), block)
    assertTrue(block.contains("| Viewport | 1280×800 CSS px |"), block)
    // Nothing to say ⇒ nothing emitted, so the report has no empty heading.
    assertEquals("", ServeBugReport.clientBlock(emptyList()))
  }
}
