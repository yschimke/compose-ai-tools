package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.Test

class RcJvmRenderTest {
  private fun resource(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing test resource $name" }
      .use { it.readBytes() }

  @Test
  fun rendersAPngAtTheRequestedPixelSize() {
    val png = renderRemoteDocumentToPng(resource("textbutton.rc"), 454, 200, density = 2f)
    val image = ImageIO.read(ByteArrayInputStream(png))
    assertThat(image.width).isEqualTo(454)
    assertThat(image.height).isEqualTo(200)
    assertThat(inkFraction(png)).isGreaterThan(0.02)
  }

  @Test
  fun aStepperDrawsItsContainerAndItsRail() {
    // The remote-m3 stepper is the document the cmp-jvm column used to draw as nothing.
    val png = renderRemoteDocumentToPng(resource("stepper.rc"), 384, 384, density = 2f)
    assertThat(inkFraction(png)).isGreaterThan(0.01)
  }

  @Test
  fun aDensityRelativeDocumentDrawsAtTheRenderDensity() {
    // The remote-m3 page indicator sizes its dots and rail from the player's `DENSITY` system
    // variable. rc-players before 2.0.4 resolved it against 1.0 on the frame a one-shot render
    // captures, so this lane drew the rail at half the width the AndroidX embedded player draws
    // it at (42px against 84px at density 2.0), and at that same half width at every density.
    val atTwo =
      inkWidth(renderRemoteDocumentToPng(resource("pageindicator.rc"), 384, 384, density = 2f))
    val atOne =
      inkWidth(renderRemoteDocumentToPng(resource("pageindicator.rc"), 384, 384, density = 1f))
    assertThat(atTwo).isIn(com.google.common.collect.Range.closed(82, 86))
    assertThat(atTwo).isIn(com.google.common.collect.Range.closed(2 * atOne - 2, 2 * atOne + 2))
  }

  @Test
  fun bothColorThemesRender() {
    // `textbutton.rc` has no `ColorTheme` branch, so the two frames may legitimately be equal; what
    // this pins is that the axis reaches the player without failing either render.
    val doc = resource("textbutton.rc")
    for (dark in listOf(false, true)) {
      val png = renderRemoteDocumentToPng(doc, 454, 200, dark = dark)
      assertThat(inkFraction(png)).isGreaterThan(0.02)
    }
  }

  @Test
  fun exportsALayeredSvgWithItsRasterLayersInlined() {
    val svg = String(renderRemoteDocumentToSvg(resource("textbutton.rc"), 454, 200), Charsets.UTF_8)
    assertThat(svg).startsWith("<svg")
    assertThat(svg).doesNotContain("figma-raster/")
  }

  @Test
  fun seedTextParsesEveryKindAndSkipsWhatItCannotRead() {
    fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    val seeds =
      parseSeedText(
        listOf(
            "str ${b64("label")} ${b64("Hello")}",
            "float ${b64("progress")} 0.5",
            "int ${b64("count")} 3",
            "color ${b64("tint")} -16776961",
            "bogus ${b64("x")} 1",
            "float ${b64("bad")} notanumber",
            "short",
            "",
          )
          .joinToString("\n")
      )
    assertThat(seeds)
      .containsExactly(
        "label",
        RcNamedValue.Text("Hello"),
        "progress",
        RcNamedValue.FloatValue(0.5f),
        "count",
        RcNamedValue.Integer(3),
        "tint",
        RcNamedValue.Color(-16776961),
      )
  }

  /** Width in pixels of the drawn (non-transparent) content. */
  private fun inkWidth(png: ByteArray): Int {
    val image = ImageIO.read(ByteArrayInputStream(png))
    var minX = image.width
    var maxX = -1
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        if ((image.getRGB(x, y) ushr 24) > 13) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
        }
      }
    }
    return if (maxX < minX) 0 else maxX - minX + 1
  }

  /** Fraction of pixels that differ from an opaque-white background by a visible amount. */
  private fun inkFraction(png: ByteArray): Double {
    val image = ImageIO.read(ByteArrayInputStream(png))
    var ink = 0
    for (y in 0 until image.height) for (x in 0 until image.width) {
      val argb = image.getRGB(x, y)
      val a = argb ushr 24
      if (a == 0) continue
      val r = argb shr 16 and 0xff
      val g = argb shr 8 and 0xff
      val b = argb and 0xff
      if (maxOf(255 - r, 255 - g, 255 - b) > 16) ink++
    }
    return ink.toDouble() / (image.width * image.height)
  }
}
