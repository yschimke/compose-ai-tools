package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A card in a **Themes** tab renders a named theme as its subject. Re-rendering it under a
 * `themeProvider` override destroys the very thing it documents, so it keeps its baked pixels.
 *
 * The case that surfaced this, on meshcore-mobile: `Theme/MeshCore-Light` is captioned "MeshCore ·
 * Light · Orbitron / Space Grotesk / JetBrains Mono", and under a Dynamic Dark override the card
 * drew dark in the default sans — a specimen whose pixels contradicted its own label.
 */
class ServeWebThemeSpecimenTest {

  private val themes = listOf(ServeTheme(name = "Brand", providerFqn = "com.example.BrandTheme"))

  private fun page(previews: List<ServePreview>) =
    ServeWeb.landingPage(
      "meshcore-mobile",
      previews,
      token = "t",
      isPublic = true,
      basePath = "/meshcore-mobile",
      declaredThemes = themes,
      canRenderThemeFor = { true },
    )

  /**
   * The page emits its themed-render URLs as one JS array in grid document order (`var themeBase =
   * ["…", "", …]`). An entry is the URL that card re-requests under a declared theme; `""` means it
   * keeps its baked pixels.
   */
  private fun themeBases(html: String): List<String> {
    val decl =
      Regex("var themeBase = \\[(.*?)\\];", RegexOption.DOT_MATCHES_ALL)
        .find(html)
        ?.groupValues
        ?.get(1)
        ?: error("page emitted no themeBase array — declared themes were not offered at all")
    return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(decl).map { it.groupValues[1] }.toList()
  }

  /**
   * A specimen alongside an ordinary card — the real catalog shape, and the only one that emits a
   * `themeBase` array at all (a specimen-only catalog offers no chips, covered separately below).
   */
  private fun specimenAndComponent(specimenSection: String) =
    page(
      listOf(
        ServePreview(id = "theme-meshcore-light", label = "Theme", section = specimenSection),
        ServePreview(id = "contactrow-chat", label = "Contact row", section = "Components"),
      )
    )

  @Test
  fun `a themes-section specimen is not re-rendered under an override`() {
    val bases = themeBases(specimenAndComponent("Themes"))
    assertTrue(
      bases.none { it.contains("/render/theme-meshcore-light.png") },
      "a theme specimen must never gain a themeProvider render URL",
    )
    assertEquals(1, bases.count { it.isEmpty() }, "exactly the specimen keeps its baked pixels")
  }

  @Test
  fun `an ordinary card in the same catalog still re-renders`() {
    // The fix must not disable the whole Theme control — only the specimens opt out.
    val html = specimenAndComponent("Themes")
    assertTrue(
      themeBases(html).any { it.contains("/render/contactrow-chat.png") },
      "the component card still has a themed-render base",
    )
    assertTrue(html.contains("cp-theme-btn"), "the declared-theme chips remain offered")
  }

  @Test
  fun `the section match is case-insensitive`() {
    val bases = themeBases(specimenAndComponent("themes"))
    assertTrue(bases.none { it.contains("/render/theme-meshcore-light.png") })
  }

  @Test
  fun `a catalog of only specimens offers no declared-theme chips`() {
    // Otherwise the header advertises a control that redraws nothing: every themeBase would be "",
    // and the browser's `if (!img || !base) return` would skip every card. The chip gate and the
    // per-card URL must agree on eligibility.
    val html =
      page(
        listOf(
          ServePreview(id = "theme-light", label = "Theme Light", section = "Themes"),
          ServePreview(id = "theme-dark", label = "Theme Dark", section = "Themes"),
        )
      )
    assertFalse(
      html.contains("data-theme-choice=\"theme:com.example.BrandTheme\""),
      "no declared-theme chip when nothing the theme could redraw is renderable",
    )
  }

  @Test
  fun `a sectionless catalog is unaffected`() {
    // Plain bundles and uploaded catalogs carry no section at all; they must keep re-rendering.
    val html = page(listOf(ServePreview(id = "a", label = "A")))
    assertTrue(themeBases(html).any { it.contains("/render/a.png") })
  }

  @Test
  fun `a fixedTheme preview opts out with no section at all`() {
    // The `@FixedTheme` half: a specimen in a plain bundle (or a section that isn't "Themes") has
    // nothing but the annotation to speak for it.
    val html =
      page(
        listOf(
          ServePreview(id = "themecatalog__brand", label = "Brand theme", fixedTheme = true),
          ServePreview(id = "contactrow-chat", label = "Contact row"),
        )
      )
    val bases = themeBases(html)
    assertTrue(
      bases.none { it.contains("/render/themecatalog__brand.png") },
      "an @FixedTheme preview must never gain a themeProvider render URL",
    )
    assertTrue(
      bases.any { it.contains("/render/contactrow-chat.png") },
      "its neighbours still re-render — the control is not disabled wholesale",
    )
  }

  @Test
  fun `a fixedTheme preview in a Components section still opts out`() {
    // Section and annotation are independent signals; the wrong section must not override the
    // author's explicit per-preview statement.
    val bases =
      themeBases(
        page(
          listOf(
            ServePreview(
              id = "swatches",
              label = "Swatches",
              section = "Foundation",
              fixedTheme = true,
            ),
            ServePreview(id = "contactrow-chat", label = "Contact row", section = "Components"),
          )
        )
      )
    assertTrue(bases.none { it.contains("/render/swatches.png") })
    assertTrue(bases.any { it.contains("/render/contactrow-chat.png") })
  }

  /**
   * The landing withholding a specimen's themed-render URL is only half the surface. Opening the
   * card at `/p/<id>` hands the viewer its own copy of the declared themes, and that selector
   * re-renders through the same `themeProvider` override — so without this the annotation stopped
   * working the moment a visitor clicked the card.
   */
  private fun viewer(preview: ServePreview) =
    ServeWeb.viewerPage(
      preview,
      token = "t",
      canApplyOverrides = true,
      declaredThemes = themes,
      siblings = listOf(preview),
    )

  @Test
  fun `the viewer offers no app-theme options for a fixedTheme preview`() {
    val html = viewer(ServePreview(id = "themecatalog__brand", label = "Brand", fixedTheme = true))
    assertFalse(
      html.contains("theme:com.example.BrandTheme"),
      "the specimen's viewer must not offer a theme that would redraw it",
    )
    assertTrue(
      html.contains("data-has-declared-themes=\"false\""),
      "and the selector must agree it has none, so no script re-enables it",
    )
  }

  @Test
  fun `the viewer disables Day-Night for a specimen too, at runtime as well as in the markup`() {
    // Day/Night is NOT a navigation control: it maps to a `uiMode` override, and
    // `CatalogLiveRouting.overridesAffectRender` routes a uiMode differing from the id's baked
    // `__light`/`__dark` segment to a fresh daemon render. Left enabled it either redraws a
    // supposedly fixed sheet in the opposite mode, or reads "Night" over unchanged light pixels.
    val html = viewer(ServePreview(id = "themecatalog__brand", label = "Brand", fixedTheme = true))
    assertTrue(html.contains("data-fixed-theme=\"true\""))
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]* disabled>").containsMatchIn(html),
      "the whole Theme control is disabled in the markup",
    )
    // The markup alone is not enough: viewer.js reassigns `themeChoice.disabled` from the lane
    // flags on every state change, so it has to consult the same signal or it re-enables it.
    val script = viewerSource()
    assertTrue(
      script.contains("data-fixed-theme"),
      "viewer.js must gate on the flag, or its recompute undoes the server's disabled attribute",
    )
  }

  @Test
  fun `an ordinary preview's Theme control is not disabled`() {
    val html = viewer(ServePreview(id = "contactrow-chat", label = "Contact row"))
    assertTrue(html.contains("data-fixed-theme=\"false\""))
    assertFalse(Regex("<select id=\"cp-theme\"[^>]* disabled>").containsMatchIn(html))
  }

  @Test
  fun `the viewer withholds them for a Themes-section specimen too`() {
    // The section signal has to reach the viewer as well — it is the one meshcore-mobile uses.
    val html =
      viewer(ServePreview(id = "theme-meshcore-light", label = "Theme", section = "Themes"))
    assertFalse(html.contains("theme:com.example.BrandTheme"))
  }

  @Test
  fun `an ordinary preview's viewer keeps its theme options`() {
    val html = viewer(ServePreview(id = "contactrow-chat", label = "Contact row"))
    assertTrue(
      html.contains("theme:com.example.BrandTheme"),
      "the fix must not disable the viewer's Theme control everywhere",
    )
  }

  @Test
  fun `a catalog of only fixedTheme previews offers no declared-theme chips`() {
    // Same dead-control guard as the section-only case: the chip gate and the per-card URL must
    // agree on eligibility, whichever signal decided it.
    val html =
      page(
        listOf(
          ServePreview(id = "themecatalog__light", label = "Light", fixedTheme = true),
          ServePreview(id = "themecatalog__dark", label = "Dark", fixedTheme = true),
        )
      )
    assertFalse(
      html.contains("data-theme-choice=\"theme:com.example.BrandTheme\""),
      "no declared-theme chip when nothing the theme could redraw is renderable",
    )
  }
}
