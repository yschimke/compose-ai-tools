package com.example.designcatalogm3.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The catalog's selectable **theme-override choices**, shared so every render tier resolves them
 * identically — the desktop `@Preview` sticker sheet ([com.example.designcatalogm3]
 * `CatalogSticker`) and the in-browser Wasm viewer ([com.example.cmpwasmcatalog] `CatalogApp`).
 * Both read the same `theme.font` / `theme.colors` knob and map the selected **name** here, so the
 * two tiers can never drift (the snapshot the desktop bakes and the live Wasm render agree).
 *
 * The names are exactly the declared `@TypographyCatalog` / `@ColorCatalog` labels the catalog
 * advertises, so the override registry stays in lockstep with what a viewer sees to pick.
 */

/** Knob keys the theme wrappers read. */
const val CATALOG_FONT_KNOB = "theme.font"
const val CATALOG_COLORS_KNOB = "theme.colors"

/** Typeface choice names (declared `@TypographyCatalog` labels). Roboto Flex is the default. */
const val CATALOG_FONT_ROBOTO_FLEX = "Roboto Flex"
const val CATALOG_FONT_GOOGLE_SANS_FLEX = "Google Sans Flex"
const val CATALOG_FONT_LOBSTER_TWO = "Lobster Two"

/**
 * Palette choice names (declared `@ColorCatalog` labels). `M3` is the default light/dark scheme.
 */
const val CATALOG_PALETTE_M3 = "M3"
const val CATALOG_PALETTE_CORAL = "Coral"
const val CATALOG_PALETTE_TEAL = "Teal"

/**
 * Resolves a selected palette [name] to a [ColorScheme]. [CATALOG_PALETTE_M3] (and any unknown
 * name) is the stock M3 light/dark scheme, honouring [dark]; the brand palettes are fixed-tone
 * schemes. Shared by the desktop and Wasm theme wrappers so a `theme.colors` override renders
 * identically in both.
 */
fun catalogColorScheme(name: String, dark: Boolean): ColorScheme =
  when (name) {
    CATALOG_PALETTE_CORAL ->
      lightColorScheme(
        primary = Color(0xFFFF6F61),
        secondary = Color(0xFFFFB4A9),
        tertiary = Color(0xFFB8860B),
      )
    CATALOG_PALETTE_TEAL ->
      darkColorScheme(
        primary = Color(0xFF4DD0E1),
        secondary = Color(0xFF80CBC4),
        tertiary = Color(0xFFFFE082),
      )
    else -> if (dark) darkColorScheme() else lightColorScheme()
  }
