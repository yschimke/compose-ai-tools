package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog-theme → web-chrome projection ([ServeThemeCss]).
 *
 * The token payloads below are verbatim excerpts of the `tokens.dtcg.json` files the published
 * `design-artifacts/<system>` branches actually carry, so the assertions are about real palettes:
 * `wear-m3` (dark-first, cyan), `jetnews` (light, crimson) and `jetsnack` (light, with the
 * alpha-carrying `onSurface` / `outline` several app catalogs publish).
 */
class ServeThemeCssTest {

  private fun tokens(vararg roles: Pair<String, String>): String =
    roles.joinToString(prefix = """{"color":{""", postfix = "}}", separator = ",") { (role, value)
      ->
      """"$role":{"${'$'}type":"color","${'$'}value":"$value"}"""
    }

  // wear-m3: dark surface, cyan primary, full surfaceContainer* ladder.
  private val wearM3 =
    tokens(
      "primary" to "#4dd0e1ff",
      "primaryContainer" to "#4d3d76ff",
      "onPrimary" to "#210f48ff",
      "onPrimaryContainer" to "#f6edffff",
      "surface" to "#202124ff",
      "onSurface" to "#f6edffff",
      "background" to "#000000ff",
      "surfaceContainerLow" to "#272430ff",
      "surfaceContainer" to "#332e3cff",
      "outline" to "#948f9aff",
    )

  // jetnews: light surface, crimson primary, no surfaceContainer* roles.
  private val jetNews =
    tokens(
      "primary" to "#bf0031ff",
      "onPrimary" to "#ffffffff",
      "primaryContainer" to "#ffdad9ff",
      "surface" to "#fffbffff",
      "onSurface" to "#201a1aff",
      "surfaceVariant" to "#f4ddddff",
      "outline" to "#857373ff",
    )

  // jetsnack: light, and its onSurface / outline carry alpha — they must be composited, not
  // parsed as opaque.
  private val jetSnack =
    tokens(
      "primary" to "#4b30edff",
      "onPrimary" to "#ffffffff",
      "surface" to "#ffffffff",
      "onSurface" to "#000000de",
      "outline" to "#0000001f",
    )

  /**
   * The `--cp-*` declarations of the emitted sheet, resolved for one mode.
   *
   * The projection emits ONE `:root` block of `light-dark(<light>, <dark>)` pairs rather than a
   * light block plus a `prefers-color-scheme` block (that is what lets the page-theme setting pin a
   * mode), so reading a mode out means taking one half of each pair.
   */
  private fun vars(css: String, dark: Boolean): Map<String, String> = half(css, "--cp", dark)

  /** One half of every `light-dark()` pair whose property starts with [prefix]. */
  private fun half(css: String, prefix: String, dark: Boolean): Map<String, String> =
    Regex("($prefix-[a-z0-9-]+):\\s*light-dark\\((#[0-9a-f]{6}), (#[0-9a-f]{6})\\)")
      .findAll(css)
      .associate { it.groupValues[1] to it.groupValues[if (dark) 3 else 2] }

  private fun rgb(hex: String) = ServeThemeCss.parse(hex)!!.first

  private fun contrast(a: String, b: String) = ServeThemeCss.contrast(rgb(a), rgb(b))

  @Test
  fun `a dark-first catalog paints dark mode from its own surfaces`() {
    val dark = vars(assertNotNull(ServeThemeCss.fromDtcg(wearM3)), dark = true)
    // The catalog's surface IS the page; its surfaceContainerLow (one step of elevation) the card.
    assertEquals("#202124", dark["--cp-bg"])
    assertEquals("#272430", dark["--cp-surface"])
    assertEquals("#332e3c", dark["--cp-surface-2"])
    assertEquals("#f6edff", dark["--cp-fg"])
    // Wear M3's cyan clears the contrast floor on its own dark surface, so it lands unmodified.
    assertEquals("#4dd0e1", dark["--cp-accent"])
    assertEquals("#4d3d76", dark["--cp-accent-soft"])
  }

  @Test
  fun `the mode a dark-first catalog did not bake keeps the built-in neutrals`() {
    val light = vars(assertNotNull(ServeThemeCss.fromDtcg(wearM3)), dark = false)
    // Light mode is not wear-m3's mode: its dark surfaces must not leak into a light page, so the
    // M3 baseline scheme's light neutrals (the same four `serve.css` declares) are used verbatim…
    assertEquals("#fef7ff", light["--cp-bg"])
    assertEquals("#1d1b20", light["--cp-fg"])
    // …but the brand colour still carries, re-contrasted against the light page (cyan on white is
    // unreadable at 1.6:1, so it is deepened until it reads).
    assertTrue(contrast(light.getValue("--cp-accent"), light.getValue("--cp-bg")) >= 4.0)
  }

  @Test
  fun `a light catalog paints light mode from its own surfaces`() {
    val light = vars(assertNotNull(ServeThemeCss.fromDtcg(jetNews)), dark = false)
    // No surfaceContainerLow published, so the page tint is derived from surface + onSurface and
    // the catalog's surface stays the card fill.
    assertEquals("#fffbff", light["--cp-surface"])
    assertEquals("#201a1a", light["--cp-fg"])
    assertTrue(light.getValue("--cp-bg") != light.getValue("--cp-surface"))
    assertEquals("#bf0031", light["--cp-accent"])
    assertEquals("#ffdad9", light["--cp-accent-soft"])
  }

  @Test
  fun `alpha-carrying tokens are composited rather than read as opaque`() {
    val light = vars(assertNotNull(ServeThemeCss.fromDtcg(jetSnack)), dark = false)
    // `#000000de` over white is a near-black grey, NOT #000000.
    assertEquals("#212121", light["--cp-fg"])
  }

  @Test
  fun `every themed colour that carries text stays readable in both modes`() {
    for (palette in listOf(wearM3, jetNews, jetSnack)) {
      val css = assertNotNull(ServeThemeCss.fromDtcg(palette))
      for (dark in listOf(false, true)) {
        val v = vars(css, dark)
        val bg = v.getValue("--cp-bg")
        val surface = v.getValue("--cp-surface")
        val where = "${if (dark) "dark" else "light"} mode"
        assertTrue(contrast(v.getValue("--cp-fg"), bg) >= 7.0, "body text on the page, $where")
        assertTrue(contrast(v.getValue("--cp-fg"), surface) >= 7.0, "body text on a card, $where")
        assertTrue(contrast(v.getValue("--cp-fg-muted"), bg) >= 3.5, "muted text, $where")
        assertTrue(contrast(v.getValue("--cp-accent"), bg) >= 4.0, "links, $where")
        assertTrue(contrast(v.getValue("--cp-accent-strong"), bg) >= 4.0, "hover text, $where")
        assertTrue(
          contrast(v.getValue("--cp-on-accent"), v.getValue("--cp-accent")) >= 4.0,
          "the site mark's glyph on its accent tile, $where",
        )
        assertTrue(
          contrast(v.getValue("--cp-on-accent-soft"), v.getValue("--cp-accent-soft")) >= 4.0,
          "a chip's label on its soft accent fill, $where",
        )
      }
    }
  }

  @Test
  fun `borders sit between the page and its text in both modes`() {
    for (palette in listOf(wearM3, jetNews, jetSnack)) {
      val css = assertNotNull(ServeThemeCss.fromDtcg(palette))
      for (dark in listOf(false, true)) {
        val v = vars(css, dark)
        val bg = v.getValue("--cp-bg")
        // Visible, but never mistakable for text.
        assertTrue(contrast(v.getValue("--cp-border"), bg) < 2.0)
        assertTrue(
          contrast(v.getValue("--cp-border-strong"), bg) >= contrast(v.getValue("--cp-border"), bg)
        )
      }
    }
  }

  @Test
  fun `the projection sets exactly the custom properties the stylesheet declares`() {
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    val declared =
      Regex("(--cp-[a-z0-9-]+):").findAll(sheet.substringBefore("* { box-sizing")).map {
        it.groupValues[1]
      }
    val used = Regex("var\\((--cp-[a-z0-9-]+)\\)").findAll(sheet).map { it.groupValues[1] }.toSet()
    val emitted = vars(assertNotNull(ServeThemeCss.fromDtcg(wearM3)), dark = false).keys
    assertEquals(declared.toSet(), emitted, "the sheet's :root palette and the projection's")
    assertEquals(emptySet(), used - emitted, "custom properties used but never themed")
  }

  /** The `--md-sys-color-*` (Material 3 role) declarations, resolved for one mode. */
  private fun roles(css: String, dark: Boolean): Map<String, String> =
    half(css, "--md-sys-color", dark)

  @Test
  fun `the M3 roles the stylesheet declares are all themed, and agree with the aliases`() {
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    val declared =
      Regex("(--md-sys-color-[a-z0-9-]+):")
        .findAll(sheet.substringBefore("* { box-sizing"))
        .map { it.groupValues[1] }
        .toSet()
    val css = assertNotNull(ServeThemeCss.fromDtcg(wearM3))
    for (dark in listOf(false, true)) {
      val r = roles(css, dark)
      val v = vars(css, dark)
      val where = if (dark) "dark" else "light"
      // A role the sheet paints from but the projection never emits would silently keep the
      // baseline scheme's colour while the rest of the page re-themed.
      assertEquals(emptySet(), declared - r.keys, "roles declared but never themed, $where")
      // The two families are one palette seen twice; where they name the same colour they must
      // agree exactly, or a tonal chip and its `--cp-*` neighbour drift apart on the same page.
      assertEquals(v["--cp-accent"], r["--md-sys-color-primary"], where)
      assertEquals(v["--cp-bg"], r["--md-sys-color-surface"], where)
      assertEquals(v["--cp-fg"], r["--md-sys-color-on-surface"], where)
      assertEquals(v["--cp-surface"], r["--md-sys-color-surface-container-low"], where)
      assertEquals(v["--cp-surface-2"], r["--md-sys-color-surface-container-high"], where)
      assertEquals(v["--cp-border"], r["--md-sys-color-outline-variant"], where)
      assertEquals(v["--cp-accent-soft"], r["--md-sys-color-secondary-container"], where)
    }
  }

  @Test
  fun `M3 container roles stay on the right side of the page in both modes`() {
    for (palette in listOf(wearM3, jetNews, jetSnack)) {
      val css = assertNotNull(ServeThemeCss.fromDtcg(palette))
      for (dark in listOf(false, true)) {
        val r = roles(css, dark)
        val where = "${if (dark) "dark" else "light"} mode"
        for (role in listOf("error", "tertiary")) {
          val container = r.getValue("--md-sys-color-$role-container")
          val on = r.getValue("--md-sys-color-on-$role-container")
          assertTrue(contrast(on, container) >= 4.0, "$role container label, $where")
        }
        // The surface ladder must climb monotonically away from the page, or "one step of
        // elevation" stops meaning anything.
        val low = contrast(r.getValue("--md-sys-color-surface-container-low"), "#ffffff")
        val high = contrast(r.getValue("--md-sys-color-surface-container-high"), "#ffffff")
        assertTrue(if (dark) high <= low else high >= low, "the surface ladder, $where")
      }
    }
  }

  // wear-m3 as its CatalogTheme actually declares it: a cyan primary AND a distinct rose secondary
  // family. The published design systems mostly publish no secondary family at all, which is the
  // other half of this behaviour.
  private val wearM3WithSecondary =
    tokens(
      "primary" to "#4dd0e1ff",
      "primaryContainer" to "#4d3d76ff",
      "onPrimary" to "#210f48ff",
      "onPrimaryContainer" to "#f6edffff",
      "secondary" to "#ff8da1ff",
      "secondaryContainer" to "#652936ff",
      "onSecondaryContainer" to "#ffd9e0ff",
      "surface" to "#202124ff",
      "onSurface" to "#f6edffff",
    )

  @Test
  fun `a published secondary family paints the selected states it was authored for`() {
    // M3 gives the two containers different jobs: `primaryContainer` backs the brand mark, while
    // `secondaryContainer` is the selected state of every chip, segment, drawer toggle and nav row.
    // Collapsing both onto the primary container would throw away half of a published scheme.
    val dark = roles(assertNotNull(ServeThemeCss.fromDtcg(wearM3WithSecondary)), dark = true)
    val aliases = vars(assertNotNull(ServeThemeCss.fromDtcg(wearM3WithSecondary)), dark = true)
    assertEquals("#4d3d76", dark["--md-sys-color-primary-container"], "the mark keeps the primary")
    assertEquals("#652936", dark["--md-sys-color-secondary-container"], "chips take the secondary")
    assertEquals("#ffd9e0", dark["--md-sys-color-on-secondary-container"])
    // …and the alias a chip may equally be styled from moves with it, or one page would paint the
    // same selected state two different colours.
    assertEquals(dark["--md-sys-color-secondary-container"], aliases["--cp-accent-soft"])
    assertEquals(dark["--md-sys-color-on-secondary-container"], aliases["--cp-on-accent-soft"])
  }

  @Test
  fun `a catalog with no secondary family keeps the chip fill it has today`() {
    // Most published catalogs name no `secondaryContainer`. They must fall back to the PRIMARY
    // container rather than to a bare derived tint, so this projection doesn't silently restyle
    // every design system that predates it.
    for (dark in listOf(false, true)) {
      val r = roles(assertNotNull(ServeThemeCss.fromDtcg(wearM3)), dark)
      assertEquals(
        r["--md-sys-color-primary-container"],
        r["--md-sys-color-secondary-container"],
        "${if (dark) "dark" else "light"} mode",
      )
    }
  }

  @Test
  fun `every on-container label is contrast-checked against the fill it actually lands on`() {
    // A label validated against the page — or against a container that was then rejected for
    // sitting on the wrong side of it — can still come out invisible on the fill it is painted on.
    val palettes = listOf(wearM3, wearM3WithSecondary, jetNews, jetSnack)
    for (palette in palettes) {
      val css = assertNotNull(ServeThemeCss.fromDtcg(palette))
      for (dark in listOf(false, true)) {
        val r = roles(css, dark)
        val where = "${if (dark) "dark" else "light"} mode"
        for (family in listOf("primary", "secondary", "tertiary", "error")) {
          val fill = r.getValue("--md-sys-color-$family-container")
          val label = r.getValue("--md-sys-color-on-$family-container")
          assertTrue(contrast(label, fill) >= 4.0, "$family container label, $where")
        }
      }
    }
  }

  @Test
  fun `both modes are emitted as one block of light-dark pairs`() {
    // The shape is load-bearing, not incidental: `serve.css` pins a mode with `color-scheme` alone
    // (the Page theme setting), which can only re-resolve values written as `light-dark()` pairs. A
    // `prefers-color-scheme` block would ignore the pin and leave a catalog's palette on the OS
    // preference while the built-in chrome around it followed the selected theme.
    val css = assertNotNull(ServeThemeCss.fromDtcg(wearM3))
    assertEquals(1, Regex(":root \\{").findAll(css).count(), "one :root block")
    assertTrue("@media" !in css, "no media query")
    // Every declaration carries both halves — a bare colour would be stuck in one mode.
    for (line in css.lines().filter { it.trim().startsWith("--") }) {
      assertTrue(line.contains("light-dark("), line)
    }
  }

  @Test
  fun `a catalog with no usable tokens serves the built-in chrome`() {
    assertNull(ServeThemeCss.fromDtcg("not json at all"))
    assertNull(ServeThemeCss.fromDtcg("""{"type":{"Body":{}}}"""), "no colour group")
    assertNull(ServeThemeCss.fromDtcg(tokens("primary" to "#4b30edff")), "no surface")
    assertNull(ServeThemeCss.fromDtcg(tokens("surface" to "#ffffffff")), "no primary")
    assertNull(
      ServeThemeCss.fromDtcg(tokens("surface" to "rgb(1,2,3)", "primary" to "#fff")),
      "a surface that isn't a hex colour",
    )
  }

  @Test
  fun `an unreadable published foreground never becomes the page's body text`() {
    // A catalog is free to publish a syntactically valid `onSurface` that is unreadable on its own
    // surface. Body text anchors the whole neutral ramp, so taking it literally would make the
    // matching-mode page unreadable end to end.
    val white =
      vars(
        assertNotNull(
          ServeThemeCss.fromDtcg(
            tokens("surface" to "#ffffff", "onSurface" to "#ffffff", "primary" to "#4b30edff")
          )
        ),
        dark = false,
      )
    assertTrue(contrast(white.getValue("--cp-fg"), "#ffffff") >= 4.5, "white on white was accepted")
    // A merely *weak* foreground is nudged rather than discarded — it keeps its hue.
    val weak =
      vars(
        assertNotNull(
          ServeThemeCss.fromDtcg(
            tokens("surface" to "#ffffff", "onSurface" to "#8f8f5a", "primary" to "#4b30edff")
          )
        ),
        dark = false,
      )
    val fg = rgb(weak.getValue("--cp-fg"))
    assertTrue(contrast(weak.getValue("--cp-fg"), "#ffffff") >= 4.5)
    assertTrue(fg.g > fg.b, "the darkened foreground keeps the published hue")
  }

  @Test
  fun `a colour that can never reach the contrast floor stays recognisably itself`() {
    // Near-white primary on a white page: no mix reaches 4:1, so it is darkened as far as the cap
    // allows rather than collapsing onto the text colour.
    val css =
      assertNotNull(ServeThemeCss.fromDtcg(tokens("surface" to "#ffffff", "primary" to "#fffef0")))
    val accent = vars(css, dark = false).getValue("--cp-accent")
    assertTrue(accent != "#000000" && accent != "#111114", "collapsed onto the text colour")
    assertTrue(contrast(accent, "#ffffff") > 1.5, "not nudged at all")
  }
}
