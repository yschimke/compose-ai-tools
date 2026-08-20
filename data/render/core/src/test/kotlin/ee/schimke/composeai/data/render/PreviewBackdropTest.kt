package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [PreviewBackdrop]'s chain. The ordering is the contract — every consumer that used to guess
 * a ground now defers to this, so a rung moving silently changes what a whole catalog is scored and
 * displayed against.
 */
class PreviewBackdropTest {

  @Test
  fun `an explicit backgroundColor outranks everything below it`() {
    val backdrop =
      PreviewBackdrop.resolve(
        showBackground = true,
        backgroundColor = 0xFF112233L,
        night = true,
        themeSurface = "#FF445566",
        catalogSurface = PreviewBackdrop.CatalogSurface.DARK,
      )
    assertEquals("#FF112233", backdrop.color)
    assertEquals(PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR, backdrop.source)
  }

  @Test
  fun `showBackground names the same sheets PreviewBackground paints`() {
    val day = PreviewBackdrop.resolve(showBackground = true, night = false)
    val night = PreviewBackdrop.resolve(showBackground = true, night = true)
    // The published backdrop and the painted pixels must never name different colours for the same
    // preview — that is the whole reason this delegates to PreviewBackground's constants.
    assertEquals("#%08X".format(PreviewBackground.DAY_ARGB), day.color)
    assertEquals("#%08X".format(PreviewBackground.NIGHT_ARGB), night.color)
    assertEquals(PreviewBackdrop.Source.PREVIEW_SHOW_BACKGROUND, night.source)
  }

  @Test
  fun `a captured theme surface answers for a transparent capture`() {
    val backdrop = PreviewBackdrop.resolve(themeSurface = "#ff1c1b1f")
    assertEquals("#FF1C1B1F", backdrop.color)
    assertEquals(PreviewBackdrop.Source.THEME_SURFACE, backdrop.source)
  }

  @Test
  fun `a fully transparent theme surface is not an answer`() {
    // Publishing #00000000 would have consumers composite onto clear black, which is worse than
    // falling through to the catalog's declared stage.
    val backdrop =
      PreviewBackdrop.resolve(
        themeSurface = "#00000000",
        catalogSurface = PreviewBackdrop.CatalogSurface.DARK,
      )
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, backdrop.source)
  }

  @Test
  fun `a dark-first catalog answers for a sticker that declares nothing`() {
    // The wear-m3-catalog case: `@Preview(showBackground = false)` so the sticker stays droppable
    // onto any Figma canvas, and the catalog's own `display.surface` supplies the stage.
    val backdrop = PreviewBackdrop.resolve(catalogSurface = PreviewBackdrop.CatalogSurface.DARK)
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, backdrop.source)
    assertTrue(backdrop.isDark)
  }

  @Test
  fun `a nonzero but fully transparent explicit colour is not a ground`() {
    // `0` is the annotation's "unset"; `0x00FFFFFF` is a stated colour that still paints nothing.
    // Publishing it would hand a consumer a ground that isn't one and strand the catalog's stage.
    val backdrop =
      PreviewBackdrop.resolve(
        backgroundColor = 0x00FFFFFFL,
        catalogSurface = PreviewBackdrop.CatalogSurface.DARK,
      )
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, backdrop.source)
    assertTrue(backdrop.isDark)
  }

  @Test
  fun `a barely-opaque explicit colour still counts`() {
    // Only *fully* transparent falls through — a low-alpha wash is still the author stating one.
    val backdrop = PreviewBackdrop.resolve(backgroundColor = 0x01FFFFFFL)
    assertEquals(PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR, backdrop.source)
    assertEquals("#01FFFFFF", backdrop.color)
  }

  @Test
  fun `a dark variant inside a light-first catalog keeps a dark ground`() {
    // The catalog's stage speaks for the system, not for a variant that contradicts it: a dark
    // variant's light-on-transparent artwork needs a dark ground wherever it lives.
    val backdrop =
      PreviewBackdrop.resolve(
        variantSurface = PreviewBackdrop.CatalogSurface.DARK,
        catalogSurface = PreviewBackdrop.CatalogSurface.LIGHT,
      )
    assertEquals(PreviewBackdrop.Source.PREVIEW_VARIANT, backdrop.source)
    assertTrue(backdrop.isDark)
  }

  @Test
  fun `a stated ground still outranks the variant`() {
    val backdrop =
      PreviewBackdrop.resolve(
        backgroundColor = 0xFFFFFFFFL,
        variantSurface = PreviewBackdrop.CatalogSurface.DARK,
      )
    assertEquals(PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR, backdrop.source)
    assertFalse(backdrop.isDark)
  }

  @Test
  fun `an unthemed preview still falls through to the catalog stage`() {
    val backdrop =
      PreviewBackdrop.resolve(
        variantSurface = null,
        catalogSurface = PreviewBackdrop.CatalogSurface.DARK,
      )
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, backdrop.source)
  }

  @Test
  fun `nothing to say yields no colour rather than an invented one`() {
    val backdrop = PreviewBackdrop.resolve()
    assertNull(backdrop.color)
    assertEquals(PreviewBackdrop.Source.NONE, backdrop.source)
    assertFalse(backdrop.isDark)
  }

  @Test
  fun `a preview that spoke for itself keeps its ground in a dark-first catalog`() {
    // The per-preview half of "per-preview with catalog defaults": an explicitly white specimen in
    // a dark-first system must not be repainted black by the catalog's stage.
    val published = PreviewBackdrop.resolve(showBackground = true, night = false)
    val joined = PreviewBackdrop.withCatalogDefault(published, PreviewBackdrop.CatalogSurface.DARK)
    assertEquals(PreviewBackdrop.Source.PREVIEW_SHOW_BACKGROUND, joined.source)
    assertFalse(joined.isDark)
  }

  @Test
  fun `a preview with nothing to say takes the catalog stage at join time`() {
    val published = PreviewBackdrop.resolve()
    val joined = PreviewBackdrop.withCatalogDefault(published, PreviewBackdrop.CatalogSurface.DARK)
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, joined.source)
    assertTrue(joined.isDark)
  }

  @Test
  fun `an absent published backdrop is the same as one with nothing to say`() {
    val joined = PreviewBackdrop.withCatalogDefault(null, PreviewBackdrop.CatalogSurface.LIGHT)
    assertEquals(PreviewBackdrop.Source.CATALOG_SURFACE, joined.source)
    assertFalse(joined.isDark)
  }

  @Test
  fun `an unknown declared surface falls through instead of inventing a stage`() {
    assertNull(PreviewBackdrop.CatalogSurface.parse("chartreuse"))
    assertNull(PreviewBackdrop.CatalogSurface.parse(null))
    assertEquals(
      PreviewBackdrop.CatalogSurface.DARK,
      PreviewBackdrop.CatalogSurface.parse(" Dark "),
    )
  }

  @Test
  fun `darkness is a weighted luminance, crossing at about 737373 for grey`() {
    // The colours that actually matter: both M3 dark surfaces are dark, white is not.
    assertTrue(PreviewBackdrop.isDarkArgb("#FF000000"))
    assertTrue(PreviewBackdrop.isDarkArgb("#FF1C1B1F"))
    assertFalse(PreviewBackdrop.isDarkArgb("#FFFFFFFF"))
    // The crossing, pinned either side so a coefficient change can't drift it unnoticed.
    assertTrue(PreviewBackdrop.isDarkArgb("#FF6E6E6E"))
    assertFalse(PreviewBackdrop.isDarkArgb("#FF808080"))
    // Green carries most of the weight, so equal channel values are not equally dark.
    assertTrue(PreviewBackdrop.isDarkArgb("#FF0000FF"))
    assertFalse(PreviewBackdrop.isDarkArgb("#FF00FF00"))
  }

  @Test
  fun `a malformed colour is not reported as dark`() {
    // Better to leave a stage alone than to black it out on a value we could not read.
    assertFalse(PreviewBackdrop.isDarkArgb("#nope"))
    assertFalse(PreviewBackdrop.isDarkArgb(""))
    // A bare #RRGGBB is readable and must resolve the same as its opaque ARGB form.
    assertTrue(PreviewBackdrop.isDarkArgb("#1C1B1F"))
  }

  @Test
  fun `sources round-trip through their wire strings`() {
    for (source in PreviewBackdrop.Source.entries) {
      assertEquals(source, PreviewBackdrop.Source.fromWire(source.wire))
    }
    assertNull(PreviewBackdrop.Source.fromWire("preview.somethingElse"))
  }
}
