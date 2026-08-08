package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-preview viewer's **toolbar controls**: the Theme chips and the Background/Transparent
 * pair the catalog grid already carried.
 *
 * Both are deliberately the *same* controls as the grid's — same markup, same choice values, same
 * `bg-toggle.js` — so a visitor moving between the two pages meets one control rather than two that
 * behave alike but drift apart. What is viewer-specific is the wiring: the chips are the visible
 * face of `#cp-theme`, which stays in the DOM as the axis's single state holder (viewer.js, the
 * sticky script and URL hydration all read and write it) but is visually removed so the page never
 * shows two controls for one value.
 */
class ServeViewerThemeBarTest {

  private val declared =
    listOf(
      ServeTheme(name = "High Contrast", providerFqn = "com.example.HighContrastThemeCatalog"),
      ServeTheme(name = "Brand Light", providerFqn = "com.example.BrandLightTheme", group = "Brand"),
    )

  private fun viewer(
    preview: ServePreview,
    themes: List<ServeTheme> = declared,
    basePath: String = "/compose-m3",
  ) =
    ServeWeb.viewerPage(
      preview,
      token = "t",
      siblings = listOf(preview),
      canApplyOverrides = true,
      basePath = basePath,
      declaredThemes = themes,
    )

  @Test
  fun `the viewer bar carries a chip per theme, valued like the select's options`() {
    val html = viewer(ServePreview("plain.Button", "Button"))
    assertTrue(
      html.contains(
        "<span class=\"cp-theme cp-theme-bar\" role=\"group\" aria-label=\"Preview theme\">"
      ),
      html,
    )
    // The built-in uiMode pair keeps the labels the select used, so nothing is renamed under the
    // visitor — only re-shaped.
    assertTrue(
      html.contains(
        """<button type="button" class="cp-theme-btn" data-theme-choice="light">Day</button>"""
      ),
      html,
    )
    assertTrue(
      html.contains(
        """<button type="button" class="cp-theme-btn" data-theme-choice="dark">Night</button>"""
      ),
      html,
    )
    // …and one chip per app-declared @ThemeCatalog theme, carrying the select's own option value —
    // which is what lets a chip click be a plain assignment to the select.
    declared.forEach { theme ->
      assertTrue(
        html.contains("data-theme-choice=\"theme:${theme.providerFqn}\""),
        "no chip for ${theme.name}: $html",
      )
      assertTrue(
        html.contains("<option value=\"theme:${theme.providerFqn}\">"),
        "the chip's value must be an option the select actually offers: $html",
      )
    }
  }

  @Test
  fun `the theme select stays the state holder, visually removed and out of the tab order`() {
    val html = viewer(ServePreview("plain.Button", "Button"))
    assertTrue(html.contains("<span class=\"cp-modes-inputs\" aria-hidden=\"true\">"), html)
    assertTrue(html.contains("<select id=\"cp-theme\""), "the select must remain — it is the state")
    assertTrue(
      html.contains("data-fixed-theme=\"false\" tabindex=\"-1\""),
      "an aria-hidden wrapper is only legitimate around a control nothing can tab to: $html",
    )
    assertFalse(
      html.contains("<label>Theme"),
      "two visible controls for one value is what this replaces",
    )
  }

  @Test
  fun `a dark-first system offers Night alone, exactly as its select did`() {
    val html =
      viewer(ServePreview("wear.Chip", "Chip"), basePath = "/wear-m3", themes = emptyList())
    assertTrue(html.contains("""data-theme-choice="dark">Night</button>"""), html)
    assertFalse(
      html.contains("""data-theme-choice="light""""),
      "Wear has no day/night axis, so the bar must not sprout one",
    )
  }

  @Test
  fun `a theme specimen's bar withholds the declared themes its select withholds`() {
    val html = viewer(ServePreview("theme-brand", "Theme", section = "Themes"))
    assertTrue(html.contains("data-fixed-theme=\"true\""), html)
    assertFalse(
      html.contains("data-theme-choice=\"theme:"),
      "a specimen documents ONE theme; re-rendering it under another contradicts its caption",
    )
  }

  @Test
  fun `the viewer offers the same Background pair as the grid`() {
    val html = viewer(ServePreview("plain.Button", "Button"))
    assertTrue(
      html.contains(
        """<button type="button" class="cp-bg-btn" data-bg-choice="on">Background</button>"""
      ),
      html,
    )
    assertTrue(
      html.contains(
        """<button type="button" class="cp-bg-btn" data-bg-choice="off">Transparent</button>"""
      ),
      html,
    )
  }

  @Test
  fun `viewer js drives the select from the chips rather than rendering themes itself`() {
    val script = ServeWebAssets.load("viewer.js")!!.bytes.decodeToString()
    assertTrue(
      script.contains(
        """var themeBarBtns = document.querySelectorAll(".cp-theme-bar .cp-theme-btn");"""
      ),
      script,
    )
    assertTrue(
      script.contains("""themeChoice.dispatchEvent(new Event("change", { bubbles: true }));"""),
      "a chip click must go through the select's own change, so every existing lane still applies",
    )
    // The bar mirrors what syncServerControls has just decided; it must not re-derive it, or the
    // two would disagree about which themes this lane can render.
    assertTrue(
      script.contains("b.disabled = themeChoice.disabled || !option || option.disabled;"),
      script,
    )
    assertTrue(script.contains("syncThemeBar();"), script)
  }
}
