package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designparity.BackdropImage
import ee.schimke.composeai.designparity.BackdropPage
import ee.schimke.composeai.designparity.FrameSize
import ee.schimke.composeai.designparity.PageRect
import ee.schimke.composeai.designparity.Placement
import ee.schimke.composeai.designparity.PlacementLink
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
 * The whole-screen surface over real HTTP: the screens index, one screen's view, and the backdrop
 * image, on both the `?session=` and the canonical `/{system}/…` form.
 *
 * What's worth pinning here beyond "the routes exist":
 * - the `.png` suffix picks the image off the *same* route as the view, so a page id can never
 *   collide with a separate asset path;
 * - a placement mapped to a preview this catalog doesn't publish gets a hotspot but **no** overlay,
 *   because the alternative is an `<img>` that can only 404;
 * - a catalog that publishes no screens 404s the surface instead of serving an empty stage.
 */
class ServePageBackdropRoutingTest {

  private fun png(width: Int = 8, height: Int = 16): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /** A bundle with two previews, one of which the manifest below maps a placement to. */
  private fun bundle(label: String, pages: String?): ServeBundleHost {
    val dir = Files.createTempDirectory("pages-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/com.example.AppBar.png").writeBytes(png())
    File(dir, "previews/com.example.ListItem.png").writeBytes(png())
    if (pages != null) {
      File(dir, ServePageBackdropStore.DIRECTORY).mkdirs()
      File(dir, "${ServePageBackdropStore.DIRECTORY}/${ServePageBackdropStore.INDEX_FILE}")
        .writeText(pages)
      File(dir, "${ServePageBackdropStore.DIRECTORY}/upcoming.png").writeBytes(png(412, 954))
    }
    return ServeBundleHost(dir, label = label)
  }

  private val manifest =
    """
    {"version":1,"source":"figma","fileKey":"ocdacdEsnHipMJD3egzxKb","pages":[
      {"id":"upcoming","name":"Upcoming-Mobile","nodeId":"56615:48121",
       "frame":{"width":412,"height":954},
       "image":{"uri":"upcoming.png","scale":2},
       "placements":[
         {"nodeId":"1:1","name":"App bar","bounds":{"x":0,"y":48,"width":412,"height":64},
          "depth":0,"ref":"figma:ocdacdEsnHipMJD3egzxKb/1:1","link":"manifest",
          "code":"ui/TopAppBars.kt#AppBar","previewId":"com.example.AppBar","confidence":"high"},
         {"nodeId":"1:3","name":"Carousel","bounds":{"x":0,"y":120,"width":412,"height":200},
          "depth":0,"ref":"figma:ocdacdEsnHipMJD3egzxKb/1:3","link":"manifest",
          "code":"ui/Carousel.kt#Carousel","previewId":"com.example.NotPublished"},
         {"nodeId":"1:2","name":"Status bar","bounds":{"x":0,"y":0,"width":412,"height":48},
          "depth":0,"ref":"figma:ocdacdEsnHipMJD3egzxKb/1:2","link":"unlinked"}]}]}
    """
      .trimIndent()

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    registry.register("m3-catalog", host = bundle("m3-catalog", manifest), pinned = true)
    registry.register("plain", host = bundle("plain", pages = null), pinned = true)
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
        response.body?.string() ?: "",
      )
    }
  }

  @Test
  fun `the screens index lists the published screen and its coverage`() {
    val (code, type, body) = get("/m3-catalog/pages")
    assertEquals(200, code)
    assertTrue(type.startsWith("text/html"))
    assertTrue(body.contains("Upcoming-Mobile"))
    // Two of three parts are linked; the status bar is OS chrome with nothing behind it.
    assertTrue(body.contains("2 of 3 parts implemented"))
    assertTrue(body.contains("/m3-catalog/pages/upcoming"))
  }

  @Test
  fun `one screen renders its hotspots positioned as a share of the frame`() {
    val (code, _, body) = get("/m3-catalog/pages/upcoming")
    assertEquals(200, code)
    // 48 / 954 of the frame's height, emitted as a percentage — no image pixels anywhere.
    assertTrue(body.contains("top:5.0314%"), "expected a ratio-positioned hotspot in:\n$body")
    assertTrue(body.contains("data-link=\"manifest\""))
    assertTrue(body.contains("data-link=\"unlinked\""))
    assertTrue(body.contains("Open in Figma"))
  }

  @Test
  fun `only placements this catalog can render carry an overlay`() {
    val (_, _, body) = get("/m3-catalog/pages/upcoming")
    // Published: gets a render to lay over the design. The overlay images ride an inert
    // `<template>`, so the browser parses them but fetches nothing until the toggle clones them in.
    assertTrue(body.contains("<template data-cp-backdrop-render-source>"))
    assertTrue(body.contains("src=\"/m3-catalog/render/com.example.AppBar.png\""))
    // Mapped by the producer, but absent from this catalog — hotspot yes, image never.
    assertFalse(body.contains("com.example.NotPublished"))
    assertTrue(body.contains("Carousel"))
  }

  @Test
  fun `the backdrop image comes off the same route with a png suffix`() {
    val (code, type, _) = get("/m3-catalog/pages/upcoming.png")
    assertEquals(200, code)
    assertEquals("image/png", type)
  }

  @Test
  fun `the session-query form serves the same screens`() {
    assertEquals(200, get("/pages?session=m3-catalog").first)
    assertEquals(200, get("/pages/upcoming?session=m3-catalog").first)
    assertEquals(200, get("/pages/upcoming.png?session=m3-catalog").first)
  }

  @Test
  fun `a catalog with no screens 404s the surface`() {
    assertEquals(404, get("/plain/pages").first)
    assertEquals(404, get("/plain/pages/upcoming").first)
    assertEquals(404, get("/plain/pages/upcoming.png").first)
  }

  @Test
  fun `an unknown screen 404s, and its image with it`() {
    assertEquals(404, get("/m3-catalog/pages/ghost").first)
    assertEquals(404, get("/m3-catalog/pages/ghost.png").first)
  }

  @Test
  fun `hotspot geometry is locale-independent`() {
    // A comma-decimal default locale turns `top:5.0314%` into `top:5,0314%`, which is not CSS —
    // every rectangle would collapse to the stage's top-left on a box whose LANG happened to be
    // de_DE. Cheap to get wrong (`"%.4f".format(x)` reads perfectly innocent), invisible in every
    // English test run, so it is pinned here.
    val original = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale.GERMANY)
      val html =
        ServeWeb.pageBackdropPage(
          moduleLabel = "m3-catalog",
          page =
            BackdropPage(
              id = "upcoming",
              name = "Upcoming-Mobile",
              nodeId = "56615:48121",
              frame = FrameSize(412.0, 954.0),
              image = BackdropImage("upcoming.png", 2.0),
              placements =
                listOf(
                  Placement(
                    nodeId = "1:1",
                    name = "App bar",
                    bounds = PageRect(0.0, 48.0, 412.0, 64.0),
                    link = PlacementLink.MANIFEST,
                  )
                ),
            ),
          token = "t",
        )
      assertTrue(html.contains("top:5.0314%"), "expected a dot-decimal percentage in:\n$html")
      assertFalse(html.contains("top:5,0314%"))
    } finally {
      java.util.Locale.setDefault(original)
    }
  }

  @Test
  fun `the catalog landing links to the screens it publishes, and omits the link otherwise`() {
    assertTrue(get("/m3-catalog").third.contains("/m3-catalog/pages"))
    assertFalse(get("/plain").third.contains("/plain/pages"))
  }
}
