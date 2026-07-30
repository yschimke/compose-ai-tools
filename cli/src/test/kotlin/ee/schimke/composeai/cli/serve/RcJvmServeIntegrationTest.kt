package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The serve-side wiring of the cmp-jvm lane, exercised without Skiko: the render **spec** sourcing
 * (baked PNG size + capture density) and the chip **enablement** gate ([ServeHost.supportsCmpJvm]).
 * The actual pixel render runs in the isolated subprocess ([RcJvmServerRenderer]) and is covered by
 * the jvm module's skiko-gated tests; here the sidecar is faked with an empty jar dir, so nothing
 * native is touched.
 */
class RcJvmServeIntegrationTest {

  private fun pngBytes(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  /**
   * A bundle with a baked `previews/Foo.png` of the given size, optionally an `ir/Foo.rc` doc and a
   * `previews.json` declaring [density].
   */
  private fun bundle(width: Int, height: Int, density: Float?, withDoc: Boolean = true): File {
    val dir =
      java.nio.file.Files.createTempDirectory("rcjvm-bundle").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/Foo.png").writeBytes(pngBytes(width, height))
    if (withDoc) {
      File(dir, "ir").mkdirs()
      File(dir, "ir/Foo.rc").writeBytes(byteArrayOf(1, 2, 3))
    }
    if (density != null) {
      File(dir, "previews.json")
        .writeText(
          """
          {"module":":m","variant":"debug","previews":[
            {"id":"Foo","functionName":"Foo","className":"FooKt","params":{"density":$density}}]}
          """
            .trimIndent()
        )
    }
    return dir
  }

  private fun jarDir(): File {
    val dir = java.nio.file.Files.createTempDirectory("sidecar").toFile().also { it.deleteOnExit() }
    File(dir, "x.jar").writeText("")
    return dir
  }

  @Test
  fun `render spec uses the baked png size and the manifest density`() {
    val host = ServeBundleHost(bundle(width = 120, height = 80, density = 3.0f), label = "b")
    assertEquals(RcJvmRenderSpec(120, 80, 3.0f), host.remoteComposeRenderSpec("Foo"))
  }

  @Test
  fun `render spec falls back to the default density when the manifest declares none`() {
    val host = ServeBundleHost(bundle(width = 120, height = 80, density = null), label = "b")
    val spec = host.remoteComposeRenderSpec("Foo")
    assertEquals(120, spec?.widthPx)
    assertEquals(80, spec?.heightPx)
    // ServeBundleHost.DEFAULT_RENDER_DENSITY — the desktop renderer's own default.
    assertEquals(2.625f, spec?.density)
  }

  @Test
  fun `render spec is null for a preview with no captured document`() {
    val host = ServeBundleHost(bundle(120, 80, 3.0f, withDoc = false), label = "b")
    assertNull(host.remoteComposeRenderSpec("Foo"))
  }

  @Test
  fun `rc star seeds parse leniently by kind, skipping malformed values`() {
    val seeds =
      ServeOverrides.rcNamedValueSeeds(
        mapOf(
          "rc.label" to "Hello", // bare → string
          "rc.progress" to "float:0.5",
          "rc.iconSize" to "dp:48",
          "rc.count" to "int:3",
          "rc.on" to "bool:true",
          "rc.tint" to "color:#FF00FF00",
          "rc.bad" to "float:notanumber", // malformed → skipped
          "knob.x" to "1", // not an rc. seed → ignored
        )
      )
    assertEquals(RemoteNamedValue.StringValue("Hello"), seeds["label"])
    assertEquals(RemoteNamedValue.FloatValue(0.5f), seeds["progress"])
    assertEquals(RemoteNamedValue.DpValue(48f), seeds["iconSize"])
    assertEquals(RemoteNamedValue.IntValue(3), seeds["count"])
    assertEquals(RemoteNamedValue.BooleanValue(true), seeds["on"])
    assertEquals(RemoteNamedValue.ColorValue("#FF00FF00"), seeds["tint"])
    assertFalse("bad" in seeds)
    assertFalse("x" in seeds)
  }

  @Test
  fun `cmp-jvm chip is enabled only when the desktop-player sidecar is installed`() {
    val host = ServeBundleHost(bundle(120, 80, 3.0f), label = "b")
    // The JS lane always rides on a carried doc; cmp-jvm needs the sidecar, absent here.
    assertTrue(RcPlayerBackend.JS in host.enabledRcPlayersFor("Foo"))
    assertFalse(RcPlayerBackend.CMP_JVM in host.enabledRcPlayersFor("Foo"))

    // Point both sidecar dirs at (empty-jar) temp dirs so RcJvmServerRenderer.isAvailable() is
    // true.
    System.setProperty("composeai.cli.libRcjvmDir", jarDir().absolutePath)
    System.setProperty("composeai.cli.libDaemonDesktopDir", jarDir().absolutePath)
    try {
      assertTrue(RcPlayerBackend.CMP_JVM in host.enabledRcPlayersFor("Foo"))
    } finally {
      System.clearProperty("composeai.cli.libRcjvmDir")
      System.clearProperty("composeai.cli.libDaemonDesktopDir")
    }
  }
}
