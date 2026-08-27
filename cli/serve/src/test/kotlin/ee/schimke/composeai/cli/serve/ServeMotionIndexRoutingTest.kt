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
 * The motion browser over real HTTP.
 *
 * A page fixture only proves that `ServeWeb` draws something when handed arguments the *test*
 * chooses; it says nothing about whether the handler ever hands them over. This is the half that
 * does — and it covers the two ways this route could have been wrong by construction:
 *
 * - `/motion` is a **sibling** of `/motion/<id>.apng`, so the index must not shadow the bytes (and
 *   the bytes must not shadow the index). Both are asserted on the same server.
 * - the landing's chip and the route's 404 are gated on the *same* count, so a catalog that records
 *   nothing offers no link and a catalog that records something offers one that works.
 */
class ServeMotionIndexRoutingTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private val captureId = "switch-on__ideal__default__light"

  /** A catalog whose one component publishes one capture, in the production shape. */
  private fun recordingCatalog(): ServeBundleHost {
    val dir = Files.createTempDirectory("motion-index").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/switch-on.png").writeBytes(png())
    File(dir, "previews/still-only.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText(
        """
        {"switch-on":{"componentId":"Switch/On","section":"Components","order":1,
          "motion":[{"id":"$captureId","kind":"interaction",
            "caption":"Toggle repeatedly. The thumb travels on the theme's spatial spring.",
            "extension":".apng"}]},
         "still-only":{"componentId":"Badge","section":"Components","order":2}}
        """
          .trimIndent()
      )
    return ServeBundleHost(
      dir,
      label = "m3-catalog",
      title = "Material 3",
      declaredMotion = listOf(captureId),
      fetchMotion = { id ->
        if (id == captureId) BranchFetch.Ok("capture-bytes".toByteArray()) else BranchFetch.NotFound
      },
      motionBranchPaths = mapOf(captureId to "motion/switch-on/ideal__default__light.apng"),
    )
  }

  /** …and one that records nothing at all, which is the overwhelming majority of catalogs. */
  private fun stillCatalog(): ServeBundleHost {
    val dir = Files.createTempDirectory("motion-index-none").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/badge.png").writeBytes(png())
    return ServeBundleHost(dir, label = "plain", title = "Plain")
  }

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    registry.register("m3-catalog", host = recordingCatalog(), pinned = true)
    registry.register("plain", host = stillCatalog(), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "m3-catalog",
        isPublic = true,
        catalogSessions = listOf("m3-catalog", "plain"),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun get(path: String): Triple<Int, String, String> {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(request).execute().use { response ->
      return Triple(
        response.code,
        response.header("Content-Type").orEmpty(),
        response.body.string(),
      )
    }
  }

  @Test
  fun `the browser lists the catalog's captures and links each into the viewer`() {
    val (code, type, body) = get("/m3-catalog/motion")
    assertEquals(200, code)
    assertTrue(type.startsWith("text/html"), type)
    assertTrue(body.contains("data-motion-src=\"/m3-catalog/motion/$captureId.apng\""), body)
    assertTrue(body.contains("data-motion-poster=\"/m3-catalog/render/switch-on.png\""), body)
    assertTrue(
      body.contains("/m3-catalog/p/switch-on?mode=motion&amp;motion=$captureId"),
      "each card deep-links the viewer to this recording",
    )
    assertTrue(body.contains("Toggle repeatedly"), "the capture's caption names what to watch for")
    assertFalse(body.contains("still-only"), "a component with no capture has nothing to show here")
  }

  @Test
  fun `the index does not shadow the capture bytes`() {
    // The whole reason `/motion` could have been a mistake: it sits one segment above the route
    // that serves the recordings, and Ktor scores them separately only because they are distinct
    // paths. If that ever stops being true the page loads and every card is broken.
    val (code, type, body) = get("/m3-catalog/motion/$captureId.apng")
    assertEquals(200, code)
    assertEquals("capture-bytes", body)
    assertFalse(type.startsWith("text/html"), "the asset route still answers bytes, not the index")
  }

  @Test
  fun `the landing links the browser, and only when there is one`() {
    val (_, _, catalog) = get("/m3-catalog/")
    assertTrue(
      catalog.contains("href=\"/m3-catalog/motion\">1 motion capture</a>"),
      "the entry point is on the catalog it belongs to",
    )
    val (_, _, plain) = get("/plain/")
    assertFalse(plain.contains("/plain/motion"), "a catalog that records nothing offers no link")
  }

  @Test
  fun `a catalog that records nothing 404s the browser rather than serving an empty page`() {
    val (code, _, body) = get("/plain/motion")
    assertEquals(404, code)
    assertTrue(body.contains("no motion captures"), body)
  }
}
