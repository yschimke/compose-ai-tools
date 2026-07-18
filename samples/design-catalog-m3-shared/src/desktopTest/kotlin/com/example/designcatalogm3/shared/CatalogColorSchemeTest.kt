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
}
