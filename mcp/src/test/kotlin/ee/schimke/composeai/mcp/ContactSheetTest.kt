package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test

class ContactSheetTest {
  private fun pngOf(w: Int, h: Int, color: Color): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, w, h)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  @Test
  fun `empty input returns null`() {
    assertThat(ContactSheet.stitch(emptyList())).isNull()
  }

  @Test
  fun `stitches cells into a single decodable grid larger than any one tile`() {
    val cells =
      listOf(
        ContactSheet.Cell("light", pngOf(40, 30, Color.RED)),
        ContactSheet.Cell("dark", pngOf(40, 30, Color.BLUE)),
        ContactSheet.Cell("ar", pngOf(40, 30, Color.GREEN)),
      )
    val sheet = ContactSheet.stitch(cells)
    assertThat(sheet).isNotNull()
    val decoded = ImageIO.read(sheet!!.inputStream())
    assertThat(decoded).isNotNull()
    // 3 cells → a 2-column, 2-row grid, so the sheet exceeds a single tile in both dimensions.
    assertThat(decoded.width).isGreaterThan(40)
    assertThat(decoded.height).isGreaterThan(30)
  }

  @Test
  fun `an undecodable cell renders a placeholder without failing the sheet`() {
    val cells =
      listOf(
        ContactSheet.Cell("good", pngOf(40, 30, Color.RED)),
        ContactSheet.Cell("broken", byteArrayOf(1, 2, 3, 4)),
      )
    val sheet = ContactSheet.stitch(cells)
    assertThat(sheet).isNotNull()
    assertThat(ImageIO.read(sheet!!.inputStream())).isNotNull()
  }
}
