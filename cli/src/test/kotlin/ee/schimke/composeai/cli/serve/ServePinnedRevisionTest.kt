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

  private fun start(): ServeHttpServer {
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { name, host -> registry.register(name, host = host, pinned = true) },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
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
  fun `a pinned viewer turns off every lane that would render today's code`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId?at=$oldCommit")

    assertTrue(page.contains("data-pinned-at=\"$oldCommit\""), page)
    assertTrue(page.contains("data-can-render-overrides=\"false\""), page)
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
