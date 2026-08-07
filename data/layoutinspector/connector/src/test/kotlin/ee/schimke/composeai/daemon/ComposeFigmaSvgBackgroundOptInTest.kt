package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `compose/figma-svg` export ships **background-free**: a caller may hand it the colour the
 * render painted, but nothing is injected unless `composeai.svg.background=true`.
 *
 * The export's product is editable layers. An injected fill is the opposite — an opaque rect (or
 * device-mask circle) spanning the whole canvas that a designer has to find and delete before the
 * import is usable on their own canvas — and it is redundant besides: a preview that declares
 * `showBackground` is nearly always a screen whose own root already paints that colour, so the
 * injected layer landed directly on top of an identical one the tree drew itself.
 */
class ComposeFigmaSvgBackgroundOptInTest {
  private lateinit var rootDir: File
  private var priorBackground: String? = null

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-figma-bg-test").toFile()
    priorBackground = System.getProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
  }

  @After
  fun tearDown() {
    priorBackground?.let { System.setProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND, it) }
      ?: System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    rootDir.deleteRecursively()
  }

  /** A 200×200 screen whose own root paints the same colour the preview declared. */
  private fun layout() =
    LayoutInspectorPayload(
      LayoutInspectorNode(
        nodeId = "Screen",
        component = "Screen",
        bounds = LayoutInspectorBounds(0, 0, 200, 200),
        size = LayoutInspectorSize(200, 200),
        children = emptyList(),
      )
    )

  private fun export(previewId: String, roundClip: Boolean): String {
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout(),
      roundClip = roundClip,
      deviceBackground = "#FF000000",
    )
    return rootDir.resolve(previewId).resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
  }

  @Test
  fun `a masked device export injects no watch face by default`() {
    System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    val svg = export("wear-default", roundClip = true)
    assertFalse(
      "no black device face may be injected by default:\n$svg",
      svg.contains("""<circle cx="100" cy="100" r="100" fill="#000000""""),
    )
    // The mask itself is unaffected — it is geometry, not paint.
    assertTrue(
      "the device mask still clips the tree:\n$svg",
      svg.contains("""clipPath id="deviceRound""""),
    )
  }

  @Test
  fun `a maskless showBackground export injects no fill by default`() {
    System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    val svg = export("maskless-default", roundClip = false)
    assertFalse(
      "no full-canvas background rect may be injected by default:\n$svg",
      svg.contains("""fill="#000000""""),
    )
  }

  @Test
  fun `the opt-in restores the injected background`() {
    System.setProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND, "true")
    val masked = export("wear-optin", roundClip = true)
    assertTrue(
      "composeai.svg.background=true must paint the device face again:\n$masked",
      masked.contains("""<circle cx="100" cy="100" r="100" fill="#000000""""),
    )
    val maskless = export("maskless-optin", roundClip = false)
    assertTrue(
      "…and the maskless frame fill too:\n$maskless",
      maskless.contains("""fill="#000000""""),
    )
  }
}
