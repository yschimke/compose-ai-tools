package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof of the **desktop bake lane** for parameter knobs: a preview written as plain
 * Compose — no harness call, no controller, no `previewOverride*` — leaves an
 * `<stem>.overrides.json` beside its PNG describing the controls a viewer should draw.
 *
 * That sidecar is what `compose-preview serve` reads its override list from, for a daemon-backed
 * host too (the daemon supplies renders, not declarations), so before this the whole knob format
 * was invisible to `serve` for anything baked offline. [DesktopKnobRendererTest] covers the pixels
 * a seed produces; this covers what the capture *announces*, which is the half that was missing.
 */
class DesktopKnobBakeSidecarTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.KnobRenderTestFixturesKt"

  /** [KnobSticker]'s knobs, exactly as `previews.json` records them — defaults included. */
  private val stickerKnobs =
    listOf(
      PreviewKnobSpec("sizeDp", 0, "INT", "40"),
      PreviewKnobSpec("dark", 1, "BOOLEAN", "false"),
    )

  /** [EnumKnobSticker]'s one knob, as `previews.json` records a closed set. */
  private val enumKnobs =
    listOf(PreviewKnobSpec("size", 0, "ENUM", "Small", listOf("Small", "Medium", "Large")))

  @After
  fun clearController() {
    ee.schimke.composeai.overrides.PreviewOverrideController.resetForNewSession()
  }

  @Test
  fun `a baked preview publishes its parameter knobs as declarations`() {
    val (png, sidecar) = bake("unseeded")

    assertTrue("render wrote no PNG", png.isFile && png.length() > 0)
    assertTrue("no overrides sidecar beside the render: ${sidecar.absolutePath}", sidecar.isFile)

    val json = sidecar.readText()
    // Both knobs declared, at the literal defaults discovery read out of the compiled body.
    assertTrue(json, json.contains(""""key":"sizeDp""""))
    assertTrue(json, json.contains(""""key":"dark""""))
    // …and nothing seeded, so what is in force is what the author wrote.
    assertEquals(
      "a viewer would show a `current` that disagrees with the pixels beside it",
      2,
      Regex("\"current\"").findAll(json).count(),
    )
    assertTrue(json, json.contains("40"))
  }

  @Test
  fun `a seeded knob reaches the pixels and the declaration together`() {
    ee.schimke.composeai.overrides.PreviewOverrideController.set(
      mapOf("sizeDp" to PreviewOverrideValue.IntValue(90))
    )
    val (png, sidecar) = bake("seeded")

    // The seed bound as an argument — this is the whole path under test: a manifest-shaped knob
    // list plus a `namedOverrides` seed, with no `previewOverride*` call anywhere in the fixture.
    val image = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    assertEquals("the seed did not reach the parameter", 90, image.width)

    val json = sidecar.readText()
    // The unseeded sibling still declares, and still reports its author default — a partial seed
    // must not blank the knobs it did not name.
    assertTrue(json, json.contains(""""key":"dark""""))
    assertTrue("the declaration did not follow the seed: $json", json.contains("90"))
  }

  @Test
  fun `a preview with no knobs writes no sidecar, exactly as before`() {
    val (png, sidecar) = bake("plain", knobs = emptyList())
    assertTrue("render wrote no PNG", png.isFile && png.length() > 0)
    assertTrue(
      "an unknobbed preview must not start emitting an empty override list",
      !sidecar.exists(),
    )
  }

  @Test
  fun `an enum knob is declared as a closed, exhaustive set of options`() {
    val (_, sidecar) = bake("enum-unseeded", knobs = enumKnobs, function = "EnumKnobSticker")

    val json = sidecar.readText()
    assertTrue(json, json.contains(""""key":"size""""))
    // The whole reason the kind exists. Without these a viewer draws a text box, which shows the
    // current value and hides every alternative — so `Large` would be reachable only by someone who
    // had read the source, which is exactly the regression a `previewOverrideChoice` migration
    // would otherwise be.
    assertTrue("no options on the declaration: $json", json.contains(""""options""""))
    assertTrue("the picker is not exhaustive: $json", json.contains(""""optionsExhaustive":true"""))
    for (constant in listOf("Small", "Medium", "Large")) {
      assertTrue("option $constant missing: $json", json.contains(constant))
    }
  }

  @Test
  fun `an enum seed binds by constant name and reaches the pixels`() {
    ee.schimke.composeai.overrides.PreviewOverrideController.set(
      mapOf("size" to PreviewOverrideValue.StringValue("Large"))
    )
    val (png, sidecar) = bake("enum-seeded", knobs = enumKnobs, function = "EnumKnobSticker")

    // The seed crosses the process boundary as the constant's NAME and becomes the constant itself
    // at the invoke seam — the one conversion the format could not previously make.
    val image = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    assertEquals("the enum seed did not reach the parameter", 120, image.width)
    assertTrue("the declaration did not follow the seed", sidecar.readText().contains("Large"))
  }

  @Test
  fun `a seed naming no constant of the enum falls back to the author default`() {
    ee.schimke.composeai.overrides.PreviewOverrideController.set(
      mapOf("size" to PreviewOverrideValue.StringValue("Enormous"))
    )
    val (png, _) = bake("enum-bogus", knobs = enumKnobs, function = "EnumKnobSticker")

    // Dropped, not coerced and not fatal: a constant the enum does not have is what a stale client
    // sends after a rename, and rendering the author default is the honest answer to it.
    val image = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    assertEquals("a bogus enum seed must render the default", 40, image.width)
  }

  /** One capture through the real render entry, returning its PNG and the sidecar beside it. */
  private fun bake(
    base: String,
    knobs: List<PreviewKnobSpec> = stickerKnobs,
    function: String = "KnobSticker",
  ): Pair<File, File> {
    val out = File(tempFolder.newFolder(base), "$base.png")
    renderPreview(
      className = fixtureClass,
      functionName = function,
      widthPx = 400,
      heightPx = 400,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0xFFFFFFFF,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = true,
      wrapHeight = true,
      previewArgs = emptyList(),
      localeTag = null,
      captureGutter = PreviewCaptureGutter.None,
      knobs = knobs,
    )
    return out to File(out.parentFile, "${out.nameWithoutExtension}.overrides.json")
  }
}
