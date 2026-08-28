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
 * The design-page surface over real HTTP: the pages index, one page's view, and the cached export,
 * on both the `?session=` and the canonical `/{system}/…` form.
 *
 * What's worth pinning here beyond "the routes exist":
 * - the export is **inlined** in the view, because an `<img>` cannot be reached into and hiding a
 *   node is the whole feature;
 * - the `.svg` suffix picks the export off the *same* route as the view, so a page id can never
 *   collide with a separate asset path — and it answers the sanitized markup, not the branch's own
 *   bytes;
 * - a node mapped to a preview this catalog doesn't publish gets an outline but **no** render,
 *   because the alternative is an `<img>` that can only 404;
 * - a catalog that publishes no pages 404s the surface instead of serving an empty stage.
 */
class ServeDesignPageRoutingTest {

  private fun png(width: Int = 8, height: Int = 16): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private val svg =
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 800" width="1200" height="800">
      <g data-node-id="1:1"><circle cx="180" cy="300" r="90" fill="#6750A4"/></g>
      <g data-node-id="1:3"><rect x="330" y="210" width="180" height="180" fill="#6750A4"/></g>
      <g data-node-id="1:9"><rect x="40" y="32" width="1120" height="64" fill="#EADDFF"/></g>
    </svg>
    """
      .trimIndent()

  /** A bundle with two previews, one of which the manifest below maps a node to. */
  private fun bundle(label: String, pages: String?): ServeBundleHost {
    val dir = Files.createTempDirectory("pages-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/com.example.Circle.png").writeBytes(png())
    File(dir, "previews/com.example.Square.png").writeBytes(png())
    if (pages != null) {
      File(dir, ServeDesignPageStore.DIRECTORY).mkdirs()
      File(dir, "${ServeDesignPageStore.DIRECTORY}/${ServeDesignPageStore.INDEX_FILE}")
        .writeText(pages)
      File(dir, "${ServeDesignPageStore.DIRECTORY}/shape.svg").writeText(svg)
      File(dir, "${ServeDesignPageStore.DIRECTORY}/icons.svg").writeText(svg)
    }
    return ServeBundleHost(dir, label = label)
  }

  private val manifest =
    """
    {"version":2,"source":"figma","fileKey":"ocdacdEsnHipMJD3egzxKb","pages":[
      {"id":"shape","name":"Shape","nodeId":"58548:7093",
       "frame":{"width":1200,"height":800},
       "image":{"uri":"shape.svg","format":"svg"},
       "nodes":[
         {"nodeId":"1:8","name":"Shape Set","depth":2,"container":true,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:8","link":"unlinked"},
         {"nodeId":"1:1","name":"Shape=Circle","depth":3,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:1","link":"manifest",
          "code":"ui/Shapes.kt#CircleShape","previewId":"com.example.Circle","confidence":"high"},
         {"nodeId":"1:3","name":"Shape=Pill","depth":3,"cell":true,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:3","link":"manifest",
          "code":"ui/Shapes.kt#PillShape","previewId":"com.example.NotPublished"},
         {"nodeId":"1:12","name":"Shape=Gem","depth":3,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:12","link":"unlinked"},
         {"nodeId":"1:9","name":".Header","depth":2,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:9","link":"unlinked"},
         {"nodeId":"1:20","name":"Base / Corner","depth":2,"inventory":false,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:20","link":"unlinked"}]},
      {"id":"icons","name":"Icons","nodeId":"58548:9000","inventory":false,
       "frame":{"width":1200,"height":800},
       "image":{"uri":"icons.svg","format":"svg"},
       "nodes":[
         {"nodeId":"2:1","name":"Icon=Alarm","depth":2,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/2:1","link":"unlinked"},
         {"nodeId":"2:2","name":"Icon=Bell","depth":2,
          "ref":"figma:ocdacdEsnHipMJD3egzxKb/2:2","link":"unlinked"}]}]}
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
        response.body.string(),
      )
    }
  }

  @Test
  fun `the pages index lists the published page and its coverage`() {
    val (code, type, body) = get("/m3-catalog/pages")
    assertEquals(200, code)
    assertTrue(type.startsWith("text/html"))
    assertTrue(body.contains("Shape"))
    // FIVE nodes on the sheet, but only THREE are components a catalog could implement, and the
    // count says so. `Shape Set` says `container` on the wire — the variant set the two linked
    // shapes came out of,
    // and `.Header` is a private component (Figma's leading-dot convention) — furniture, not work.
    // Counting every node instead reported `2 of 5` and told a reader three components were
    // missing when one was.
    assertTrue(body.contains("2 of 3 components implemented"), body)
    assertTrue(body.contains("/m3-catalog/pages/shape"))
  }

  /**
   * A node we REACHED rather than BUILT gets its own mark, and the legend names it.
   *
   * `Shape=Pill` is linked exactly as `Shape=Circle` is — both `link: manifest` — so the link
   * cannot tell them apart, and on a well-covered sheet painted one colour neither could a reader.
   * The distinction is what is behind them: a preview written for the component, or a `_VARIANT_`
   * capture of a neighbouring one with a knob turned.
   */
  @Test
  fun `an override cell is marked apart from a component we wrote`() {
    val (_, _, body) = get("/m3-catalog/pages/shape")
    assertTrue(
      Regex("data-cp-node=\"1:3\"[^>]*data-cp-cell|data-cp-cell[^>]*data-cp-node=\"1:3\"")
        .containsMatchIn(body),
      body,
    )
    // The component with a preview of its own carries no such claim — and note 1:3 does, despite
    // this catalog publishing no sticker for it: cell-ness is a fact about the mapping, not about
    // whether the render made it into the bundle.
    assertFalse(
      Regex("data-cp-node=\"1:1\"[^>]*data-cp-cell|data-cp-cell[^>]*data-cp-node=\"1:1\"")
        .containsMatchIn(body),
      body,
    )
    assertTrue(body.contains("override variant"), body)
  }

  /**
   * The kit's own base parts and a sheet that is not a component inventory, over the wire.
   *
   * `Base / Corner` is a sixth node on the Shape sheet and the count does not move: a base part is
   * what a published set is assembled from, not something a catalog owes. And the Icons sheet — 499
   * nodes in the real kit — states what it is instead of reporting `0 of 499`, which was a third of
   * the whole kit's apparent gap and drowned every real one.
   */
  @Test
  fun `base parts and a non-inventory sheet make no coverage claim`() {
    val (_, _, index) = get("/m3-catalog/pages")
    assertTrue(index.contains("2 of 3 components implemented"), index)
    assertFalse(index.contains("0 of 2 components implemented"), index)
    assertTrue(index.contains("2 nodes · not a component inventory"), index)

    val (code, _, page) = get("/m3-catalog/pages/icons")
    assertEquals(200, code)
    assertTrue(page.contains("2 nodes · not a component inventory"), page)
    // Still drawn and still browsable — this changes what the sheet claims, not what it shows.
    assertTrue(page.contains("data-node-id=\"1:1\""), page)
  }

  @Test
  fun `only a real missing component is marked as a coverage gap`() {
    val (_, _, body) = get("/m3-catalog/pages/shape")
    // `Shape=Gem` is unlinked and IS a component, so it is the gap the filter exists to show.
    assertTrue(
      Regex("data-cp-node=\"1:12\"[^>]*data-cp-gap|data-cp-gap[^>]*data-cp-node=\"1:12\"")
        .containsMatchIn(body),
      "expected 1:12 to be a gap in:\n$body",
    )
    // The container and the private component are unlinked too, and neither is a gap.
    for (id in listOf("1:8", "1:9")) {
      assertFalse(
        Regex("data-cp-node=\"$id\"[^>]*data-cp-gap|data-cp-gap[^>]*data-cp-node=\"$id\"")
          .containsMatchIn(body),
        "expected $id NOT to be a gap in:\n$body",
      )
    }
  }

  @Test
  fun `the page view inlines the export rather than pointing an img at it`() {
    val (code, _, body) = get("/m3-catalog/pages/shape")
    assertEquals(200, code)
    // The capability the whole surface rests on: the markup is in the document, so a node can be
    // found, hidden, and replaced. An `<img>` could not be reached into.
    assertTrue(body.contains("data-node-id=\"1:1\""), "expected inlined markup in:\n$body")
    assertTrue(body.contains("data-cp-node=\"1:1\""))
    assertTrue(body.contains("data-link=\"manifest\""))
    assertTrue(body.contains("data-link=\"unlinked\""))
    assertTrue(body.contains("Open in Figma"))
    // The sheet's own shape decides the stage's shape.
    assertTrue(body.contains("--cp-page-aspect:1.5000"))
  }

  @Test
  fun `only nodes this catalog can render carry a swap-in render`() {
    val (_, _, body) = get("/m3-catalog/pages/shape")
    // Published: gets a render to stand in for the design's own drawing. The renders ride an inert
    // `<template>`, so the browser parses them but fetches nothing until the toggle adopts them.
    assertTrue(body.contains("<template data-cp-page-render-source>"))
    assertTrue(body.contains("src=\"/m3-catalog/render/com.example.Circle.png\""))
    // Mapped by the producer, but absent from this catalog — outline yes, image never.
    assertFalse(body.contains("com.example.NotPublished"))
    assertTrue(body.contains("Shape=Pill"))
  }

  @Test
  fun `the export comes off the same route with an svg suffix`() {
    val (code, type, body) = get("/m3-catalog/pages/shape.svg")
    assertEquals(200, code)
    assertTrue(type.startsWith("image/svg+xml"), type)
    assertTrue(body.contains("data-node-id"))
  }

  @Test
  fun `the session-query form serves the same pages`() {
    assertEquals(200, get("/pages?session=m3-catalog").first)
    assertEquals(200, get("/pages/shape?session=m3-catalog").first)
    assertEquals(200, get("/pages/shape.svg?session=m3-catalog").first)
  }

  /**
   * Both design-page surfaces file against the CATALOG, page-scoped.
   *
   * The floating launcher unhides its catalog half only on a page carrying `#cp-report`, so a
   * design page — the surface whose entire subject is somebody's design file — offered the preview
   * SERVER's tracker as its only route, which is where a report about the design ended up
   * (issue #4704). Page-scoped, for the reason the comparison wall's is: a sheet shows every
   * component on it and singles out none, so the report names the page rather than inventing a
   * preview the reporter never picked.
   */
  @Test
  fun `the pages index and a page both offer the catalog tracker`() {
    val (_, _, index) = get("/m3-catalog/pages")
    assertTrue(
      index.contains("id=\"cp-report\"") &&
        index.contains("data-cp-subject=\"these design pages\""),
      index,
    )
    val (_, _, page) = get("/m3-catalog/pages/shape")
    assertTrue(
      page.contains("id=\"cp-report\"") && page.contains("data-cp-subject=\"this design page\""),
      page,
    )
    // Page-scoped: the body names the page and no preview it cannot honestly single out.
    assertTrue(page.contains("### Which page") && !page.contains("| Preview |"), page)
    // …and the catalog landing, the surface most visitors arrive on, carries one too.
    val (_, _, landing) = get("/m3-catalog/")
    assertTrue(
      landing.contains("id=\"cp-report\"") && landing.contains("data-cp-subject=\"this catalog\""),
      landing,
    )
  }

  @Test
  fun `a catalog with no pages 404s the surface`() {
    assertEquals(404, get("/plain/pages").first)
    assertEquals(404, get("/plain/pages/shape").first)
    assertEquals(404, get("/plain/pages/shape.svg").first)
  }

  @Test
  fun `an unknown page 404s, and its export with it`() {
    assertEquals(404, get("/m3-catalog/pages/ghost").first)
    assertEquals(404, get("/m3-catalog/pages/ghost.svg").first)
  }

  @Test
  fun `the stage aspect ratio is locale-independent`() {
    // A comma-decimal default locale turns `1.5000` into `1,5000`, which is not CSS — the stage
    // would collapse on a box whose LANG happened to be de_DE. Cheap to get wrong
    // (`"%.4f".format(x)` reads perfectly innocent), invisible in every English test run.
    val original = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale.GERMANY)
      val html =
        ServeWeb.designPage(
          moduleLabel = "m3-catalog",
          page =
            ee.schimke.composeai.designpages.DesignPage(
              id = "shape",
              name = "Shape",
              nodeId = "58548:7093",
              frame = ee.schimke.composeai.designpages.PageFrame(1200.0, 800.0),
              image = ee.schimke.composeai.designpages.PageImage("shape.svg"),
              nodes =
                listOf(
                  ee.schimke.composeai.designpages.PageNode(
                    nodeId = "1:1",
                    name = "Shape=Circle",
                    link = ee.schimke.composeai.designpages.PageNodeLink.MANIFEST,
                  )
                ),
            ),
          svg = svg,
          token = "t",
        )
      assertTrue(html.contains("--cp-page-aspect:1.5000"), "expected a dot decimal in:\n$html")
      assertFalse(html.contains("1,5000"))
    } finally {
      java.util.Locale.setDefault(original)
    }
  }

  @Test
  fun `the catalog landing links to the pages it publishes, and omits the link otherwise`() {
    assertTrue(get("/m3-catalog").third.contains("/m3-catalog/pages"))
    assertFalse(get("/plain").third.contains("/plain/pages"))
  }
}
