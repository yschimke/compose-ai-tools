package ee.schimke.composeai.glimmer

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import org.junit.Test

class GlimmerEnvironmentCompositorTest {
  @Test
  fun `black is additive zero and channels saturate`() {
    val capture = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
    capture.setRGB(0, 0, 0xff000000.toInt())
    capture.setRGB(1, 0, 0xffff8040.toInt())

    val result = GlimmerEnvironmentCompositor.composite(capture, GlimmerEnvironment.Light)

    assertThat(result.getRGB(0, 0)).isNotEqualTo(0xff000000.toInt())
    assertThat((result.getRGB(1, 0) ushr 16) and 0xff).isEqualTo(255)
  }

  @Test
  fun `png application preserves raw capture`() {
    val dir = createTempDirectory("glimmer-composite-").toFile()
    try {
      val file = File(dir, "capture.png")
      val source = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
      ImageIO.write(source, "png", file)

      val raw = GlimmerEnvironmentCompositor.applyToPng(file, GlimmerEnvironment.Dark)

      assertThat(raw.name).isEqualTo("capture.raw.png")
      assertThat(raw.exists()).isTrue()
      assertThat(file.readBytes().contentEquals(raw.readBytes())).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `png application can preserve raw capture without overwriting another processor artifact`() {
    val dir = createTempDirectory("glimmer-composite-distinct-raw-").toFile()
    try {
      val file = File(dir, "capture.png")
      val focusRaw = File(dir, "capture.raw.png")
      val glimmerRaw = File(dir, "capture.glimmer.raw.png")
      val source = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
      source.setRGB(0, 0, 0xffffffff.toInt())
      ImageIO.write(source, "png", file)
      focusRaw.writeText("pre-focus-overlay")

      val raw =
        GlimmerEnvironmentCompositor.applyToPng(
          file,
          GlimmerEnvironment.Dark,
          raw = glimmerRaw,
        )

      assertThat(raw).isEqualTo(glimmerRaw)
      assertThat(glimmerRaw.exists()).isTrue()
      assertThat(focusRaw.readText()).isEqualTo("pre-focus-overlay")
      assertThat(file.readBytes().contentEquals(glimmerRaw.readBytes())).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }
}
