package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Historical permalinks, end to end (issue #3723): a catalog loaded from a delivery branch, then
 * served both at its tip and pinned (`?at=<sha>`) to an older publish.
 *
 * The delivery branch is stubbed at the store's fetch seam, so the whole path is exercised — the
 * commit feed that supplies the revision list, the branch-path bookkeeping that lets an id be
 * resolved at another commit, and the HTTP lanes — without a network or a repository.
 */
class ServePinnedRevisionTest {

  private val system = "compose-m3"
  private val branch = "design-artifacts/compose-m3"
  private val repo = "yschimke/compose-ai-tools"
  private val previewId = "button-filled__ideal__default__dark"
  private val referenceId = "button-figma"
  private val oldCommit = "1111111111111111111111111111111111111111"
  private val newCommit = "2222222222222222222222222222222222222222"

  private val currentRender = png(1)
  private val historicalRender = png(2)
  private val currentReference = png(3)
  private val historicalReference = png(4)

  private val catalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark",
         "overrides":[{"key":"enabled","type":"bool","label":"enabled",
           "default":{"kind":"bool","value":true},
           "current":{"kind":"bool","value":true}}]}]}]}
    """
      .trimIndent()

  private val referencesJson =
    """
    {"schema":"compose-preview-references/v1","references":[{
       "id":"button-figma","previewId":"$previewId","label":"Figma button",
       "raster":{"path":"references/button.png","width":2,"height":2},
       "source":{"provider":"figma"}}]}
    """
      .trimIndent()

  private val previewIndexJson =
    """
    {"schema":"compose-preview-revision-index/v1","current":["$previewId"],"revisions":[
      {"commit":"$oldCommit","previews":["$previewId"]}]}
    """
      .trimIndent()

  private val feed =
    """
    <feed>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/$newCommit</id>
        <updated>2026-08-13T09:42:57Z</updated>
        <content type="html">regenerate compose-m3 catalog (2026-08-13, 0b0c2063)</content>
      </entry>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/$oldCommit</id>
        <updated>2026-08-01T10:00:00Z</updated>
        <content type="html">regenerate compose-m3 catalog (2026-08-01, b34eff53)</content>
      </entry>
    </feed>
    """
      .trimIndent()

  /**
   * The stubbed branch.
   *
   * `<newCommit>` — the feed's head — serves the current bytes, and the load reads it *by sha*:
   * resolving the tip first and fetching everything through it is what makes a load atomic, so the
   * branch-name base below is only the fallback for a feed that could not be read. `<oldCommit>`
   * serves the older bytes, and every other commit serves nothing, which is what a pin naming a
   * publish this branch never had looks like from here.
   */
  private val fetch: (String) -> ByteArray? = { url ->
    val tip = "https://raw.githubusercontent.com/$repo/$newCommit/"
    val byBranch = "https://raw.githubusercontent.com/$repo/$branch/"
    val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
    when (url) {
      ServeCatalogRevision.commitsFeedUrl(repo, branch) -> feed.encodeToByteArray()
      "${tip}catalog.json",
      "${byBranch}catalog.json" -> catalogJson.encodeToByteArray()
      "${tip}preview-index.json",
      "${byBranch}preview-index.json" -> previewIndexJson.encodeToByteArray()
      "${tip}references/index.json",
      "${byBranch}references/index.json" -> referencesJson.encodeToByteArray()
      "${tip}references/button.png",
      "${byBranch}references/button.png" -> currentReference
      "${tip}images/button-filled/ideal__default__dark.png",
      "${byBranch}images/button-filled/ideal__default__dark.png" -> currentRender
      "${old}references/button.png" -> historicalReference
      "${old}images/button-filled/ideal__default__dark.png" -> historicalRender
      else -> null
    }
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()
  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
  }

  private fun start(): ServeHttpServer = startServer(fetch)

  /** A server over a branch stub that overlays [branch] on the default one. */
  private fun startWith(branch: (String) -> ByteArray?): Int = startServer(branch).port

  private fun startServer(branchFetch: (String) -> ByteArray?): ServeHttpServer {
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { name, host -> registry.register(name, host = host, pinned = true) },
        trust = { TrustStore.EMPTY },
        fetch = branchFetch,
      )
    assertTrue(store.load(system) is ServeCatalogStore.Result.Ok)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = system,
        isPublic = true,
        catalogSessions = listOf(system),
      )
      .also {
        it.start()
        server = it
      }
  }

  @Test
  fun `a pinned render serves the bytes that publish had, and the tip still serves today's`() {
    val port = start().port

    assertContentEquals(
      currentRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png"),
    )
    assertContentEquals(
      historicalRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit"),
    )
    // Both panels of a comparison pin, or the page would score one moment against another.
    assertContentEquals(
      currentReference,
      bytes("http://127.0.0.1:$port/$system/reference/$referenceId.png"),
    )
    assertContentEquals(
      historicalReference,
      bytes("http://127.0.0.1:$port/$system/reference/$referenceId.png?at=$oldCommit"),
    )
  }

  @Test
  fun `a pin the branch cannot answer 404s rather than falling back to the current bytes`() {
    val port = start().port
    val absent = "3333333333333333333333333333333333333333"

    val response = get("http://127.0.0.1:$port/$system/render/$previewId.png?at=$absent")

    assertEquals(404, response.first)
    // The failure that matters is the silent one: answering a permalink with today's render would
    // look like success to whoever followed the link.
    assertFalse(response.second.contentEquals(currentRender))
  }

  @Test
  fun `a ref-shaped pin is refused instead of quietly resolving to the tip`() {
    val port = start().port

    for (path in
      listOf(
        "/$system/render/$previewId.png?at=$branch",
        "/$system/reference/$referenceId.png?at=main",
        "/$system/p/$previewId?at=main",
        "/$system/compare/$previewId?at=main",
      )) {
      assertEquals(400, get("http://127.0.0.1:$port$path").first, path)
    }
  }

  @Test
  fun `a pinned page pins every asset it links and offers its way back`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/compare/$previewId?at=$oldCommit")

    assertTrue(page.contains("render/$previewId.png?at=$oldCommit"), page)
    assertTrue(page.contains("reference/$referenceId.png?at=$oldCommit"), page)
    assertTrue(page.contains("Pinned to catalog revision"), page)
    // The way back to the live catalog is part of the banner: a pinned page a visitor cannot leave
    // is a dead end rather than a permalink.
    assertTrue(page.contains("view current"), page)
  }

  /** The knob row for [key], so an assertion reads one control rather than a whole page. */
  private fun knobRow(page: String, key: String): String =
    page.lineSequence().first { it.contains("""data-knob-key="$key"""") }

  /**
   * A deep link's knob values seed the viewer's controls — except where the image beside them is
   * deliberately NOT the overridden render, which is both of this session's shapes.
   *
   * A pinned page answers with the historical baked artifact (`pinnedRenderQuerySuffix` strips
   * every override from its URL), and a static catalog that accepted `?fallback=baked` answers with
   * the published snapshot and names what it dropped. Seeding either would tick a box the pixels
   * never saw — and on a session with no override lane the control is *disabled*, so the visitor
   * cannot even correct it.
   */
  @Test
  fun `a page whose image ignores the override does not seed its controls`() {
    val port = start().port
    val base = "http://127.0.0.1:$port/$system/p/$previewId"

    // The declaration is `true` and each link asks for `false`, so an unseeded control stays
    // ticked.
    // Pinned: the picture is the older publish, whatever the link asks for.
    assertTrue(
      knobRow(text("$base?at=$oldCommit&knob.enabled=false"), "enabled").contains(" checked"),
      "a pinned page seeded its controls from the request",
    )

    // Baked fallback on a catalog with no lane that could apply an override.
    assertTrue(
      knobRow(text("$base?knob.enabled=false&fallback=baked"), "enabled").contains(" checked"),
      "an accepted baked fallback seeded its controls from the request",
    )
  }

  @Test
  fun `an unpinned page offers the branch's publishes as destinations`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId")

    assertTrue(page.contains("cp-revisions"), page)
    assertTrue(page.contains("at=$oldCommit"), page)
    // The tip is where the page already is, so its row links to the clean URL rather than pinning
    // the same bytes under a sha.
    assertFalse(page.contains("at=$newCommit"), page)
    assertTrue(page.contains("b34eff53"), page)
  }

  @Test
  fun `a generated preview index removes revisions that did not publish this preview`() {
    val tip = "https://raw.githubusercontent.com/$repo/$newCommit/"
    val withoutPreview =
      """
      {"schema":"compose-preview-revision-index/v1","current":["$previewId"],"revisions":[
        {"commit":"$oldCommit","previews":["some-other-preview"]}]}
      """
        .trimIndent()
        .encodeToByteArray()
    val port = startWith { url ->
      if (url == "${tip}preview-index.json") withoutPreview else fetch(url)
    }

    val page = text("http://127.0.0.1:$port/$system/p/$previewId")

    assertTrue(page.contains("cp-revisions"), page)
    assertFalse(page.contains("at=$oldCommit"), page)
  }

  @Test
  fun `a revision link on a token-free page starts its query, and resolves`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId")

    // A public server builds token-free links, so the page URL carries no query at all and the pin
    // has to *open* one. `&at=` there would fold the sha into the path — the shape that made every
    // revision in the menu a 404 rather than a permalink.
    assertTrue(page.contains("/p/$previewId?at=$oldCommit"), page)
    assertFalse(page.contains("/p/$previewId&at="), page)
    assertEquals(200, get("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit").first)
  }

  @Test
  fun `a daemon-produced lane is refused under a pin rather than answered from today`() {
    val port = start().port

    // The branch publishes one product per revision: the baked PNG. Everything else on this route
    // is made on demand from the catalog's current code, so a pin has nothing historical to serve —
    // and answering with today's export under a URL naming an old publish is the failure the whole
    // feature exists to prevent.
    for (suffix in listOf(".svg", ".slots", ".a11y", ".annotations", ".rc")) {
      val (code, _) = get("http://127.0.0.1:$port/$system/render/$previewId$suffix?at=$oldCommit")
      assertEquals(404, code, suffix)
    }
  }

  @Test
  fun `a pinned viewer offers no lane whose output the daemon would make now`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit")

    assertTrue(page.contains("data-pinned-at=\"$oldCommit\""), page)
    assertTrue(page.contains("data-can-render-overrides=\"false\""), page)
    // Generated outputs remain visible when the current component offers them, but are disabled;
    // their routes and direct links still go away because they have no historical bytes.
    assertFalse(page.contains("$previewId.svg"), page)
    // The spec lane is the opposite case and stays — a design reference is a published file, so it
    // has a real answer at that commit — but it must be *asked* for at that commit.
    if (page.contains("/reference/$referenceId.png")) {
      assertTrue(page.contains("/reference/$referenceId.png?at=$oldCommit"), page)
    }
  }

  @Test
  fun `revision links retain a selected theme while the pinned page reports the baked theme`() {
    // Deliberately contradict the path suffix: the catalog field is authoritative historical
    // metadata, while inferring from the id would report Night for pixels published as Day.
    val historicalCatalog = catalogJson.replace("\"theme\":\"dark\"", "\"theme\":\"light\"")
    val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
    val port = startWith { url ->
      if (url == "${old}catalog.json") historicalCatalog.encodeToByteArray() else fetch(url)
    }
    val provider = "ee.schimke.m3catalog.LightMediumContrastTheme"

    val current = text("http://127.0.0.1:$port/$system/p/$previewId?themeProvider=$provider")
    assertTrue(current.contains("themeProvider=$provider&amp;at=$oldCommit"), current)

    val pinned =
      text("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit&themeProvider=$provider")
    assertTrue(pinned.contains("Pinned revision — theme overrides are not applied"), pinned)
    assertTrue(pinned.contains("id=\"cp-theme-toggle-value\">Light</span>"), pinned)
    val ogImage =
      Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(pinned)?.groupValues?.get(1)
    assertTrue(ogImage?.contains("at=$oldCommit") == true, pinned)
    assertFalse(ogImage.contains("themeProvider"), pinned)
    assertFalse(pinned.contains("data-usage-src="), pinned)
    assertFalse(pinned.contains("try in playground"), pinned)
    // An id that still exists uses the same canonical name on Current and pinned pages.
    assertTrue(current.contains("Button Filled"), current)
    assertTrue(pinned.contains("Button Filled"), pinned)
  }

  @Test
  fun `a preview the catalog has since dropped still resolves at the revision that had it`() {
    // A preview id present at the older commit and gone from the tip — renamed, retired, or
    // reorganised since. It is exactly the case a permalink exists for (the link was made while it
    // existed) and exactly the one the tip's map cannot answer: that id is not in today's catalog
    // at all, so resolving through it is an unconditional 404 on an asset the commit really has.
    val retiredPath = "images/button-filled-legacy/ideal__default__dark.png"
    val retiredId = "button-filled-legacy__ideal__default__dark"
    val retiredCatalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Button/Filled","images":[{"path":"$retiredPath","theme":"dark"}]}]}
      """
        .trimIndent()
    val retired = png(9)
    val port = startWith { url ->
      val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
      when (url) {
        "${old}catalog.json" -> retiredCatalog.encodeToByteArray()
        "$old$retiredPath" -> retired
        else -> fetch(url)
      }
    }

    assertContentEquals(
      retired,
      bytes("http://127.0.0.1:$port/$system/render/$retiredId.png?at=$oldCommit"),
    )
    // …and the id is genuinely absent from the live catalog, so this is not the tip's map quietly
    // answering: without the pinned manifest the request above has nowhere to resolve.
    assertEquals(404, get("http://127.0.0.1:$port/$system/render/$retiredId.png").first)

    // The PAGE is what a person actually opened, and it has to resolve too — the session's preview
    // list is built from the tip, so a renamed-away id is not in it and the viewer used to 404 on
    // a permalink whose pixels this server could serve perfectly well.
    val page = text("http://127.0.0.1:$port/$system/p/$retiredId?at=$oldCommit")
    assertTrue(page.contains("data-preview-id=\"$retiredId\""), page)
    assertTrue(page.contains("Pinned to catalog revision"), page)
    // Named by the component that revision declared it under, rather than by the bare id.
    assertTrue(page.contains("Button Filled"), page)
    // Unpinned, the id is simply gone — a retired preview is not resurrected onto the live catalog.
    assertEquals(404, get("http://127.0.0.1:$port/$system/p/$retiredId").first)
  }

  @Test
  fun `a reference raster that moved between publishes resolves at its own revision`() {
    // A design reference carries its id and its raster path independently, so unlike a render the
    // id can survive a path change. The tip's map then points at a path that commit never had.
    val movedPath = "references/legacy/button.png"
    val movedManifest =
      """
      {"schema":"compose-preview-references/v1","references":[{
         "id":"$referenceId","previewId":"$previewId","label":"Figma button",
         "raster":{"path":"$movedPath","width":2,"height":2},
         "source":{"provider":"figma"}}]}
      """
        .trimIndent()
    val moved = png(8)
    val port = startWith { url ->
      val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
      when (url) {
        "${old}references/index.json" -> movedManifest.encodeToByteArray()
        "$old$movedPath" -> moved
        // The path the TIP knows this reference by does not exist at that commit.
        "${old}references/button.png" -> null
        else -> fetch(url)
      }
    }

    assertContentEquals(
      moved,
      bytes("http://127.0.0.1:$port/$system/reference/$referenceId.png?at=$oldCommit"),
    )
  }

  @Test
  fun `a render asked for to order is refused under a pin, not answered with the baked one`() {
    val port = start().port

    // These select a DIFFERENT product by query rather than by suffix: a full-page capture, another
    // player's raster, an overridden render. Answering any of them with the plain baked PNG would
    // be a 200 that silently ignores half the URL.
    for (query in
      listOf(
        "scroll=long",
        "rcPlayer=cmp-jvm",
        "fontScale=1.5",
        "device=pixel_8",
        "knob.size=xl",
      )) {
      val url = "http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit&$query"
      assertEquals(400, get(url).first, query)
    }
    // The bare pinned render is unaffected — refusing the combination is not refusing the pin.
    assertContentEquals(
      historicalRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit"),
    )
  }

  @Test
  fun `an id the pinned catalog does not list is not answered from the tip's paths`() {
    // The old commit publishes a catalog that lists ONLY the legacy component, while the path the
    // tip knows this preview by happens to resolve at that commit too. A readable manifest is the
    // authority on its own revision: it does not list this id, so the answer is nothing — not the
    // file sitting at today's path.
    val oldCatalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Legacy","images":[{"path":"images/legacy/ideal.png"}]}]}
      """
        .trimIndent()
    val port = startWith { url ->
      val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
      when (url) {
        "${old}catalog.json" -> oldCatalog.encodeToByteArray()
        else -> fetch(url)
      }
    }

    // …even though the tip's path for it does resolve at that commit (the default stub serves it).
    assertEquals(
      404,
      get("http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit").first,
    )
  }

  @Test
  fun `a pinned comparison draws no annotation layer from the current catalog`() {
    val port = start().port

    val pinned = text("http://127.0.0.1:$port/$system/compare/$previewId?at=$oldCommit")

    // Annotations describe the current catalog's layout, so over historical pixels they would
    // label today's bounds as that revision's spec. The controls go with the payload.
    assertFalse(pinned.contains("cp-annotations"), pinned)
    assertFalse(pinned.contains("cp-annotation-toggle"), pinned)
    assertFalse(pinned.contains("data-cp-annotation-kind"), pinned)
  }

  @Test
  fun `a load reads one commit rather than a moving branch`() {
    // CopyOnWriteArrayList, not a synchronized list: a catalog load keeps background threads
    // fetching (vectors, rc-compare) after it returns, so they are still appending while the
    // assertions below read. A synchronized list needs the caller to hold its monitor to iterate —
    // `any {}` and even `toList()` do not — and the miss is a ConcurrentModificationException that
    // shows up as a CI flake rather than on the run that wrote it.
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    startWith { url ->
      asked += url
      fetch(url)
    }

    // The feed is read first, and everything the load reads afterwards is addressed by the sha it
    // resolved — so a publish landing mid-load cannot leave the pages advertising one revision
    // while serving a mixture of two.
    val reads = asked.toList()
    assertEquals(ServeCatalogRevision.commitsFeedUrl(repo, branch), reads.first())
    assertTrue(
      reads.drop(1).all { it.startsWith("https://raw.githubusercontent.com/$repo/$newCommit/") },
      "read by branch name rather than by sha: $reads",
    )
  }

  @Test
  fun `a branch with no readable history still loads, by name`() {
    // CopyOnWriteArrayList, not a synchronized list: a catalog load keeps background threads
    // fetching (vectors, rc-compare) after it returns, so they are still appending while the
    // assertions below read. A synchronized list needs the caller to hold its monitor to iterate —
    // `any {}` and even `toList()` do not — and the miss is a ConcurrentModificationException that
    // shows up as a CI flake rather than on the run that wrote it.
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    val port = startWith { url ->
      asked += url
      if (url == ServeCatalogRevision.commitsFeedUrl(repo, branch)) null else fetch(url)
    }

    // No feed ⇒ no revision to pin the load to, so it reads the branch exactly as it did before
    // permalinks existed. Serving the catalog matters more than serving it atomically.
    assertTrue(
      asked.any { it == "https://raw.githubusercontent.com/$repo/$branch/catalog.json" },
      "did not fall back to the branch: ${asked.toList()}",
    )
    assertEquals(200, get("http://127.0.0.1:$port/$system/render/$previewId.png").first)
  }

  @Test
  fun `a pinned url the branch refuses is not asked about twice`() {
    val fetches = java.util.concurrent.atomic.AtomicInteger()
    val absent = "3333333333333333333333333333333333333333"
    val port = startWith { url ->
      if (url.startsWith("https://raw.githubusercontent.com/$repo/$absent/"))
        fetches.incrementAndGet()
      fetch(url)
    }

    repeat(4) {
      assertEquals(
        404,
        get("http://127.0.0.1:$port/$system/render/$previewId.png?at=$absent").first,
      )
    }

    // Two manifest reads for the commit (memoised by ServePinnedManifest) and one asset read that
    // came back empty (remembered as a miss). Without the negative cache each of the four requests
    // pays for the asset again — and a page of broken pinned images pays once per image.
    assertEquals(3, fetches.get())
  }

  @Test
  fun `a pinned render answers a HEAD probe, so a shared link still unfurls`() {
    val port = start().port
    val url = "http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit"

    val head = client.newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    // An unfurler probes an og:image before fetching it. Refusing dropped the preview card on
    // exactly the historical links this feature exists to share; the lane is admission-bounded now,
    // so the probe costs at most one permitted read and the GET behind it is served from cache.
    assertEquals(200, head)
  }

  @Test
  fun `a preview the pinned revision never had has no page either`() {
    // The mirror image of the retired case: an id today's catalog lists but that revision did not
    // publish — a preview ADDED since. Its render already 404s, so serving a page built from
    // today's metadata would wrap a broken image in a banner claiming the pixels cannot change.
    val olderCatalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Legacy","images":[{"path":"images/legacy/ideal.png"}]}]}
      """
        .trimIndent()
    val port = startWith { url ->
      val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
      if (url == "${old}catalog.json") olderCatalog.encodeToByteArray() else fetch(url)
    }

    val unavailable = get("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit")
    assertEquals(404, unavailable.first)
    val unavailablePage = unavailable.second.decodeToString()
    // A catalog publish can predate one preview. That is still an honest 404, but it must not
    // strand the visitor: the same revision control remains available to choose another publish,
    // and current is a clean URL rather than today's pixels served under the historical pin.
    assertTrue(unavailablePage.contains("Preview unavailable"), unavailablePage)
    assertTrue(unavailablePage.contains("was not published in catalog revision"), unavailablePage)
    assertTrue(unavailablePage.contains("class=\"cp-revisions\""), unavailablePage)
    assertTrue(unavailablePage.contains("/p/$previewId"), unavailablePage)
    assertTrue(unavailablePage.contains("at=$oldCommit"), unavailablePage)
    assertEquals(
      404,
      get("http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit").first,
    )
    // Unpinned it is an ordinary preview of the live catalog, untouched by any of this.
    assertEquals(200, get("http://127.0.0.1:$port/$system/p/$previewId").first)
  }

  @Test
  fun `a revision whose catalog cannot be read leaves the page to the live catalog`() {
    // "I could not ask that revision" is not "that revision says no". The page falls back to the
    // session's own preview rather than 404ing a link that may well be good.
    val port = startWith { url ->
      val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
      if (url == "${old}catalog.json") null else fetch(url)
    }

    assertEquals(200, get("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit").first)
  }

  /** The path-scoped feed URL for this preview's render — what the runs lane reads. */
  private val renderFeedUrl =
    ServeCatalogRevision.pathCommitsFeedUrl(
      repo,
      branch,
      "images/button-filled/ideal__default__dark.png",
    )

  /**
   * That feed, overlaid on the default branch stub, naming [commits] as the publishes that touched
   * the render.
   */
  private fun withRenderChanges(vararg commits: String): (String) -> ByteArray? = { url ->
    if (url == renderFeedUrl) {
      commits
        .joinToString("\n") { "<entry><id>tag:github.com,2008:Grit::Commit/$it</id></entry>" }
        .let { "<feed>$it</feed>".encodeToByteArray() }
    } else fetch(url)
  }

  @Test
  fun `the runs lane groups publishes that share their pixels`() {
    // Both publishes touched the render, so each is its own run.
    val port = startServer(withRenderChanges(newCommit, oldCommit)).port

    val body = text("http://127.0.0.1:$port/$system/api/render-runs/$previewId")

    assertTrue(body.contains("\"revisions\":2"), body)
    assertTrue(body.contains("\"head\":\"$newCommit\""), body)
    assertTrue(body.contains("\"head\":\"$oldCommit\""), body)
  }

  @Test
  fun `a publish that moved no pixel joins the run above it`() {
    // Only the OLDER publish changed the render, so the newer one republished identical bytes: one
    // run covering both, headed by the newest. This is the case the whole feature exists for — the
    // menu would otherwise offer two rows that open the same image.
    val port = startServer(withRenderChanges(oldCommit)).port

    val body = text("http://127.0.0.1:$port/$system/api/render-runs/$previewId")

    assertTrue(body.contains("\"head\":\"$newCommit\""), body)
    assertTrue(body.contains("\"commits\":2"), body)
    assertFalse(body.contains("\"head\":\"$oldCommit\""), body)
    // Closed by a real change rather than by the end of the window, so the count is exact.
    assertFalse(body.contains("\"open\":true"), body)
  }

  @Test
  fun `a run the window cuts off says so instead of claiming a count`() {
    // The only publish that touched this render predates both rows in the window, so the run's
    // length is a floor. Reporting it as an exact two would be a claim the branch never supported.
    val ancient = "4444444444444444444444444444444444444444"
    val port = startServer(withRenderChanges(ancient)).port

    val body = text("http://127.0.0.1:$port/$system/api/render-runs/$previewId")

    assertTrue(body.contains("\"commits\":2"), body)
    assertTrue(body.contains("\"open\":true"), body)
  }

  @Test
  fun `a feed that parses to nothing is a failure, not proof the render never changed`() {
    // A 200 carrying an HTML error page, a redirect, or a reshaped feed all parse down to zero
    // entries. A published render necessarily has at least the commit that added it, so zero never
    // describes a real one — and reporting it as an empty change set would tell the viewer, with
    // confidence, that every listed publish is pixel-identical.
    for (body in listOf("<feed></feed>", "<html>404</html>", "")) {
      val port = startServer { url ->
        if (url == renderFeedUrl) body.encodeToByteArray() else fetch(url)
      }
        .port

      assertEquals(
        404,
        get("http://127.0.0.1:$port/$system/api/render-runs/$previewId").first,
        body,
      )
      server?.stop()
    }
  }

  @Test
  fun `a branch that cannot be asked 404s rather than claiming nothing changed`() {
    // No path-scoped feed on the stub. An empty run list and "the branch did not answer" are
    // opposite claims, and drawing the first when we mean the second would label a dozen genuinely
    // different publishes as one unchanged stretch.
    val port = start().port

    assertEquals(
      404,
      get("http://127.0.0.1:$port/$system/api/render-runs/$previewId").first,
    )
    // An id this catalog never baked has no render path to ask about either.
    assertEquals(404, get("http://127.0.0.1:$port/$system/api/render-runs/nope").first)
  }

  @Test
  fun `a window we cannot bound draws no runs at all`() {
    // A branch that ships no `preview-index.json`. `availableRevisions` fails open there — right
    // for the menu, where an extra link that 404s beats hiding real history — so the window can
    // reach back past the preview's own creation. The path feed's creation commit then reads as a
    // boundary, and every row below it becomes a trailing run headed by a publish that has no
    // render: marked, counted as another distinct look, and asked for a thumbnail that cannot
    // exist. Without the inventory there is nothing to bound the window with, so say nothing.
    val port = startServer { url ->
      if (url.endsWith("/preview-index.json")) null
      else withRenderChanges(newCommit, oldCommit)(url)
    }
      .port

    assertEquals(
      404,
      get("http://127.0.0.1:$port/$system/api/render-runs/$previewId").first,
    )
  }

  @Test
  fun `the revision menu stamps every row with the sha the runs lane names`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId")

    // The join between the two halves. Without the stamp the client would have to parse `?at=` out
    // of each href, which the *current* row deliberately does not carry — so the one row that is
    // always a run head would be the one row that never got marked.
    assertTrue(page.contains("data-revision=\"$newCommit\""), page)
    assertTrue(page.contains("data-revision=\"$oldCommit\""), page)
    assertTrue(page.contains("<cp-revision-runs "), page)
    assertTrue(page.contains("/api/render-runs/$previewId"), page)
  }

  private fun get(url: String): Pair<Int, ByteArray> =
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      response.code to response.body.bytes()
    }

  private fun bytes(url: String): ByteArray {
    val (code, body) = get(url)
    assertEquals(200, code, url)
    return body
  }

  private fun text(url: String): String {
    val (code, body) = get(url)
    assertEquals(200, code, url)
    return body.decodeToString()
  }

  private fun tempRoot(): File =
    Files.createTempDirectory("serve-pinned").toFile().also { it.deleteOnExit() }

  /** A distinguishable 2×2 PNG per [seed], so "which version came back" is decidable. */
  private fun png(seed: Int): ByteArray =
    ByteArrayOutputStream()
      .also { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, seed * 0x3F3F3F)
        ImageIO.write(image, "png", out)
      }
      .toByteArray()
}
