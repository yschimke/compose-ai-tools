package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.FigmaSvgBackgroundMode
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
 * render painted, but nothing is injected unless this render asks for a [FigmaSvgBackgroundMode]
 * (or the daemon-wide `composeai.svg.background` default is set).
 *
 * The export's product is editable layers. An injected fill is the opposite — an opaque rect (or
 * device-mask circle) spanning the whole canvas that a designer has to find and delete before the
 * import is usable on their own canvas — and it is redundant besides: a preview that declares
 * `showBackground` is nearly always a screen whose own root already paints that colour, so the
 * injected layer landed directly on top of an identical one the tree drew itself. Hard to remove,
 * easy to add back — so it is requested, per preview, in one of three shapes: the device mask
 * ([FigmaSvgBackgroundMode.DEVICE]), the component's own silhouette
 * ([FigmaSvgBackgroundMode.CONTENT_SHAPE]), or a plain tile ([FigmaSvgBackgroundMode.FULL_BLEED]).
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

  private fun export(
    previewId: String,
    roundClip: Boolean,
    backgroundMode: FigmaSvgBackgroundMode? = null,
  ): String {
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout(),
      roundClip = roundClip,
      deviceBackground = "#FF000000",
      backgroundMode = backgroundMode,
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

  /**
   * The per-item seam: a single render asks to keep its background (the viewer's Background control
   * sending `PreviewOverrides(clearBackground = false)`) and gets the fill without the daemon-wide
   * property being on — a device screen, a tall scroll capsule, or an outlined button that needs
   * something to read against.
   */
  @Test
  fun `a per-render request injects the background with the global switch off`() {
    System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    val masked =
      export("wear-requested", roundClip = true, backgroundMode = FigmaSvgBackgroundMode.DEVICE)
    assertTrue(
      "an explicit per-render request must paint the device face:\n$masked",
      masked.contains("""<circle cx="100" cy="100" r="100" fill="#000000""""),
    )
    val maskless =
      export(
        "maskless-requested",
        roundClip = false,
        backgroundMode = FigmaSvgBackgroundMode.DEVICE,
      )
    assertTrue(
      "…and the maskless frame fill too:\n$maskless",
      maskless.contains("""fill="#000000""""),
    )
    // Neighbouring renders that said nothing stay background-free — the request is per item, not a
    // mode the first opt-in switches on for the rest of the session.
    val quiet = export("maskless-quiet", roundClip = false)
    assertFalse(
      "a render that asked for nothing must still export background-free:\n$quiet",
      quiet.contains("""fill="#000000""""),
    )
  }

  /**
   * `FULL_BLEED` on a masked device export paints the corners the mask cuts away — so the fill has
   * to be a plain rect, and has to sit *outside* the clip group or the mask would trim it back to
   * the very circle this mode exists to escape.
   */
  @Test
  fun `full bleed paints a square rect outside the device clip`() {
    System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    val svg =
      export("wear-bleed", roundClip = true, backgroundMode = FigmaSvgBackgroundMode.FULL_BLEED)
    assertFalse(
      "full bleed must not paint the mask circle:\n$svg",
      svg.contains("""<circle cx="100" cy="100" r="100" fill="#000000""""),
    )
    assertTrue(
      "full bleed must paint a full-frame rect:\n$svg",
      svg.contains("""<rect x="0" y="0" width="200" height="200" fill="#000000""""),
    )
    // The rect precedes the clipped group, so the device mask never reaches it.
    val rectAt = svg.indexOf("""<rect x="0" y="0" width="200" height="200" fill="#000000"""")
    val clippedGroupAt = svg.indexOf("""clip-path="url(#deviceRound)"""")
    assertTrue(
      "the full-bleed rect must be emitted before the clipped group:\n$svg",
      rectAt in 0 until clippedGroupAt,
    )
  }

  /**
   * `CONTENT_SHAPE` hugs the component instead of tiling the canvas — the case an outlined button
   * wants: a filled pill exactly under its outline, not a square behind it.
   */
  @Test
  fun `content shape paints the component silhouette`() {
    System.clearProperty(ComposeFigmaSvgDataProducer.PROP_BACKGROUND)
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = "pill",
      layout = outlinedButtonLayout(),
      deviceBackground = "#FF000000",
      backgroundMode = FigmaSvgBackgroundMode.CONTENT_SHAPE,
    )
    val svg = rootDir.resolve("pill").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue(
      "the injected fill must take the button's own bounds and radius:\n$svg",
      svg.contains("""<rect x="20" y="60" width="160" height="80" rx="40" ry="40" fill="#000000""""),
    )
    assertFalse(
      "…and must not also tile the whole canvas:\n$svg",
      svg.contains("""<rect x="0" y="0" width="200" height="200" fill="#000000""""),
    )
  }

  /** An unshaped root wrapping a 160×80 pill — the shape an OutlinedButton's container reports. */
  private fun outlinedButtonLayout() =
    LayoutInspectorPayload(
      LayoutInspectorNode(
        nodeId = "Root",
        component = "Box",
        bounds = LayoutInspectorBounds(0, 0, 200, 200),
        size = LayoutInspectorSize(200, 200),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "Button",
              component = "OutlinedButton",
              bounds = LayoutInspectorBounds(20, 60, 180, 140),
              size = LayoutInspectorSize(160, 80),
              tokens = ComposeSemanticsTokens(cornerRadiusPx = "40.0px"),
            )
          ),
      )
    )
}
