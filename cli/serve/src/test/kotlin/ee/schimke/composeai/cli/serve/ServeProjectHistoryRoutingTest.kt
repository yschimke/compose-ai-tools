package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The project-mode render-history surface, end to end over HTTP: the viewer page carries the
 * locally-computed timeline inline, and each of its entries resolves to real PNG bytes served out
 * of the repository by content sha.
 *
 * Git is faked at the [GitRunner] boundary, so this is about the wiring — that the payload reaches
 * the page, that the lane exists and is gated, and that the response is an image — rather than
 * about the history rules ([ServeProjectHistoryTest], [PreviewHistoryTest]).
 */
class ServeProjectHistoryRoutingTest {

  private val previewId = "com.example.Red"
  private val sha = "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80"
  private val newer = "a".repeat(40)
  private val older = "b".repeat(40)

  private fun png(rgb: Int): ByteArray {
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, rgb)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  private val renders = mapOf(newer to png(0xFFFF0000.toInt()), older to png(0xFF0000FF.toInt()))

  private val baselines =
    """{"$previewId": {"module": "samples:demo", "renderBasename": "Red.png"}}"""

  /** `git log --format=%x01%H%x1f%aI%x1f%s --raw` over the delivery branch — two publishes. */
  private val log =
    listOf(
        "\u0001$sha\u001F2026-05-22T11:08:37+00:00\u001FUpdate preview baselines from 57ac24f3",
        ":100644 100644 ${"0".repeat(40)} $newer M\trenders/samples:demo/Red.png",
        "\u00018b9f6f2bc953756edcb13963e09cd57c54866570\u001F2026-05-07T08:34:51+00:00" +
          "\u001FUpdate preview baselines from cf69a4a0",
        ":100644 100644 ${"0".repeat(40)} $older M\trenders/samples:demo/Red.png",
      )
      .joinToString("\n")

  private val git = GitRunner { _, args ->
    when {
      args.firstOrNull() == "rev-parse" ->
        if (args.last() == "compose-preview/main^{commit}") GitResult(0, sha) else GitResult(1, "")
      args.firstOrNull() == "show" && args.last() == "$sha:baselines.json" ->
        GitResult(0, baselines)
      args.contains("log") -> GitResult(0, log)
      else -> GitResult(1, "")
    }
  }

  private val history =
    ServeProjectHistory(
      repoRoot = File("."),
      git = git,
      readBlobBytes = { _, blob -> renders[blob] },
    )

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    val dir = Files.createTempDirectory("project-history").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png(0xFF00FF00.toInt()))
    registry.register(
      "demo",
      host = ServeBundleHost(dir, label = "demo", title = "Demo"),
      pinned = true,
    )
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = true,
        projectHistory = history,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun get(path: String): Triple<Int, String, String?> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return Triple(r.code, r.body.string(), r.header("Content-Type"))
    }
  }

  private fun getBytes(path: String): Triple<Int, ByteArray, String?> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return Triple(r.code, r.body.bytes(), r.header("Content-Type"))
    }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `the viewer inlines the local timeline and links at this server`() {
    val (code, body, _) = get("/demo/p/$previewId")
    assertEquals(200, code)
    assertTrue(body.contains("id=\"cp-history-data\""), "expected an inline history payload")
    assertTrue(body.contains(newer), "expected the newest version's blob sha in the payload")
    assertTrue(
      body.contains("data-history-blob-url=\"/demo/history/render/{blob}.png"),
      "expected the entries to address this server's own lane, under the session's path prefix",
    )
    // The delivery-branch pair belongs to a hosted catalog and must not appear here: there is no
    // repo to fetch a manifest from, and a half-wired strip would fetch nothing forever.
    assertFalse(body.contains("data-history-url="), "local mode must not advertise a manifest URL")
    assertFalse(body.contains("data-history-repo="), "local mode has no delivery repo")
  }

  @Test
  fun `an entry resolves to the render bytes for that version`() {
    val (code, bytes, contentType) = getBytes("/history/render/$older.png")
    assertEquals(200, code)
    assertTrue(bytes.contentEquals(renders[older]), "expected the older version's exact bytes")
    assertTrue(
      contentType.orEmpty().startsWith("image/png"),
      "served as an image, got $contentType",
    )
    // Both URL forms, since the viewer links relative to whichever prefix it was served under.
    assertEquals(200, getBytes("/demo/history/render/$newer.png").first)
  }

  @Test
  fun `a blob the timeline does not name is not served`() {
    assertEquals(404, getBytes("/history/render/${"d".repeat(40)}.png").first)
    assertEquals(404, getBytes("/history/render/nonsense.png").first)
  }

  @Test
  fun `the lane does not exist without a project history`() {
    val bareRegistry = ServeSessionRegistry(open = { null })
    val dir = Files.createTempDirectory("no-history").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png(0xFF00FF00.toInt()))
    bareRegistry.register(
      "demo",
      host = ServeBundleHost(dir, label = "demo", title = "Demo"),
      pinned = true,
    )
    val bare =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = bareRegistry,
          defaultSessionId = "demo",
          isPublic = true,
        )
        .also { it.start() }
    try {
      val req =
        Request.Builder().url("http://127.0.0.1:${bare.port}/history/render/$newer.png").build()
      client.newCall(req).execute().use { assertEquals(404, it.code) }
      val page =
        Request.Builder().url("http://127.0.0.1:${bare.port}/demo/p/$previewId").build().let { r ->
          client.newCall(r).execute().use { it.body.string() }
        }
      assertFalse(page.contains("cp-history-data"), "no payload without a project history")
    } finally {
      bare.stop()
      bareRegistry.close()
    }
  }
}
