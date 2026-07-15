package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeBundleHostTest {

  private fun bundle(vararg previews: Pair<String, ByteArray>): File {
    val dir = java.nio.file.Files.createTempDirectory("bundle").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    val previewsDir = File(dir, "previews").apply { mkdirs() }
    previews.forEach { (id, png) ->
      File(previewsDir, "$id.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }
    return dir
  }

  @Test
  fun `nested preview ids (with slashes) are discovered and rendered`() {
    val host = ServeBundleHost(bundle("group/com.example.Red" to byteArrayOf(4, 2)), label = "b")
    assertEquals(listOf("group/com.example.Red"), host.previews.map { it.id })
    val ok = host.render("group/com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(4, 2).contentEquals(ok.png))
  }

  @Test
  fun `declared themes are read from the bundle's previews_json when present`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1))
    // A previews.json carrying two synthetic THEME_CATALOG entries (the shape discovery emits) plus
    // an ordinary preview that must NOT be mistaken for a theme.
    File(dir, "previews.json")
      .writeText(
        """
        {
          "module": ":samples:cmp",
          "variant": "debug",
          "previews": [
            { "id": "com.example.Card", "functionName": "Card", "className": "com.example.CardKt" },
            { "id": "themecatalog__Brand_Light", "functionName": "Brand Light theme",
              "className": "com.example.BrandLightTheme",
              "params": { "name": "Brand Light", "group": "Brand", "kind": "THEME_CATALOG",
                          "wrapperClassName": "com.example.BrandLightTheme" } },
            { "id": "themecatalog__Brand_Dark", "functionName": "Brand Dark theme",
              "className": "com.example.BrandDarkTheme",
              "params": { "name": "Brand Dark", "group": "Brand", "kind": "THEME_CATALOG",
                          "wrapperClassName": "com.example.BrandDarkTheme" } }
          ]
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "compose-m3")
    assertEquals(
      listOf(
        "Brand Light" to "com.example.BrandLightTheme",
        "Brand Dark" to "com.example.BrandDarkTheme",
      ),
      host.declaredThemes.map { it.name to it.providerFqn },
    )
    assertEquals(listOf("Brand", "Brand"), host.declaredThemes.map { it.group })
  }

  @Test
  fun `no declared themes without a previews_json (a bare WebEmbed)`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(1)), label = "b")
    assertTrue(host.declaredThemes.isEmpty())
  }

  @Test
  fun `previews are discovered from the bundle's png files, sorted`() {
    val host =
      ServeBundleHost(
        bundle("com.example.Red" to byteArrayOf(1), "com.example.Blue" to byteArrayOf(2)),
        label = "demo@abc",
      )
    assertEquals(listOf("com.example.Blue", "com.example.Red"), host.previews.map { it.id })
    assertEquals("demo@abc", host.label)
  }

  @Test
  fun `previews are tagged with state and theme from the variants manifest`() {
    val dir =
      bundle(
        "checkbox__ideal__default__light" to byteArrayOf(1),
        "checkbox__ideal__unchecked__light" to byteArrayOf(2),
      )
    File(dir, "previews/${ServeCatalogStore.VARIANTS_FILE}")
      .writeText(
        """
        {
          "checkbox__ideal__default__light": { "state": "default", "theme": "light" },
          "checkbox__ideal__unchecked__light": { "state": "unchecked", "theme": "light" }
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "compose-m3")
    val byId = host.previews.associateBy { it.id }
    assertEquals(
      "default" to "light",
      byId.getValue("checkbox__ideal__default__light").let { it.state to it.theme },
    )
    assertEquals(
      "unchecked" to "light",
      byId.getValue("checkbox__ideal__unchecked__light").let { it.state to it.theme },
    )
  }

  @Test
  fun `a plain bundle without a variants manifest keeps null state and theme`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(1)), label = "b")
    val p = host.previews.single()
    assertNull(p.state)
    assertNull(p.theme)
  }

  @Test
  fun `render returns the baked png and NotFound for unknown ids`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(9, 8, 7)), label = "b")

    val ok = host.render("com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(9, 8, 7).contentEquals(ok.png))
    assertEquals(RenderOutcome.NotFound, host.render("com.example.Missing", PreviewOverrides()))
  }

  @Test
  fun `renderSvg serves the baked figma svg with hybrid rasters inlined`() {
    val dir = bundle("button-filled__ideal__default__dark" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg")
      .writeText("<svg><image href=\"button-filled.figma-raster/n0.png\"/></svg>")
    File(figma, "button-filled.figma-raster").mkdirs()
    File(figma, "button-filled.figma-raster/n0.png").writeBytes(byteArrayOf(1, 2, 3))

    val host = ServeBundleHost(dir, label = "compose-m3", figmaDir = figma)
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val svg = ok.svg.decodeToString()
    val expected = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    assertTrue(svg.contains("data:image/png;base64,$expected"), "raster inlined: $svg")
    assertFalse(svg.contains("figma-raster/"), "no dangling external ref: $svg")
  }

  @Test
  fun `renderSvg is NotFound without a figma dir, for unknown ids, and for missing svgs`() {
    val dir =
      bundle("button-filled__ideal__default__dark" to byteArrayOf(1), "badge__x" to byteArrayOf(2))
    val overrides = PreviewOverrides()

    // A plain bundle (no figmaDir) 404s the .svg lane.
    val plain = ServeBundleHost(dir, label = "b")
    assertEquals(
      SvgOutcome.NotFound,
      plain.renderSvg("button-filled__ideal__default__dark", overrides),
    )

    // With a figma dir: a known id whose slug carried no svg, and an unknown id, both 404.
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg").writeText("<svg/>")
    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    assertEquals(SvgOutcome.NotFound, host.renderSvg("badge__x", overrides)) // slug badge: no svg
    assertEquals(SvgOutcome.NotFound, host.renderSvg("nope__x", overrides)) // unknown id
  }

  @Test
  fun `renderSvg does not inline a raster href that escapes the figma dir`() {
    val dir = bundle("button-filled__ideal__default__dark" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    // A secret file OUTSIDE the figma dir; a traversal href must not read it.
    File(dir, "secret.png").writeBytes(byteArrayOf(9, 9, 9))
    File(figma, "button-filled.svg")
      .writeText("<svg><image href=\"button-filled.figma-raster/../../secret.png\"/></svg>")

    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val svg = ok.svg.decodeToString()
    val leaked = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
    assertFalse(svg.contains(leaked), "must not inline a file outside the figma dir: $svg")
    assertTrue(svg.contains("../../secret.png"), "the escaping href is left as a plain ref: $svg")
  }

  @Test
  fun `a bundle host has no live lane`() {
    val host = ServeBundleHost(bundle("p" to byteArrayOf(1)), label = "b")
    assertNull(host.subscribeStream("p", PreviewOverrides(), null, null) {})
    assertEquals(0, host.activeStreamCount())
    host.close() // no-op, must not throw
  }

  @Test
  fun `looksLikeBundle detects a previews directory with pngs`() {
    assertTrue(ServeBundleHost.looksLikeBundle(bundle("p" to byteArrayOf(1))))
    val empty = java.nio.file.Files.createTempDirectory("empty").toFile().also { it.deleteOnExit() }
    assertFalse(ServeBundleHost.looksLikeBundle(empty))
  }
}
