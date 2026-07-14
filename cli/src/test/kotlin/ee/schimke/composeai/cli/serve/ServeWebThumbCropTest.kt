package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the HTML wiring of the server-side thumbnail crop: when a card's [ContentCrop] is present
 * the render `<img>` is wrapped in a fixed-size `.cp-crop` clip window (so a Wear sticker shows the
 * component, not its watch canvas); when absent the card keeps the plain fit-to-box `<img>` — so a
 * phone/desktop catalog and the plain-module landing are untouched.
 */
class ServeWebThumbCropTest {

  private val previews =
    listOf(ServePreview(id = "filled-button__ideal__default__compact", label = "Filled"))
  private val crop =
    ContentCrop(boxW = 120, boxH = 48, imgW = 454, imgH = 454, left = -167, top = -203)

  @Test
  fun `a catalog card with a crop wraps the image in a sized clip window`() {
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { crop },
      )
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;height:48px\""),
      "clip window sized to the box",
    )
    assertTrue(
      html.contains("style=\"width:454px;height:454px;left:-167px;top:-203px\""),
      "render img sized + offset to show only the component",
    )
  }

  @Test
  fun `a catalog card with no crop keeps the plain image (no clip window)`() {
    val html = ServeWeb.landingPage("compose-m3", previews, token = "t", basePath = "/compose-m3")
    // The `.cp-crop` CSS rule ships on every page; assert the absence of the *wrapper element*.
    assertFalse(html.contains("class=\"cp-crop\""), "uncropped cards carry no clip window")
    assertTrue(html.contains("<img loading=\"lazy\" alt=\"Filled\""), "plain fit-to-box image")
  }

  @Test
  fun `the home hero card is framed when the system carries a hero crop`() {
    val system =
      ServeWeb.HomeSystem(
        system = "wear-m3",
        title = "Wear Compose Material 3",
        subtitle = null,
        previewCount = 34,
        trust = null,
        heroPreviewId = "filled-button__ideal__default__compact",
        heroCrop = crop,
      )
    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;height:48px\""),
      "hero framed to its box",
    )
  }

  @Test
  fun `the crop CSS is present so the clip window actually clips`() {
    val html = ServeWeb.landingPage("wear-m3", previews, token = "t", thumbCrop = { crop })
    assertTrue(
      html.contains(".cp-crop { position: relative; overflow: hidden;"),
      "clip style shipped",
    )
    assertTrue(
      html.contains(".cp-imgwrap .cp-crop img { position: absolute; max-width: none;"),
      "img escapes the fit-to-box cap",
    )
  }
}
