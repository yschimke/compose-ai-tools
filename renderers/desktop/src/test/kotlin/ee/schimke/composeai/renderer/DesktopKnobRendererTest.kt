package ee.schimke.composeai.renderer

import ee.schimke.composeai.renderer.PreviewKnobArguments.Knob
import ee.schimke.composeai.renderer.PreviewKnobArguments.Type
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof of the **secondary override format**: a preview declares its editable knobs as
 * its own defaulted value parameters, and the renderer seeds a subset of them by argument.
 *
 * The claim under test is the one the format rests on — that **an unseeded parameter still takes
 * its author default**. `ComposableMethod` reads a null argument as "use the default": it sets that
 * parameter's bit in Kotlin's synthetic `$default` mask so the compiled default expression runs. If
 * that were not true, seeding one knob would blank every other parameter and the format would be
 * unusable for anything with more than one knob. Here it is checked in pixels: the render seeded
 * only on `sizeDp` must be byte-identical to a render that seeds nothing except for the size.
 *
 * These fixtures carry no harness call at all — no `previewOverride*`, no controller, no import
 * from this project. That is the format's whole point, and rendering them proves a preview written
 * as plain Compose is seedable.
 */
class DesktopKnobRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.KnobRenderTestFixturesKt"

  /** The knobs discovery records for [KnobSticker], spelled here as the renderer receives them. */
  private val stickerKnobs = listOf(Knob("sizeDp", 0, Type.INT), Knob("dark", 1, Type.BOOLEAN))

  private fun render(
    base: String,
    functionName: String = "KnobSticker",
    seeds: Map<String, String> = emptyMap(),
    knobs: List<Knob> = stickerKnobs,
  ): BufferedImage {
    val out = File(tempFolder.newFolder(base), "$base.png")
    renderPreview(
      className = fixtureClass,
      functionName = functionName,
      widthPx = 400,
      heightPx = 400,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0xFFFFFFFF,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = true,
      wrapHeight = true,
      previewArgs = PreviewKnobArguments.bind(knobs, seeds),
      localeTag = null,
      captureGutter = PreviewCaptureGutter.None,
    )
    assertTrue("rendered PNG must exist: ${out.absolutePath}", out.exists() && out.length() > 0)
    return ByteArrayInputStream(out.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
  }

  private fun BufferedImage.bytes(): List<Int> =
    (0 until height).flatMap { y -> (0 until width).map { x -> getRGB(x, y) } }

  @Test
  fun `an unseeded preview renders its author defaults`() {
    // The zero-seed case must go through the same path a plain render does: `bind` returns an empty
    // list, the renderer invokes with no arguments, and every parameter takes its default.
    val default = render("default")
    assertEquals(40, default.width)
    assertEquals(40, default.height)
  }

  @Test
  fun `seeding a knob changes the render`() {
    val default = render("size-default")
    val seeded = render("size-seeded", seeds = mapOf("sizeDp" to "90"))

    assertEquals(90, seeded.width)
    assertEquals(90, seeded.height)
    assertNotEquals(default.width, seeded.width)
  }

  @Test
  fun `an unseeded parameter keeps its author default when a sibling is seeded`() {
    // The load-bearing assertion. Seeding only `sizeDp` must leave `dark` at `false`, so the badge
    // stays red — the same pixels as the unseeded render, merely larger. A binding that passed a
    // zero value instead of setting the $default mask bit would render the dark colour here.
    val seededSize = render("sibling-size", seeds = mapOf("sizeDp" to "90"))
    val seededBoth = render("sibling-both", seeds = mapOf("sizeDp" to "90", "dark" to "false"))

    assertEquals(seededBoth.bytes(), seededSize.bytes())
  }

  @Test
  fun `seeding the other knob flips only that parameter`() {
    val light = render("colour-light", seeds = mapOf("sizeDp" to "60"))
    val dark = render("colour-dark", seeds = mapOf("sizeDp" to "60", "dark" to "true"))

    assertEquals(light.width, dark.width)
    assertNotEquals(light.getRGB(30, 30), dark.getRGB(30, 30))
  }

  @Test
  fun `an unparseable seed renders the author default rather than a coerced value`() {
    // Dropping the seed is visible and recoverable; coercing it would publish a capture that
    // silently disagrees with what the client asked for.
    val default = render("bad-default")
    val bad = render("bad-seeded", seeds = mapOf("sizeDp" to "enormous"))

    assertEquals(default.bytes(), bad.bytes())
  }

  @Test
  fun `a knob is bound by its position in the full parameter list`() {
    // `OffsetKnobSticker`'s seedable parameter is at index 1, behind an unseedable one. Binding it
    // at index 0 (its position among the knobs) would pass an Int where a List is declared and
    // fail the invoke, so a correct render here is the whole check.
    val seeded =
      render(
        "offset",
        functionName = "OffsetKnobSticker",
        seeds = mapOf("sizeDp" to "70"),
        knobs = listOf(Knob("sizeDp", 1, Type.INT)),
      )

    assertEquals(70, seeded.width)
  }
}
