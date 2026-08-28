package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-preview viewer's **toolbar controls**: the Theme chips and the Background/Transparent
 * pair the catalog grid already carried.
 *
 * Both are deliberately the *same* controls as the grid's — same markup, same choice values, the
 * same `<cp-bg-toggle>` element — so a visitor moving between the two pages meets one control
 * rather than two that behave alike but drift apart. What is viewer-specific is the wiring: the
 * chips are the visible face of `#cp-theme`, which stays in the DOM as the axis's single state
 * holder (viewer.js, the sticky script and URL hydration all read and write it) but is visually
 * removed so the page never shows two controls for one value.
 */
class ServeViewerThemeBarTest {

  private val declared =
    listOf(
      ServeTheme(name = "High Contrast", providerFqn = "com.example.HighContrastThemeCatalog"),
      ServeTheme(
        name = "Brand Light",
        providerFqn = "com.example.BrandLightTheme",
        group = "Brand",
      ),
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
        "<span class=\"cp-theme cp-theme-bar\" id=\"cp-theme-bar\" role=\"group\"" +
          " aria-label=\"Preview theme\">"
      ),
      html,
    )
    // The built-in uiMode pair uses the same plain Light/Dark wording as the rest of the UI.
    assertTrue(
      html.contains(
        """<button type="button" class="cp-theme-btn" data-theme-choice="light">Light</button>"""
      ),
      html,
    )
    assertTrue(
      html.contains(
        """<button type="button" class="cp-theme-btn" data-theme-choice="dark">Dark</button>"""
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
        html.contains("<option value=\"theme:${theme.providerFqn}\""),
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
  fun `a dark-first system offers Dark alone`() {
    val html =
      viewer(ServePreview("wear.Chip", "Chip"), basePath = "/wear-m3", themes = emptyList())
    assertTrue(html.contains("""data-theme-choice="dark">Dark</button>"""), html)
    assertFalse(
      html.contains("""data-theme-choice="light""""),
      "Wear has no light/dark axis, so the bar must sprout no light choice",
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
  fun `the viewer offers the same Transparent toggle as the grid, and one Fit width toggle`() {
    val html = viewer(ServePreview("plain.Button", "Button"))
    // A two-state axis with a default is ONE aria-pressed button, not a pair whose other half is
    // always a no-op. Both toolbars emit the identical `<cp-bg-toggle>`; the button itself (and its
    // resting `aria-pressed="false"`) is rendered by the element — see
    // `cli/serve-web/test/bgToggle.test.ts`.
    val transparent = "<cp-bg-toggle label="
    assertTrue(html.contains(transparent), html)
    assertTrue(
      ServeWeb.landingPage("compose-m3", listOf(ServePreview("a", "A")), token = "t")
        .contains(transparent),
      "the grid's toggle must be the same element, or the two bars are wiring two shapes",
    )
    assertTrue(html.contains("""class="cp-bg-btn cp-zoom-toggle" aria-pressed="false""""), html)
    assertFalse(html.contains("data-zoom-mode="), "the Fit screen / Fit width pair is one toggle")
    assertFalse(html.contains("data-bg-choice="), "…and so is Background / Transparent")
  }

  @Test
  fun `a pinned toolbar keeps current-only controls visible and explains why they are disabled`() {
    val preview = ServePreview("plain.Button", "Button", sourceFile = "Button.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        siblings = listOf(preview),
        canApplyOverrides = true,
        hasSvgExport = true,
        usageHref = "/usage/plain.Button",
        basePath = "/compose-m3",
        revisions = ServeWeb.CatalogRevisions(pinned = "1111111"),
      )

    assertTrue(html.contains("id=\"cp-source-chip\"") && html.contains(">Source</button>"), html)
    assertTrue(html.contains("Pinned revision — source is only available"), html)
    assertTrue(html.contains("id=\"cp-svg-toggle\"") && html.contains(">SVG</button>"), html)
    assertTrue(html.contains("Pinned revision — SVG is generated"), html)
    assertTrue(html.contains("id=\"cp-explode-toggle\"") && html.contains(">3D</button>"), html)
    assertTrue(html.contains("Pinned revision — 3D is generated"), html)
    assertTrue(html.contains("id=\"cp-pinned-controls-note\" role=\"note\""), html)
    assertTrue(html.contains("tabindex=\"0\" aria-describedby=\"cp-pinned-controls-note\""), html)
    assertFalse(html.contains("data-usage-src=\"/usage/plain.Button\""), html)
    assertFalse(html.contains("id=\"cp-source-panel\""), html)
  }

  @Test
  fun `the select names the theme its preview is baked in`() {
    // `data-default-theme` is what makes "has the visitor pinned a theme?" answerable. Without it
    // the only record of the baked theme is the `selected` option, which stops answering the
    // moment the sticky script writes `el.value` — so a URL that merely spells the default out
    // (`?uiMode=light` on a light variant) read as a pinned override and suppressed the Figma
    // comparison for a visitor who had chosen nothing (#4218).
    assertTrue(
      viewer(ServePreview("button-filled__ideal__default__light", "Button"))
        .contains("data-default-theme=\"light\""),
      "a __light variant is baked light",
    )
    assertTrue(
      viewer(ServePreview("button-filled__ideal__default__dark", "Button"))
        .contains("data-default-theme=\"dark\""),
      "…and the forgiveness is per-preview: on a __dark variant it is `dark` that asks for nothing",
    )
    assertTrue(
      viewer(ServePreview("wear.Chip", "Chip"), basePath = "/wear-m3", themes = emptyList())
        .contains("data-default-theme=\"dark\""),
      "a dark-first system's default needs no id token to be known",
    )
  }

  @Test
  fun `a preview whose theme the catalog cannot name claims no default`() {
    // Empty is "the server could not say", NOT "light". The select still displays Light (its
    // `selected` option), but the baked pixels are not known to be a light render, so
    // `uiMode=light`
    // there stays a real override that has to travel — claiming it as the default would answer a
    // request with pixels that may not honour it.
    assertTrue(
      viewer(ServePreview("plain.Button", "Button")).contains("data-default-theme=\"\""),
      "no id token, no metadata, not dark-first: nothing to claim",
    )
  }

  @Test
  fun `the sticky bootstrap displays a default-valued choice without marking it a pick`() {
    val html = viewer(ServePreview("button-filled__ideal__default__light", "Button"))
    assertTrue(
      html.contains("var bakedTheme = el.getAttribute(\"data-default-theme\") || \"\";") &&
        html.contains("function pinsTheme(choice) { return !!choice && choice !== bakedTheme; }"),
      "the bootstrap must share the viewer's rule rather than re-deciding it: $html",
    )
    // Both seeding paths — the URL and the remembered choice — display the value and mark it
    // active only when it deviates. `data-theme-active` is what the spec baseline is published
    // from three lines later, so an un-guarded write here is the whole bug.
    assertTrue(
      html.contains("if (pinsTheme(urlChoice)) el.setAttribute(\"data-theme-active\", \"1\");"),
      "a URL naming the default must not read as a pin: $html",
    )
    assertTrue(
      html.contains("if (pinsTheme(stored)) el.setAttribute(\"data-theme-active\", \"1\");"),
      "…nor must a remembered choice that agrees with it: $html",
    )
  }

  @Test
  fun `the viewer asks the shared rule whether a theme choice is a pin`() {
    val script = viewerSource()
    assertTrue(
      script.contains("defaultValue: defaultThemeValue(),"),
      "activeThemeChoice needs the baked theme to forgive a default-valued pick: $script",
    )
    assertTrue(
      script.contains("rules.pinsTheme(choice, defaultThemeValue())"),
      "Back/Forward hydration restores \"nobody picked\", not a pick agreeing with the default",
    )
  }

  @Test
  fun `viewer js drives the select from the chips rather than rendering themes itself`() {
    val script = viewerSource()
    assertTrue(
      script.contains("const themeBarBtns = document.querySelectorAll<HTMLButtonElement>(") &&
        script.contains(""".cp-theme-bar .cp-theme-btn"""),
      script,
    )
    assertTrue(
      script.contains("""themeChoice.dispatchEvent(new Event("change", { bubbles: true }));"""),
      "a chip click must go through the select's own change, so every existing lane still applies",
    )
    // The bar mirrors what syncServerControls has just decided; it must not re-derive it, or the
    // two would disagree about which themes this lane can render. That rule moved to
    // `cli/serve-web/src/viewer/themeChoice.ts`, where `viewerThemeChoice.test.ts` drives each way
    // a
    // chip can be disabled — the select itself, a missing option, a server-disabled option. What is
    // held here is that the served asset still asks for it rather than re-deriving it locally.
    assertTrue(
      script.contains("rules.themeBarButton(") && script.contains("b.disabled = state.disabled;"),
      script,
    )
    assertTrue(script.contains("syncThemeBar();"), script)
  }
}
