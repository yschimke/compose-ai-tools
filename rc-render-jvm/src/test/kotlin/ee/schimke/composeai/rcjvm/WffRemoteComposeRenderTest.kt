package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.dwf.WffCompileOptions
import ee.schimke.composeai.dwf.WffRemoteCompose
import ee.schimke.composeai.dwf.WffResourceSource
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** The `.rc` `WffRemoteCompose` hands a host plays in the player at any requested size. */
class WffRemoteComposeRenderTest {
  private val resDir: File =
    File(
        checkNotNull(
            javaClass.classLoader.getResource("wear-os-samples/SimpleAnalog/raw/watchface.xml"),
          )
          .toURI(),
      )
      .parentFile
      .parentFile
  private val xml = File(resDir, "raw/watchface.xml").readText()

  private fun distinctColors(png: ByteArray): Int {
    val image = checkNotNull(ImageIO.read(ByteArrayInputStream(png)))
    val colors = HashSet<Int>()
    for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
      colors += image.getRGB(x, y)
    }
    return colors.size
  }

  @Test
  fun `a compiled document renders at the requested size`() {
    val result =
      WffRemoteCompose.compile(
        xml,
        WffResourceSource.ofDirectory(resDir),
        WffCompileOptions(widthPx = 300, heightPx = 300),
      )
    val png = renderDwfDocumentToPng(result.rcBytes!!, widthPx = 300, heightPx = 300)
    val image = checkNotNull(ImageIO.read(ByteArrayInputStream(png)))
    assertThat(image.width).isEqualTo(300)
    assertThat(image.height).isEqualTo(300)
    assertThat(distinctColors(png)).isGreaterThan(8)
  }

  @Test
  fun `missing resources still render, with the placeholder visible`() {
    val withPlaceholders = WffRemoteCompose.compile(xml, WffResourceSource.Empty)
    val without =
      WffRemoteCompose.compile(
        xml,
        WffResourceSource.Empty,
        WffCompileOptions(placeholderMissingResources = false),
      )
    val a = renderDwfDocumentToPng(withPlaceholders.rcBytes!!, 450, 450)
    val b = renderDwfDocumentToPng(without.rcBytes!!, 450, 450)
    assertThat(a).isNotEqualTo(b)
    assertThat(distinctColors(a)).isGreaterThan(1)
  }
}
