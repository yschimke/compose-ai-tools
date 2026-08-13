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
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
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
   * The stubbed branch. The tip serves the current bytes; `<oldCommit>` serves the older ones, and
   * every other commit serves nothing — which is what a pin naming a publish this branch never had
   * looks like from here.
   */
  private val fetch: (String) -> ByteArray? = { url ->
    val tip = "https://raw.githubusercontent.com/$repo/$branch/"
    val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
    when (url) {
      ServeCatalogRevision.commitsFeedUrl(repo, branch) -> feed.encodeToByteArray()
      "${tip}catalog.json" -> catalogJson.encodeToByteArray()
      "${tip}references/index.json" -> referencesJson.encodeToByteArray()
      "${tip}references/button.png" -> currentReference
      "${tip}images/button-filled/ideal__default__dark.png" -> currentRender
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
    // The SVG export is the one that looks static and isn't: it is produced per request, so the
    // toggle and the download link both go while the pin is in force.
    assertFalse(page.contains("cp-fmt-svg"), page)
    assertFalse(page.contains("$previewId.svg"), page)
    // The spec lane is the opposite case and stays — a design reference is a published file, so it
    // has a real answer at that commit — but it must be *asked* for at that commit.
    if (page.contains("/reference/$referenceId.png")) {
      assertTrue(page.contains("/reference/$referenceId.png?at=$oldCommit"), page)
    }
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

  private fun get(url: String): Pair<Int, ByteArray> =
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      response.code to (response.body?.bytes() ?: ByteArray(0))
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
