package ee.schimke.composeai.cli.serve

import java.io.File
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
        "serve-chrome.js",
        "vue-runtime.js",
        "catalog-components.js",
        "compare-components.js",
        "design-components.js",
        "parity-components.js",
        "viewer-components.js",
        "remote-compose.js",
        "viewer.js",
        "format-compare.js",
        "keyboard-navigation.js",
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
      html.contains("""<script src="${ServeWebAssets.href("serve-chrome.js")}"></script>"""),
      html,
    )
    // The provenance badge is a Vue element in `viewer-components.js`, so what the page owes it is
    // the shared runtime, the surface bundle and the tag — its behaviour is covered by
    // `cli/serve-web/test/backendBadge.test.ts`.
    assertTrue(
      html.contains("""<script src="${ServeWebAssets.href("vue-runtime.js")}"></script>""") &&
        html.contains("""<script src="${ServeWebAssets.href("viewer-components.js")}"></script>"""),
      html,
    )
    assertTrue(html.contains("<cp-backend-badge "), html)
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
        "serve-chrome.js",
        "viewer.js",
        "format-compare.js",
        "keyboard-navigation.js",
      )) {
      // Read the bytes and hand `node` a temp copy, rather than pointing it at the resource's own
      // path. `getResource(...).toURI().path` is null for a `jar:` URL, and which of the two this
      // is depends on packaging, not on anything the test asserts: while `serve` was a package
      // inside `:cli` the assets resolved from an exploded `build/resources/main` directory, and
      // as its own module they resolve from `serve.jar`, whereupon `ProcessBuilder` was handed a
      // null command element and threw NPE. Checking the bytes is also the more honest test — it
      // is the shipped copy that has to parse.
      val bytes =
        assertNotNull(
            ServeWebAssets::class
              .java
              .getResourceAsStream("/ee/schimke/composeai/cli/serve/assets/$name")
          )
          .use { it.readBytes() }
      val copy = File.createTempFile("serve-asset-", "-$name").apply { deleteOnExit() }
      copy.writeBytes(bytes)
      val result =
        try {
          ProcessBuilder("node", "--check", copy.absolutePath).redirectErrorStream(true).start()
        } catch (_: IOException) {
          return
        }
      val output = result.inputStream.bufferedReader().readText()
      assertEquals(0, result.waitFor(), "$name failed node --check:\n$output")
    }
  }

  // The RC lane's ordering invariant — apply the artifact theme, paint once to DISCOVER the named
  // font families, await them, repaint with the resolved glyphs, and only then measure — moved to
  // `<cp-compare-wall>` with the port. `compareWallElement.test.ts` drives a stub player and
  // asserts
  // the actual call order, which is what this test was approximating by comparing source offsets.
}
