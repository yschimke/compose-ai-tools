package com.example.designcatalogm3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.example.designcatalogm3.shared.CATALOG_PALETTE_CORAL
import com.example.designcatalogm3.shared.CATALOG_PALETTE_TEAL
import com.example.designcatalogm3.shared.catalogColorScheme
import com.example.designcatalogm3.shared.catalogTypography
import ee.schimke.composeai.preview.ColorCatalog
import ee.schimke.composeai.preview.ShapeCatalog
import ee.schimke.composeai.preview.TypographyCatalog

// The catalog's declared theme objects (`@TypographyCatalog` / `@ColorCatalog` / `@ShapeCatalog` on
// whole `Typography` / `ColorScheme` / `Shapes` values). Discovery synthesises a specimen sheet for
// each and the theme-override surface offers them as choices, so font choices live at theme level.
// The desktop backend doesn't draw specimen sheets yet (#2135), so these are discovered but
// rendered elsewhere.

/** Roboto Flex — the whole default M3 type scale re-pointed at the variable Roboto Flex face. */
@TypographyCatalog(name = "Roboto Flex", group = "Typeface")
val RobotoFlexTypography: Typography = catalogTypography(RobotoFlex)

/**
 * Google Sans Flex — the whole M3 type scale on the named Google Sans Flex face (graceful sans).
 */
@TypographyCatalog(name = "Google Sans Flex", group = "Typeface")
val GoogleSansFlexTypography: Typography = catalogTypography(GoogleSansFlex)

/** Lobster Two — the whole M3 type scale re-pointed at the Lobster Two display face. */
@TypographyCatalog(name = "Lobster Two", group = "Typeface")
val LobsterTwoTypography: Typography = catalogTypography(LobsterTwo)

/** The stock Material 3 **light** colour scheme — the catalog's default palette. */
@ColorCatalog(name = "Light scheme", group = "Palette")
val CatalogLightScheme: ColorScheme = lightColorScheme()

/** The stock Material 3 **dark** colour scheme. */
@ColorCatalog(name = "Dark scheme", group = "Palette")
val CatalogDarkScheme: ColorScheme = darkColorScheme()

/** Coral brand palette — a selectable colour override (`knob.theme.colors=Coral`). */
@ColorCatalog(name = "Coral", group = "Palette")
val CoralScheme: ColorScheme = catalogColorScheme(CATALOG_PALETTE_CORAL, dark = false)

/** Teal brand palette — a selectable colour override (`knob.theme.colors=Teal`). */
@ColorCatalog(name = "Teal", group = "Palette")
val TealScheme: ColorScheme = catalogColorScheme(CATALOG_PALETTE_TEAL, dark = true)

/**
 * The stock Material 3 shape scale (`extraSmall` … `extraLarge`) — the catalog's default shapes.
 */
@ShapeCatalog(name = "M3 shapes", group = "Shape") val CatalogShapes: Shapes = Shapes()
