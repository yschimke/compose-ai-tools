package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.render.PreviewBackdrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stage a comparison is drawn on — yschimke/wear-m3-catalog#56.
 *
 * The reference-compare page was the one comparison surface with no ground of its own, so its three
 * panels fell through to the `.cp-compare-shot` checkerboard. A dark-first catalog renders its
 * stickers transparent on purpose (`@Preview(showBackground = false)`, so a designer can drop one
 * onto any Figma canvas), which meant its white-on-transparent content was very nearly invisible in
 * the Actual panel — on the page whose entire job is to show it next to its reference.
 *
 * These pin both halves of the rule the fix is built on: the catalog's declared stage answers for a
 * preview that says nothing, and a preview that states its own ground keeps it.
 */
class ServeWebBackdropTest {

  private val reference =
    DesignReference(
      id = "design-button-filled",
      previewId = "button__dark",
      label = "Button",
      raster = DesignReferenceRaster(path = "references/button.png"),
    )

  private fun preview(
    id: String = "button__dark",
    showBackground: Boolean = false,
    backgroundColor: Long = 0L,
    uiMode: Int = 0,
    theme: String? = null,
  ) =
    ServePreview(
      id = id,
      label = id,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      uiMode = uiMode,
      theme = theme,
    )

  private fun page(
    preview: ServePreview,
    declaredSurface: String?,
    overrides: Map<String, String> = emptyMap(),
  ): String =
    ServeWeb.referenceComparisonPage(
      moduleLabel = "wear-m3",
      preview = preview,
      reference = reference,
      token = "t",
      sessionId = "wear-m3",
      declaredSurface = declaredSurface,
      overrides = overrides,
    )

  @Test
  fun `a dark-first catalog's comparison is drawn on its own dark stage`() {
    val html = page(preview(), declaredSurface = "dark")
    assertTrue(
      html.contains("id=\"cp-reference-compare\"") && html.contains("data-bg-theme=\"dark\""),
      "the dark-first stage reaches the page: $html",
    )
    // The exact colour rides along, so a catalog whose ground is neither literal plate still gets
    // its own rather than the nearest of the two.
    assertTrue(
      html.contains("--cp-stage-backdrop: #1C1B1F"),
      "the resolved backdrop colour is inlined: $html",
    )
  }

  @Test
  fun `a light-first catalog still gets a solid stage rather than the checkerboard`() {
    val html = page(preview(id = "button__light"), declaredSurface = "light")
    assertTrue(html.contains("data-bg-theme=\"light\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #FFFFFF"), html)
  }

  @Test
  fun `a preview that states its own ground keeps it inside a dark-first catalog`() {
    // The per-preview half. An explicitly white specimen in a dark-first system is the author
    // saying so, and must not be repainted black by the catalog's stage.
    val html =
      page(preview(showBackground = true, backgroundColor = 0xFFFFFFFFL), declaredSurface = "dark")
    assertTrue(html.contains("data-bg-theme=\"light\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #FFFFFF"), html)
  }

  @Test
  fun `a night-uiMode showBackground preview is staged on the sheet the renderer actually paints`() {
    // `showBackground` under a night uiMode paints M3's dark sheet, not white — see
    // PreviewBackground. Staging it on white would contradict its own pixels.
    val html = page(preview(showBackground = true, uiMode = 0x20), declaredSurface = "light")
    assertTrue(html.contains("data-bg-theme=\"dark\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #1C1B1F"), html)
  }

  @Test
  fun `a dark variant in a light-first catalog opens on a dark stage`() {
    // Stepping from a dark row on the compare wall into its focused view must not flip the ground:
    // the wall and the viewer both show that render dark, and this page used to answer light
    // because it consulted the catalog's stage and not the variant.
    val html = page(preview(theme = "dark"), declaredSurface = "light")
    assertTrue(html.contains("data-bg-theme=\"dark\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #1C1B1F"), html)
  }

  @Test
  fun `a night uiMode stands in for an absent variant token`() {
    val html = page(preview(uiMode = 0x20), declaredSurface = "light")
    assertTrue(html.contains("data-bg-theme=\"dark\""), html)
  }

  @Test
  fun `a stated ground still wins over the variant it contradicts`() {
    val html =
      page(preview(theme = "dark", backgroundColor = 0xFFFFFFFFL), declaredSurface = "dark")
    assertTrue(html.contains("data-bg-theme=\"light\""), html)
  }

  @Test
  fun `a uiMode override moves the stage with the pixels it renders`() {
    // Both panels take the override through `assetQuery`, so the Actual really is dark here. A
    // stage still resolved from the preview's discovery-time uiMode would describe a different
    // render than the one on screen.
    val html = page(preview(), declaredSurface = "light", overrides = mapOf("uiMode" to "dark"))
    assertTrue(html.contains("data-bg-theme=\"dark\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #1C1B1F"), html)
  }

  @Test
  fun `a uiMode override also flips a showBackground preview's sheet`() {
    val html =
      page(
        preview(showBackground = true, uiMode = 0x20),
        declaredSurface = "dark",
        overrides = mapOf("uiMode" to "light"),
      )
    assertTrue(html.contains("data-bg-theme=\"light\""), html)
    assertTrue(html.contains("--cp-stage-backdrop: #FFFFFF"), html)
  }

  @Test
  fun `the resolution is the shared chain, not a second opinion`() {
    // Guards against the failure this whole change exists to prevent: a page growing its own idea
    // of the ground that drifts from what every other surface uses.
    val sticker = ServeWeb.backdropFor(preview(), darkFirst = true)
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, sticker.source)
    assertTrue(sticker.isDark)

    val stated = ServeWeb.backdropFor(preview(backgroundColor = 0xFF00FF00L), darkFirst = true)
    assertEquals(PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR, stated.source)
    assertEquals("#FF00FF00", stated.color)
    assertFalse(stated.isDark)
  }
}
