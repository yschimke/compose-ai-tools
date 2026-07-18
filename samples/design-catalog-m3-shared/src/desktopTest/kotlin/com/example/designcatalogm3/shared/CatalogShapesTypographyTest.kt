package com.example.designcatalogm3.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for the `theme.shapes` + `theme.typography` serialized overrides — the seams that
 * let an app re-skin the M3 catalog's corners and type scale through the same string-knob surface
 * as colors, with no per-preview change. Pure-logic (no render), like [CatalogColorSchemeTest].
 */
class CatalogShapesTypographyTest {

  @Test
  fun `shapes round-trip through the theme_shapes wire form`() {
    // Values deliberately unlike the stock M3 set so the assertions bite.
    val blob = serializeCatalogShapes(0.dp, 2.dp, 6.dp, 10.dp, 50.dp)
    val shapes = catalogShapes(blob)
    assertEquals(RoundedCornerShape(0.dp), shapes.extraSmall)
    assertEquals(RoundedCornerShape(2.dp), shapes.small)
    assertEquals(RoundedCornerShape(6.dp), shapes.medium)
    assertEquals(RoundedCornerShape(10.dp), shapes.large)
    assertEquals(RoundedCornerShape(50.dp), shapes.extraLarge)
  }

  @Test
  fun `a partial shape set overrides only the tokens supplied, the rest stay stock M3`() {
    val shapes = catalogShapes("shapes:m=20")
    assertEquals(RoundedCornerShape(20.dp), shapes.medium)
    // Tokens the blob didn't carry keep their stock M3 corner.
    assertEquals(Shapes().small, shapes.small)
    assertEquals(Shapes().extraLarge, shapes.extraLarge)
  }

  @Test
  fun `absent or unparseable shapes fall back to stock M3`() {
    assertEquals(Shapes().medium, catalogShapes("").medium)
    assertEquals(Shapes().medium, catalogShapes("M3").medium)
    assertEquals(Shapes().medium, catalogShapes("shapes:garbage").medium)
  }

  @Test
  fun `typography metrics round-trip through the theme_typography wire form`() {
    // A branded scale: a bigger, bolder display role and a re-sized body role.
    val base = Typography()
    val custom =
      base.copy(
        displayLarge =
          base.displayLarge.copy(
            fontSize = 60.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
          ),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
      )
    val blob = serializeCatalogTypography(custom)

    // Applied onto the stock scale (as a `theme.font` base would supply), it reproduces the
    // metrics.
    val applied = catalogApplyTypography(catalogTypography(null), blob)
    assertEquals(60f, applied.displayLarge.fontSize.value)
    assertEquals(FontWeight.Bold, applied.displayLarge.fontWeight)
    assertEquals(15f, applied.bodyMedium.fontSize.value)
    assertEquals(22f, applied.bodyMedium.lineHeight.value)
    // Re-serializing reproduces the blob → every carried role round-trips.
    assertEquals(blob, serializeCatalogTypography(applied))
  }

  @Test
  fun `an absent or non-typo value leaves the base typography unchanged`() {
    val base = catalogTypography(null)
    assertEquals(base, catalogApplyTypography(base, ""))
    assertEquals(base, catalogApplyTypography(base, "Roboto Flex"))
    // `typo:` prefix but no usable role → base is returned untouched.
    assertEquals(base, catalogApplyTypography(base, "typo:"))
  }

  @Test
  fun `an out-of-range font weight is ignored rather than thrown`() {
    val base = catalogTypography(null)
    // FontWeight requires 1..1000; a query-driven 0 / 2000 must fall back to the base weight, not
    // throw and sink the whole render.
    val zero = catalogApplyTypography(base, "typo:bodyMedium=-/-/-/0")
    assertEquals(base.bodyMedium.fontWeight, zero.bodyMedium.fontWeight)
    val huge = catalogApplyTypography(base, "typo:bodyMedium=15/-/-/2000")
    assertEquals(base.bodyMedium.fontWeight, huge.bodyMedium.fontWeight)
    // A valid slot in the same spec still applies.
    assertEquals(15f, huge.bodyMedium.fontSize.value)
  }
}
