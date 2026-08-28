package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.CropOffset
import ee.schimke.composeai.imagecrop.RenderSize
import ee.schimke.composeai.imagecrop.WindowSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the HTML wiring of the prebaked grid thumbnails: a catalog card points at
 * `/render/<id>.png?thumb=<hash>` — the same lane, the same id, one extra param — so the page ships
 * a downscaled copy of each render instead of the full-resolution one, while every URL that layers
 * something on top (a declared theme) still resolves to a real render.
 *
 * The bake itself is covered by `ServeHeroImagesTest`; this is about the page.
 */
class ServeWebGridThumbnailTest {

  private val previews =
    listOf(ServePreview(id = "filled-button__ideal__default__compact", label = "Filled"))

  private val crop =
    ContentCrop(
      window = WindowSize(120, 48),
      render = RenderSize(454, 454),
      offset = CropOffset(-167, -203),
      nativeWindowW = 120,
      nativeCapAxis = 120,
    )

  private fun page(
    thumbHash: (String) -> String? = { null },
    thumbCrop: (String) -> ContentCrop? = { null },
    declaredThemes: List<ServeTheme> = emptyList(),
  ) =
    ServeWeb.landingPage(
      "compose-m3",
      previews,
      token = "t",
      isPublic = true,
      basePath = "/compose-m3",
      thumbCrop = thumbCrop,
      thumbHash = thumbHash,
      declaredThemes = declaredThemes,
      canRenderThemeFor = { true },
    )

  @Test
  fun `a card with a prebaked thumbnail asks for it by content hash`() {
    val html = page(thumbHash = { "abc123" })
    assertTrue(
      html.contains(
        "src=\"/compose-m3/render/filled-button__ideal__default__compact.png?thumb=abc123\""
      ),
      "the card's image is the prebaked thumbnail",
    )
  }

  @Test
  fun `a card with no baked pixels keeps the plain render URL`() {
    // Nothing to serve from memory yet — the card must still paint, off the ordinary lane, and
    // pick a thumbnail up on a later page build.
    val html = page()
    assertTrue(
      html.contains("src=\"/compose-m3/render/filled-button__ideal__default__compact.png\""),
      "unchanged for a card the server hasn't baked",
    )
    assertFalse(html.contains("thumb="), "and no thumbnail param anywhere on the page")
  }

  @Test
  fun `the thumb param is appended, never substituted for the session query`() {
    // A non-public session carries `?token=…` already; the hash has to join that query rather than
    // start a second one.
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        previews,
        token = "sekrit",
        isPublic = false,
        basePath = "",
        thumbHash = { "abc123" },
      )
    assertTrue(
      html.contains("render/filled-button__ideal__default__compact.png?token=sekrit&thumb=abc123"),
      "appended to the existing query",
    )
  }

  @Test
  fun `a framed card keeps its clip window around the thumbnail`() {
    // The crop stays in CSS precisely so the window frames the thumbnail and a later full render
    // identically — the geometry is in percentages, so it is resolution independent.
    val html = page(thumbHash = { "abc123" }, thumbCrop = { crop })
    assertTrue(
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:2.5;--cp-crop-max-w:120px;aspect-ratio:120/48\""
      ),
      "clip window still sized to the component box",
    )
    assertTrue(
      html.contains("?thumb=abc123\" style=\"width:378.3333%;left:-139.1667%;top:-422.9167%\""),
      "and it frames the prebaked thumbnail",
    )
  }

  @Test
  fun `picking a declared theme renders from the same URL, so it cannot be answered by a thumbnail`() {
    // The theme lane appends `themeProvider=` to the card's own URL. That extra param is what makes
    // the server decline the thumbnail and render for real; it also means leaving the theme
    // restores the cheap image rather than fetching a full render.
    val theme = ServeTheme(name = "Brand", providerFqn = "com.example.BrandTheme")
    val html = page(thumbHash = { "abc123" }, declaredThemes = listOf(theme))
    assertEquals(
      1,
      Regex("var themeBase = \\[\"[^\"]*\\?thumb=abc123\"\\]").findAll(html).count(),
      "the themed-render base is the card's own thumbnail URL",
    )
  }

  @Test
  fun `declared theme labels are qualified when they collide with baked modes`() {
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        listOf(
          ServePreview("button__ideal__default__light", "Button", theme = "light"),
          ServePreview("button__ideal__default__dark", "Button", theme = "dark"),
        ),
        token = "t",
        declaredThemes =
          listOf(
            ServeTheme("Light", "com.example.LightTheme", group = "Example"),
            ServeTheme("Dark", "com.example.DarkTheme", group = "Example"),
          ),
        canRenderThemeFor = { true },
      )

    assertTrue(html.contains("data-theme-choice=\"light\">Light</button>"))
    assertTrue(html.contains("data-theme-choice=\"dark\">Dark</button>"))
    assertTrue(
      html.contains(
        "data-theme-choice=\"theme:com.example.LightTheme\" data-theme-mode=\"light\">Example · Light</button>"
      )
    )
    assertTrue(
      html.contains(
        "data-theme-choice=\"theme:com.example.DarkTheme\" data-theme-mode=\"dark\">Example · Dark</button>"
      )
    )
  }

  @Test
  fun `duplicate breakpoint cards include their size in the label`() {
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        listOf(
          ServePreview("edgebutton__ideal__default__smallround", "Edgebutton"),
          ServePreview("edgebutton__ideal__default__largeround", "Edgebutton"),
          ServePreview("edgebutton__ideal__default__xlround", "Edgebutton"),
        ),
        token = "t",
      )

    assertTrue(html.contains(">Edgebutton · Small Round</div>"))
    assertTrue(html.contains(">Edgebutton · Large Round</div>"))
    assertTrue(html.contains(">Edgebutton · XL Round</div>"))
  }
}
