package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun `a themes-section specimen is not re-rendered under an override`() {
    val html =
      page(
        listOf(
          ServePreview(
            id = "theme-meshcore-light",
            label = "Theme MeshCore Light",
            section = "Themes",
          )
        )
      )
    assertEquals(
      listOf(""),
      themeBases(html),
      "a theme specimen must keep its baked pixels, not gain a themeProvider render URL",
    )
  }

  @Test
  fun `an ordinary card in the same catalog still re-renders`() {
    // The fix must not disable the whole Theme control — only the specimens opt out.
    val html =
      page(
        listOf(
          ServePreview(id = "theme-meshcore-light", label = "Theme", section = "Themes"),
          ServePreview(id = "contactrow-chat", label = "Contact row", section = "Components"),
        )
      )
    val bases = themeBases(html)
    assertTrue(bases.any { it.isEmpty() }, "the specimen opts out")
    assertTrue(
      bases.any { it.contains("/render/contactrow-chat.png") },
      "the component card still has a themed-render base",
    )
    assertTrue(html.contains("cp-theme-btn"), "the declared-theme chips remain offered")
  }

  @Test
  fun `the section match is case-insensitive`() {
    val html = page(listOf(ServePreview(id = "t", label = "T", section = "themes")))
    assertEquals(listOf(""), themeBases(html))
  }

  @Test
  fun `a sectionless catalog is unaffected`() {
    // Plain bundles and uploaded catalogs carry no section at all; they must keep re-rendering.
    val html = page(listOf(ServePreview(id = "a", label = "A")))
    assertTrue(themeBases(html).any { it.contains("/render/a.png") })
  }
}
