package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeBundleHostTest {

  private fun bundle(vararg previews: Pair<String, ByteArray>): File {
    val dir = java.nio.file.Files.createTempDirectory("bundle").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    val previewsDir = File(dir, "previews").apply { mkdirs() }
    previews.forEach { (id, png) ->
      File(previewsDir, "$id.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }
    return dir
  }

  @Test
  fun `nested preview ids (with slashes) are discovered and rendered`() {
    val host = ServeBundleHost(bundle("group/com.example.Red" to byteArrayOf(4, 2)), label = "b")
    assertEquals(listOf("group/com.example.Red"), host.previews.map { it.id })
    val ok = host.render("group/com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(4, 2).contentEquals(ok.png))
  }

  @Test
  fun `previews are discovered from the bundle's png files, sorted`() {
    val host =
      ServeBundleHost(
        bundle("com.example.Red" to byteArrayOf(1), "com.example.Blue" to byteArrayOf(2)),
        label = "demo@abc",
      )
    assertEquals(listOf("com.example.Blue", "com.example.Red"), host.previews.map { it.id })
    assertEquals("demo@abc", host.label)
  }

  @Test
  fun `render returns the baked png and NotFound for unknown ids`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(9, 8, 7)), label = "b")

    val ok = host.render("com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(9, 8, 7).contentEquals(ok.png))
    assertEquals(RenderOutcome.NotFound, host.render("com.example.Missing", PreviewOverrides()))
  }

  @Test
  fun `a bundle host has no live lane`() {
    val host = ServeBundleHost(bundle("p" to byteArrayOf(1)), label = "b")
    assertNull(host.subscribeStream("p", PreviewOverrides(), null, null) {})
    assertEquals(0, host.activeStreamCount())
    host.close() // no-op, must not throw
  }

  @Test
  fun `looksLikeBundle detects a previews directory with pngs`() {
    assertTrue(ServeBundleHost.looksLikeBundle(bundle("p" to byteArrayOf(1))))
    val empty = java.nio.file.Files.createTempDirectory("empty").toFile().also { it.deleteOnExit() }
    assertFalse(ServeBundleHost.looksLikeBundle(empty))
  }
}
