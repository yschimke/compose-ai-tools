package com.example.designcatalogm3.shared

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the `theme.fonts` serialized override — the seam that lets an app brand the M3
 * catalog's type scale per role group (e.g. `display=Orbitron, body=Space Grotesk`) through the
 * same string-knob surface as colors/shapes/metrics, with no per-preview change and no face
 * hardcoded in the catalog. Pure-logic codec (the `namedFontFamily` resolution is exercised by the
 * render, not here), like [CatalogColorSchemeTest].
 */
class CatalogFontFamiliesTest {

  @Test
  fun `families round-trip through the theme_fonts wire form`() {
    val families =
      mapOf("display" to "Orbitron", "body" to "Space Grotesk", "label" to "Space Grotesk")
    val blob = serializeCatalogFontFamilies(families)
    assertTrue(blob.startsWith(CATALOG_FONTS_PREFIX), "carries the families: prefix")
    assertEquals(families, parseCatalogFontFamilies(blob))
  }

  @Test
  fun `serialize emits only known groups in canonical order`() {
    // Insertion order (body before display) must not leak — canonical order is display→…→label.
    val blob =
      serializeCatalogFontFamilies(
        linkedMapOf("body" to "Space Grotesk", "display" to "Orbitron", "bogus" to "Nope")
      )
    assertEquals("families:display=Orbitron,body=Space Grotesk", blob)
  }

  @Test
  fun `serialize drops blank families and empties to no-override`() {
    assertEquals(
      "families:display=Orbitron",
      serializeCatalogFontFamilies(mapOf("display" to "Orbitron", "body" to "  ")),
    )
    assertEquals("", serializeCatalogFontFamilies(emptyMap()))
    assertEquals("", serializeCatalogFontFamilies(mapOf("display" to "")))
  }

  @Test
  fun `parse ignores unknown groups and blank families`() {
    val parsed =
      parseCatalogFontFamilies(
        "families:display=Orbitron,mono=JetBrains Mono,title=,body=Space Grotesk"
      )
    // `mono` is not an M3 role group and `title=` is blank — both dropped; the valid two survive.
    assertEquals(mapOf("display" to "Orbitron", "body" to "Space Grotesk"), parsed)
  }

  @Test
  fun `parse without the prefix is a no-op override`() {
    assertEquals(emptyMap(), parseCatalogFontFamilies(""))
    assertEquals(emptyMap(), parseCatalogFontFamilies("Orbitron"))
    // A bare name (the theme.font knob's shape) must not be mistaken for a families blob.
    assertEquals(emptyMap(), parseCatalogFontFamilies("Space Grotesk"))
  }

  @Test
  fun `a family name with spaces survives the codec`() {
    // "Space Grotesk" / "JetBrains Mono" carry spaces; the split is on ','/'=' only, so they ride
    // intact — the render then resolves them via namedFontFamily against the vendored faces.
    val roundTrip =
      parseCatalogFontFamilies(serializeCatalogFontFamilies(mapOf("title" to "JetBrains Mono")))
    assertEquals(mapOf("title" to "JetBrains Mono"), roundTrip)
  }

  // Generic families stand in for the vendored Orbitron/Space Grotesk faces — the apply logic only
  // cares that the right FontFamily lands on the right role group's three sizes.
  @Test
  fun `apply swaps each role group's face against the vendored map`() {
    val named = mapOf("Orbitron" to FontFamily.Serif, "Space Grotesk" to FontFamily.Monospace)
    val out =
      catalogApplyFontFamilies(
        Typography(),
        mapOf("display" to "Orbitron", "body" to "Space Grotesk"),
        named,
        fallback = FontFamily.SansSerif,
      )
    // display group → Orbitron (all three sizes)
    assertEquals(FontFamily.Serif, out.displayLarge.fontFamily)
    assertEquals(FontFamily.Serif, out.displayMedium.fontFamily)
    assertEquals(FontFamily.Serif, out.displaySmall.fontFamily)
    // body group → Space Grotesk
    assertEquals(FontFamily.Monospace, out.bodyLarge.fontFamily)
    assertEquals(FontFamily.Monospace, out.bodySmall.fontFamily)
    // an omitted group keeps the base face (unchanged)
    assertEquals(Typography().headlineLarge.fontFamily, out.headlineLarge.fontFamily)
    assertEquals(Typography().labelLarge.fontFamily, out.labelLarge.fontFamily)
  }

  @Test
  fun `apply degrades an unvendored family to the fallback and empty to base`() {
    val out =
      catalogApplyFontFamilies(
        Typography(),
        mapOf("label" to "Nonexistent"),
        named = emptyMap(),
        fallback = FontFamily.Cursive,
      )
    assertEquals(FontFamily.Cursive, out.labelLarge.fontFamily)
    // no families ⇒ the base Typography is returned untouched
    val base = Typography()
    assertEquals(base, catalogApplyFontFamilies(base, emptyMap(), mapOf("X" to FontFamily.Serif)))
  }
}
