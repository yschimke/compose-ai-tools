package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFrameDataProducerTest {

  private val fs = FakeFileSystem()

  private fun pngBytes(width: Int, height: Int, color: Color): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  /** Serves a synthetic frame sized for the requested art id. */
  private fun source(vararg available: String): DeviceArtSource {
    val backFor = mapOf("wear_round" to (576 to 596), "pixel_5" to (1360 to 2500))
    return DeviceArtSource { artId, resource ->
      if (resource !in available) return@DeviceArtSource null
      val (w, h) = backFor[artId] ?: (600 to 600)
      pngBytes(w, h, if (resource == "back") Color(40, 40, 40) else Color(0, 0, 0, 0))
    }
  }

  private fun writeScreenshot(path: String, color: Color = Color.RED): File {
    val bytes = pngBytes(300, 300, color)
    fs.createDirectories(path.toPath().parent!!)
    fs.write(path.toPath()) { write(bytes) }
    return File(path)
  }

  @Test
  fun autoResolvesWearFrameAndWritesArtifacts() {
    val png = writeScreenshot("/renders/watch.png")
    val manifest =
      DeviceFrameDataProducer.writeArtifacts(
        rootDir = File("/data"),
        previewId = "watch",
        pngFile = png,
        device = "id:wearos_large_round",
        settings = DeviceFrameConfig.Settings(DeviceFrameConfig.Selection.Auto),
        source = source("back"),
        fileSystem = fs,
      )
    assertEquals("wear_round", manifest!!.artId)
    assertTrue(fs.exists("/data/watch/deviceframe_wear_round.png".toPath()))
    assertTrue(fs.exists("/data/watch/deviceframe.json".toPath()))
    assertTrue(fs.exists("/data/watch/deviceframe-attribution.txt".toPath()))
    val attribution = fs.read("/data/watch/deviceframe-attribution.txt".toPath()) { readUtf8() }
    assertTrue(attribution.contains("CC BY 3.0"))
  }

  @Test
  fun forcedArtIdOverridesDeviceClass() {
    val png = writeScreenshot("/renders/p.png")
    val manifest =
      DeviceFrameDataProducer.writeArtifacts(
        rootDir = File("/data"),
        previewId = "p",
        pngFile = png,
        // device says tablet (normally un-framed) but the forced id wins.
        device = "id:pixel_tablet",
        settings = DeviceFrameConfig.Settings(DeviceFrameConfig.Selection.Forced("wear_round")),
        source = source("back"),
        fileSystem = fs,
      )
    assertEquals("wear_round", manifest!!.artId)
    assertTrue(fs.exists("/data/p/deviceframe_wear_round.png".toPath()))
  }

  @Test
  fun unframedDeviceClassIsANoOp() {
    val png = writeScreenshot("/renders/t.png")
    val manifest =
      DeviceFrameDataProducer.writeArtifacts(
        rootDir = File("/data"),
        previewId = "t",
        pngFile = png,
        device = "id:pixel_tablet",
        settings = DeviceFrameConfig.Settings(DeviceFrameConfig.Selection.Auto),
        source = source("back"),
        fileSystem = fs,
      )
    assertNull(manifest)
    assertFalse(fs.exists("/data/t".toPath()))
  }

  @Test
  fun missingBackLayerIsANoOp() {
    val png = writeScreenshot("/renders/w.png")
    val manifest =
      DeviceFrameDataProducer.writeArtifacts(
        rootDir = File("/data"),
        previewId = "w",
        pngFile = png,
        device = "id:wearos_large_round",
        settings = DeviceFrameConfig.Settings(DeviceFrameConfig.Selection.Auto),
        source = source(/* nothing available */ ),
        fileSystem = fs,
      )
    assertNull(manifest)
  }

  @Test
  fun phoneFrameWithShadowAndGlareLayers() {
    val png = writeScreenshot("/renders/phone.png")
    val manifest =
      DeviceFrameDataProducer.writeArtifacts(
        rootDir = File("/data"),
        previewId = "phone",
        pngFile = png,
        device = "id:pixel_8",
        settings = DeviceFrameConfig.Settings(DeviceFrameConfig.Selection.Auto),
        source = source("shadow", "back", "fore"),
        fileSystem = fs,
      )
    assertEquals("pixel_5", manifest!!.artId)
    assertTrue(fs.exists("/data/phone/deviceframe_pixel_5.png".toPath()))
  }
}
