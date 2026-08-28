package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The spec lane's **second source** — the counterpart component's render in the `compareWith`
 * sibling (issue #4621) — as it arrives over real HTTP, on the two kinds of box where a path built
 * in the abstract is not a URL that resolves.
 *
 * Both cases are about REACHABILITY, which is the whole premise of the source: the sibling is
 * offered rather than baked precisely because it is served here, so a button pointing at a render
 * this caller cannot fetch is worse than no button — the lane paints "the design spec could not be
 * loaded" over a comparison that was never available.
 *
 * - a **token-gated** server (every `serve` that is not `--public`) gates `/render/` like every
 *   other route, so the sibling's raster needs the same credential the page's own render carries;
 * - a **top-level site** ([ServeSites]) answers a neighbour's `/{system}/…` with its own 404 by
 *   design, so on that hostname the pairing resolves and the render still does not.
 */
class ServeSpecLaneParallelSourceTest {

  private val siteHost = "m3.example.test"
  private val token = "t0ken"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /**
   * A published catalog: one baked preview carrying a `componentId`, plus — when [compareWith] is
   * set — the pairing halves the lane needs, and a design reference so the lane exists at all.
   */
  private fun catalog(
    label: String,
    previewId: String,
    componentId: String,
    compareWith: String? = null,
    parallel: String? = null,
    reference: Boolean = false,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("parallel-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText("""{"$previewId":{"componentId":"$componentId"}}""")
    if (reference) {
      File(dir, ServeDesignReferenceStore.DIRECTORY).mkdirs()
      File(dir, "${ServeDesignReferenceStore.DIRECTORY}/$previewId-figma.png").writeBytes(png())
      File(dir, "${ServeDesignReferenceStore.DIRECTORY}/${ServeDesignReferenceStore.INDEX_FILE}")
        .writeText(
          """
          {"schema":"${DesignReferenceManifest.SCHEMA}","references":[
            {"id":"$previewId-figma","previewId":"$previewId","label":"Filled button",
             "source":{"provider":"figma"},
             "raster":{"path":"references/$previewId-figma.png"}}]}
          """
            .trimIndent()
        )
    }
    return ServeBundleHost(
      dir,
      label = label,
      title = label,
      compareWithSystem = compareWith,
      parallelByComponentId = parallel?.let { mapOf(componentId to it) } ?: emptyMap(),
    )
  }

  private val registry = ServeSessionRegistry(open = { null })

  private fun newServer(
    isPublic: Boolean,
    sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
  ): ServeHttpServer {
    registry.register(
      "compose-m3",
      host =
        catalog(
          "compose-m3",
          previewId = "button-filled",
          componentId = "Button/Filled",
          compareWith = "wear-m3",
          parallel = "Button/Filled",
          reference = true,
        ),
      pinned = true,
    )
    registry.register(
      "wear-m3",
      host = catalog("wear-m3", previewId = "chip-filled", componentId = "Button/Filled"),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "compose-m3",
        isPublic = isPublic,
        catalogSessions = listOf("compose-m3", "wear-m3"),
        sites = sites,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(server: ServeHttpServer, path: String, host: String? = null): Pair<Int, String> {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$path")
    if (host != null) builder.header("Host", host)
    client.newCall(builder.build()).execute().use { response ->
      return response.code to response.body.string()
    }
  }

  /** The `data-spec-src` of the picker's `parallel` button, or null when it is not offered. */
  private fun parallelSrc(html: String): String? =
    Regex("data-cp-spec-source=\"parallel\" data-spec-src=\"([^\"]*)\"")
      .find(html)
      ?.groupValues
      ?.get(1)

  @Test
  fun `the sibling's raster carries the page's own access token`() {
    val server = newServer(isPublic = false)
    try {
      val (code, html) = get(server, "/compose-m3/p/button-filled?token=$token")
      assertEquals(200, code)
      val src = parallelSrc(html)
      assertTrue(src != null, "the pairing resolves into a second source: $html")
      // The point: `/render/` is `rejectBadToken`-gated like every other route, so the bare path
      // this used to emit was answered with the gate's own 404 on every server that is not
      // `--public` — i.e. on every local `serve`.
      assertEquals("/wear-m3/render/chip-filled.png?token=$token", src)
      // …and the URL it names really does answer, which is the assertion the string cannot make.
      assertEquals(200, get(server, src!!).first)
      assertEquals(404, get(server, src.substringBefore("?")).first, "the gate is real")
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a public server still offers the bare path`() {
    val server = newServer(isPublic = true)
    try {
      val (_, html) = get(server, "/compose-m3/p/button-filled")
      assertEquals("/wear-m3/render/chip-filled.png", parallelSrc(html))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a top-level site offers no sibling it would 404`() {
    val server =
      newServer(isPublic = true, sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")))
    try {
      val (code, html) = get(server, "/p/button-filled", host = siteHost)
      assertEquals(200, code)
      // The lane itself is untouched — the kit reference is this catalog's own and stays reachable.
      assertTrue(html.contains("id=\"cp-spec-lane\""), "the lane is still offered")
      assertFalse(
        html.contains("data-cp-spec-source"),
        "but the sibling is not a source here: $html",
      )
      // Because this is what the neighbour's render answers on this hostname.
      assertEquals(404, get(server, "/wear-m3/render/chip-filled.png", host = siteHost).first)
    } finally {
      server.stop()
    }
  }
}
