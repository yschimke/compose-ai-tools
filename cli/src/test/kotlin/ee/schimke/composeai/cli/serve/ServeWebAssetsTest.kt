package ee.schimke.composeai.cli.serve

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServeWebAssetsTest {
  @Test
  fun `serve web frontend assets are packaged as classpath resources`() {
    for (name in listOf("serve.css", "viewer.js", "viewer-groups.js", "viewer-drawers.js")) {
      val asset = assertNotNull(ServeWebAssets.load(name), "$name should be loadable")
      assertTrue(asset.bytes.isNotEmpty(), "$name should not be empty")
      assertTrue(asset.etag.startsWith("\"") && asset.etag.endsWith("\""), "$name ETag")
    }
  }

  @Test
  fun `viewer page references extracted assets`() {
    val preview = ServePreview("plain.Button", "button")
    val html = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))

    assertTrue(html.contains("""<link rel="stylesheet" href="/assets/serve/serve.css">"""), html)
    assertTrue(html.contains("""<script src="/assets/serve/viewer.js"></script>"""), html)
    assertTrue(html.contains("""<script src="/assets/serve/backend-badge.js"></script>"""), html)
  }

  @Test
  fun `extracted javascript assets pass syntax check when node is available`() {
    for (name in listOf("viewer.js", "viewer-groups.js", "viewer-drawers.js", "backend-badge.js")) {
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
}
