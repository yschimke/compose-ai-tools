package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.GuidelineRegionV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test

class GuidelineAnnotatorTest {
  private fun fail(
    rule: String,
    nodeIds: List<String> = emptyList(),
    regions: List<GuidelineRegionV1> = emptyList(),
  ) =
    GuidelineVerdictV1.Builder(rule, GuidelineVerdictV1.FAIL)
      .apply {
        this.nodeIds = nodeIds
        this.regions = regions
      }
      .build()

  private fun region(x: Double, y: Double, w: Double, h: Double, subject: String? = null) =
    GuidelineRegionV1.Builder(x, y, w, h).apply { subjectId = subject }.build()

  private fun png(width: Int, height: Int, fill: Color): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
      color = fill
      fillRect(0, 0, width, height)
      dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  @Test
  fun `findings are numbered in the order given, skipping those with nothing to draw`() {
    val nodes = listOf(PreviewNode("a", null, "", 10, 10, 30, 30))
    val marks =
      GuidelineAnnotator.marks(
        nodes,
        listOf(
          fail("first", regions = listOf(region(0.5, 0.5, 0.2, 0.2))),
          fail("nothing-here", nodeIds = listOf("missing")),
          fail("other-subject", regions = listOf(region(0.1, 0.1, 0.1, 0.1, subject = "other"))),
          fail("two-marks", nodeIds = listOf("a"), regions = listOf(region(0.0, 0.0, 0.1, 0.1))),
        ),
        subjectId = "me",
        width = 100,
        height = 100,
      )
    assertThat(marks.map { it.number to it.ruleId })
      .containsExactly(1 to "first", 2 to "two-marks", 2 to "two-marks")
      .inOrder()
    assertThat(marks[0].bounds).isEqualTo(Rectangle(50, 50, 20, 20))
    assertThat(marks[1].bounds).isEqualTo(Rectangle(10, 10, 20, 20))
    assertThat(marks[1].region).isFalse()
    assertThat(marks[2].region).isTrue()
  }

  @Test
  fun `badges stay inside the picture and never overlap, even on one shared box`() {
    val box = Rectangle(0, 0, 40, 40)
    val marks = (1..6).map { GuidelineAnnotator.Mark(it, "r$it", box, region = true) }
    val size = 24
    val centres = GuidelineAnnotator.placeBadges(marks, 200, 200, size)
    assertThat(centres).hasSize(6)
    centres.forEach {
      assertThat(it.x).isAtLeast(size / 2.0)
      assertThat(it.y).isAtLeast(size / 2.0)
      assertThat(it.x).isAtMost(200 - size / 2.0)
      assertThat(it.y).isAtMost(200 - size / 2.0)
    }
    centres.forEachIndexed { i, a ->
      centres.drop(i + 1).forEach { b -> assertThat(a.distance(b)).isAtLeast(size.toDouble()) }
    }
  }

  @Test
  fun `a badge sits beside its box rather than on it when there is room`() {
    val box = Rectangle(80, 80, 40, 40)
    val centre =
      GuidelineAnnotator.placeBadges(
          listOf(GuidelineAnnotator.Mark(1, "r", box, true)),
          200,
          200,
          24,
        )
        .single()
    assertThat(box.intersects(Rectangle(centre.x.toInt() - 12, centre.y.toInt() - 12, 24, 24)))
      .isFalse()
  }

  @Test
  fun `nothing is painted over the content a region marks`() {
    val grey = Color(0x80, 0x80, 0x80)
    val out =
      GuidelineAnnotator.annotate(
        png(400, 400, grey),
        emptyList(),
        listOf(fail("clip", regions = listOf(region(0.25, 0.25, 0.5, 0.5)))),
      )
    val image = ImageIO.read(out.inputStream())
    assertThat(image.width).isEqualTo(400)
    // The middle of the region, and well inside its edges, are untouched.
    listOf(200 to 200, 120 to 280, 280 to 120).forEach { (x, y) ->
      assertThat(image.getRGB(x, y)).isEqualTo(grey.rgb)
    }
    // The outline is there, just outside the box's top edge: amber dashes over a dark halo.
    val edge = (100..300).flatMap { x -> (94..100).map { y -> Color(image.getRGB(x, y)) } }
    assertThat(edge.any { it.red > 0xE0 && it.green > 0xA0 && it.blue < 0x40 }).isTrue()
    assertThat(edge.any { it.red < 0x30 && it.green < 0x30 && it.blue < 0x30 }).isTrue()
  }

  @Test
  fun `the overlay says it carries numbered badges`() {
    val out =
      GuidelineAnnotator.annotate(
        png(100, 100, Color.GRAY),
        emptyList(),
        listOf(fail("clip", regions = listOf(region(0.2, 0.2, 0.5, 0.5)))),
      )
    val text = String(out, Charsets.ISO_8859_1)
    assertThat(text)
      .contains("tEXt${GuidelineAnnotator.FORMAT_KEY}\u0000${GuidelineAnnotator.FORMAT}")
    assertThat(ImageIO.read(out.inputStream()).width).isEqualTo(100)
  }

  @Test
  fun `an unreadable picture is returned unchanged`() {
    val junk = byteArrayOf(1, 2, 3)
    assertThat(GuidelineAnnotator.annotate(junk, emptyList(), listOf(fail("x")))).isEqualTo(junk)
  }
}
