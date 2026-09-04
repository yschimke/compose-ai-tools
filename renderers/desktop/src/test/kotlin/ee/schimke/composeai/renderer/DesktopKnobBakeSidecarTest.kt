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

  /** One capture through the real render entry, returning its PNG and the sidecar beside it. */
  private fun bake(
    base: String,
    knobs: List<PreviewKnobSpec> = stickerKnobs,
  ): Pair<File, File> {
    val out = File(tempFolder.newFolder(base), "$base.png")
    renderPreview(
      className = fixtureClass,
      functionName = "KnobSticker",
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
