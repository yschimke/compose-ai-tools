package ee.schimke.composeai.cli.serve

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServeWebAssetsTest {
  @Test
  fun `serve web frontend assets are packaged as classpath resources`() {
    for (name in
      listOf(
        "serve.css",
        "url-state.js",
        "viewer.js",
        "viewer-groups.js",
        "viewer-drawers.js",
        "format-compare.js",
        "catalog-live.js",
      )) {
      val asset = assertNotNull(ServeWebAssets.load(name), "$name should be loadable")
      assertTrue(asset.bytes.isNotEmpty(), "$name should not be empty")
      assertTrue(asset.etag.startsWith("\"") && asset.etag.endsWith("\""), "$name ETag")
      assertEquals(asset.etag.trim('"'), asset.version, "$name version")
      assertEquals("/assets/serve/${asset.version}/$name", ServeWebAssets.href(name))
    }
  }

  @Test
  fun `viewer page references extracted assets`() {
    val preview = ServePreview("plain.Button", "button")
    val html = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))

    assertTrue(
      html.contains("""<link rel="stylesheet" href="${ServeWebAssets.href("serve.css")}">"""),
      html,
    )
    assertTrue(
      html.contains("""<script src="${ServeWebAssets.href("viewer.js")}"></script>"""),
      html,
    )
    assertTrue(
      html.contains("""<script src="${ServeWebAssets.href("url-state.js")}"></script>"""),
      html,
    )
    assertTrue(
      html.contains("""<script src="${ServeWebAssets.href("backend-badge.js")}"></script>"""),
      html,
    )
    val svgHtml = ServeWeb.viewerPage(preview, token = "t", hasSvgExport = true)
    assertTrue(
      svgHtml.contains("""<script src="${ServeWebAssets.href("format-compare.js")}"></script>"""),
      svgHtml,
    )
    assertTrue(svgHtml.contains("id=\"cp-svg-match\""), svgHtml)
  }

  @Test
  fun `extracted javascript assets pass syntax check when node is available`() {
    for (name in
      listOf(
        "url-state.js",
        "viewer.js",
        "viewer-groups.js",
        "viewer-drawers.js",
        "backend-badge.js",
        "format-compare.js",
        "catalog-live.js",
      )) {
      val resource =
        assertNotNull(
          ServeWebAssets::class.java.getResource("/ee/schimke/composeai/cli/serve/assets/$name")
        )
      val result =
        try {
          ProcessBuilder("node", "--check", resource.toURI().path).redirectErrorStream(true).start()
        } catch (_: IOException) {
          return
        }
      val output = result.inputStream.bufferedReader().readText()
      assertEquals(0, result.waitFor(), "$name failed node --check:\n$output")
    }
  }

  @Test
  fun `remote compose comparison fixes theme and fonts before scoring`() {
    val script = ServeWebAssets.load("format-compare.js")!!.bytes.decodeToString()
    val theme = script.indexOf("player.setTheme(theme)")
    val firstPaint = script.indexOf("player.repaint", startIndex = theme)
    val fonts = script.indexOf("player.fontsReady()", startIndex = firstPaint)
    val finalPaint = script.indexOf("player.repaint", startIndex = fonts)
    val score = script.indexOf("scoreCanvas(pngUrl, canvas)", startIndex = finalPaint)
    assertTrue(
      theme >= 0 &&
        theme < firstPaint &&
        firstPaint < fonts &&
        fonts < finalPaint &&
        finalPaint < score,
      "the RC player must apply artifact theme, discover and await fonts, then repaint before scoring",
    )
  }
}
