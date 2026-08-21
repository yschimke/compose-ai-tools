package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SHAPE of the stage a comparison is drawn on — the other half of `ServeWebBackdropTest`.
 *
 * Giving the reference-compare page a ground fixed a dark-first catalog's invisible stickers and
 * introduced a subtler version of the same fault for round devices: a Wear capture is a circle in a
 * square PNG, so a ground painted across the panel draws the watch as a rectangle. Because Wear
 * previews declare `backgroundColor = 0xFF000000` against near-black screens, the stage came out
 * pixel-identical to the screen on this repo's own `PageIndicatorScaffoldTemplate` renders and the
 * device boundary disappeared rather than merely looking square.
 */
class ServeWebStageClipTest {

  private val reference =
    DesignReference(
      id = "design-timetext",
      previewId = "timetext",
      label = "TimeText",
      raster = DesignReferenceRaster(path = "references/timetext.png"),
    )

  private fun preview(deviceFrame: ServeDeviceFrame? = null) =
    ServePreview(
      id = "timetext",
      label = "TimeText",
      showBackground = true,
      backgroundColor = 0xFF000000L,
      deviceFrame = deviceFrame,
    )

  private fun page(preview: ServePreview): String =
    ServeWeb.referenceComparisonPage(
      moduleLabel = "wear-m3",
      preview = preview,
      reference = reference,
      token = "t",
      sessionId = "wear-m3",
      declaredSurface = "dark",
    )

  @Test
  fun `a round device resolves to the circle inscribed in its own frame`() {
    val clip = ServeWeb.stageClipFor(preview(ServeDeviceFrame(227.0, 227.0, isRound = true)))
    assertEquals("circle(50% at 50% 50%)", clip)
  }

  @Test
  fun `a rectangular device gets no clip at all`() {
    // The overwhelming majority. Every pixel of a phone capture is screen, so a clip here would
    // crop real content — the failure mode is the opposite of the one this feature fixes and much
    // harder to notice, because a cropped corner still looks like a rendered component.
    assertNull(ServeWeb.stageClipFor(preview(ServeDeviceFrame(411.0, 891.0, isRound = false))))
  }

  @Test
  fun `a preview that names no device gets no clip`() {
    assertNull(ServeWeb.stageClipFor(preview(deviceFrame = null)))
  }

  @Test
  fun `the round stage reaches the page as a property AND a marker attribute`() {
    val html = page(preview(ServeDeviceFrame(227.0, 227.0, isRound = true)))
    assertTrue(html.contains("--cp-stage-clip: circle(50% at 50% 50%)"), html)
    // The marker is not redundant with the property. Clipping is not all the rules it gates do:
    // they also take the ground off the panel and hand it to the image, so the corners the clip
    // opens up fall through to the page's checkerboard instead of staying stage-coloured. CSS
    // cannot branch on whether a custom property was set, so without the marker every rectangular
    // preview would lose its panel ground to buy a clip it never uses.
    assertTrue(html.contains("data-cp-stage-clip=\"1\""), html)
  }

  @Test
  fun `a rectangular preview's page carries neither the clip nor the marker`() {
    val html = page(preview(ServeDeviceFrame(411.0, 891.0, isRound = false)))
    assertFalse(html.contains("--cp-stage-clip"), html)
    assertFalse(html.contains("data-cp-stage-clip"), html)
    // …and still gets its ground, which is the regression the marker exists to prevent.
    assertTrue(html.contains("--cp-stage-backdrop: #000000"), html)
  }

  @Test
  fun `roundness comes from the device string, not from explicitly sized dimensions`() {
    // The trap this pins: `DeviceDimensions.resolve` returns early with `isRound = false` as soon
    // as it is handed explicit width and height. Every Wear preview in this repo's catalog states
    // BOTH a `device =` and its dp, so asking for both at once reports the entire set as square,
    // and the feature is silently inert exactly where it is needed.
    val frame = ServeDeviceFrame.from("id:wearos_large_round", widthDp = 227, heightDp = 227)
    assertEquals(ServeDeviceFrame(227.0, 227.0, isRound = true), frame)
  }

  @Test
  fun `a device frame is resolved for its dimensions when the annotation states none`() {
    val frame = ServeDeviceFrame.from("id:wearos_small_round", widthDp = null, heightDp = null)
    assertTrue(frame!!.isRound, "$frame")
    assertEquals(frame.widthDp, frame.heightDp, "a watch face resolves square: $frame")
  }

  @Test
  fun `no device string means no frame`() {
    assertNull(ServeDeviceFrame.from(null, 411, 891))
    assertNull(ServeDeviceFrame.from("  ", 411, 891))
  }

  @Test
  fun `a single-axis dp hint does not displace the device, exactly as the renderer decides it`() {
    // `frameDpOverriddenBy` is both-axes-or-neither, and the renderer that produced the PNG applied
    // it. Deciding per axis here would put a 120×227 clip over a 227×227 render and crop live
    // screen off two sides — a circle in the wrong place, which is worse than no clip at all.
    val frame = ServeDeviceFrame.from("id:wearos_large_round", widthDp = 120, heightDp = null)
    assertEquals(ServeDeviceFrame(227.0, 227.0, isRound = true), frame)
  }

  @Test
  fun `a device override restates the clip from the device actually rendered`() {
    // The Actual panel takes `device=` through its asset query, so the comparison on screen is of
    // that frame. A square Wear choice must DROP the circle rather than crop the render it did not
    // describe…
    val round = preview(ServeDeviceFrame.from("id:wearos_large_round", 227, 227))
    assertNull(ServeWeb.stageClipFor(round, mapOf("device" to "id:wearos_square")))
    // …and the inverse must gain one, or a round render sits on a square stage.
    val phone = preview(ServeDeviceFrame.from("id:pixel_5", null, null))
    assertEquals(
      "circle(50% at 50% 50%)",
      ServeWeb.stageClipFor(phone, mapOf("device" to "id:wearos_large_round")),
    )
  }

  @Test
  fun `a size override suppresses the clip rather than guessing a circle for it`() {
    // `widthPx`/`heightPx` are pixels against a density this page does not carry, and `orientation`
    // re-derives the frame through rules that live in the resolver. A guess would be a circle in
    // the wrong place; the un-clipped stage at least never hides real pixels.
    val round = preview(ServeDeviceFrame.from("id:wearos_large_round", 227, 227))
    assertNull(ServeWeb.stageClipFor(round, mapOf("widthPx" to "480")))
    assertNull(ServeWeb.stageClipFor(round, mapOf("orientation" to "landscape")))
    // An empty value is not an override — it must not cost the clip.
    assertEquals(
      "circle(50% at 50% 50%)",
      ServeWeb.stageClipFor(round, mapOf("widthPx" to "")),
    )
  }
}
