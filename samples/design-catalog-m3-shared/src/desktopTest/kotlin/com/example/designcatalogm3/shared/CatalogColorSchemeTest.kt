package com.example.designcatalogm3.shared

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the `theme.colors` **serialized app palette** — the seam that lets any consumer
 * (e.g. an app rendering the M3 catalog under its own brand theme) re-skin every sticker through
 * the existing string knob, with no per-preview change and no brand hardcoded in this module.
 */
class CatalogColorSchemeTest {

  @Test
  fun `serialize then parse round-trips the full role set for both modes`() {
    val light =
      lightColorScheme(
        primary = Color(0xFF00695C),
        secondary = Color(0xFF80CBC4),
        // A "fixed" accent role — proves the M3 fixed roles round-trip too, not just the base set.
        primaryFixed = Color(0xFF112233),
      )
    val dark = darkColorScheme(primary = Color(0xFF4DD0E1), tertiary = Color(0xFFFFE082))
    val blob = serializeCatalogColorScheme(light, dark)

    // Decodes through the same public resolver both render tiers call.
    val decodedLight = catalogColorScheme(blob, dark = false)
    val decodedDark = catalogColorScheme(blob, dark = true)

    assertEquals(light.primary, decodedLight.primary)
    assertEquals(light.secondary, decodedLight.secondary)
    assertEquals(light.surface, decodedLight.surface)
    assertEquals(light.primaryFixed, decodedLight.primaryFixed)
    assertEquals(dark.primary, decodedDark.primary)
    assertEquals(dark.tertiary, decodedDark.tertiary)
    // Re-serializing the decoded schemes reproduces the blob → every carried role round-trips.
    assertEquals(blob, serializeCatalogColorScheme(decodedLight, decodedDark))
  }

  @Test
  fun `a partial palette overrides only the supplied roles, the rest stay stock M3`() {
    val stock = lightColorScheme()
    val decoded = catalogColorScheme("scheme:l=primary:FF00695C,onPrimary:FFFFFFFF", dark = false)
    assertEquals(Color(0xFF00695C), decoded.primary)
    assertEquals(Color(0xFFFFFFFF), decoded.onPrimary)
    // A role the blob didn't carry keeps its stock M3 tone.
    assertEquals(stock.surface, decoded.surface)
    assertEquals(stock.outline, decoded.outline)
  }

  @Test
  fun `6-digit hex is treated as opaque`() {
    val decoded = parseCatalogColorScheme("scheme:l=primary:00695C", dark = false)
    assertEquals(Color(0xFF00695C), decoded?.primary)
  }

  @Test
  fun `a named or unparseable value falls back to a stock or named scheme`() {
    // A plain name isn't a `scheme:` blob → the named path → stock M3.
    assertEquals(lightColorScheme().primary, catalogColorScheme("M3", dark = false).primary)
    // `scheme:` prefix but no usable role → null, and the resolver falls back to stock M3.
    assertNull(parseCatalogColorScheme("scheme:garbage", dark = false))
    assertEquals(
      lightColorScheme().primary,
      catalogColorScheme("scheme:garbage", dark = false).primary,
    )
    // Malformed hex for the only role → skipped → nothing to apply → null.
    assertNull(parseCatalogColorScheme("scheme:l=primary:zzzz", dark = false))
    // A blob carrying no segment for the requested mode → null (caller uses stock for that mode).
    assertNull(parseCatalogColorScheme("scheme:l=primary:FF00695C", dark = true))
  }

  @Test
  fun `named palettes declare their supported light and dark modes`() {
    val both = setOf(CatalogThemeMode.LIGHT, CatalogThemeMode.DARK)
    // Stock M3 (and any unknown name) supports both modes.
    assertEquals(both, catalogThemeModes(CATALOG_PALETTE_M3))
    assertEquals(both, catalogThemeModes("nope"))
    // The brand palettes are single-mode: Coral is a light scheme, Teal a dark one.
    assertEquals(setOf(CatalogThemeMode.LIGHT), catalogThemeModes(CATALOG_PALETTE_CORAL))
    assertEquals(setOf(CatalogThemeMode.DARK), catalogThemeModes(CATALOG_PALETTE_TEAL))
  }

  @Test
  fun `a serialized palette's modes are inferred from the segments it carries`() {
    val light = lightColorScheme(primary = Color(0xFFFF6F61))
    val dark = darkColorScheme(primary = Color(0xFF4DD0E1))
    // A full serialize carries both l= and d= → both modes.
    assertEquals(
      setOf(CatalogThemeMode.LIGHT, CatalogThemeMode.DARK),
      catalogThemeModes(serializeCatalogColorScheme(light, dark)),
    )
    // Only an l= segment → light-only; only a d= segment → dark-only.
    assertEquals(setOf(CatalogThemeMode.LIGHT), catalogThemeModes("scheme:l=primary:FFFF6F61"))
    assertEquals(setOf(CatalogThemeMode.DARK), catalogThemeModes("scheme:d=primary:FF4DD0E1"))
  }

  @Test
  fun `a malformed serialized palette falls back to both modes`() {
    // No usable role in either mode → neither parses → both (mirrors catalogColorScheme's
    // fallback).
    assertEquals(
      setOf(CatalogThemeMode.LIGHT, CatalogThemeMode.DARK),
      catalogThemeModes("scheme:garbage"),
    )
  }

  @Test
  fun `a segment of only unknown roles is not a usable mode`() {
    // A valid `l=` segment whose only key is a typo'd / unknown role (`primry`) carries no
    // recognized role, so it isn't a usable mode: the parser skips the unknown key and returns
    // null,
    // and catalogThemeModes falls back to both rather than reporting a spurious light-only.
    assertNull(parseCatalogColorScheme("scheme:l=primry:FF000000", dark = false))
    assertEquals(
      setOf(CatalogThemeMode.LIGHT, CatalogThemeMode.DARK),
      catalogThemeModes("scheme:l=primry:FF000000"),
    )
    // A recognized role alongside the unknown one still applies (the unknown is just ignored).
    assertNull(parseCatalogColorScheme("scheme:d=primry:FF000000", dark = false))
    assertEquals(
      Color(0xFF00695C),
      parseCatalogColorScheme("scheme:l=primry:FF000000,primary:FF00695C", dark = false)?.primary,
    )
  }
}
