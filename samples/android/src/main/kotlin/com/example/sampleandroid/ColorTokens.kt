package com.example.sampleandroid

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.preview.ColorCatalog

/**
 * Design tokens annotated with `@ColorCatalog` — the compose-ai-tools analogue of Showkase's
 * `@ShowkaseColor`. No `@Preview` is written for these: the compose-preview plugin discovers the
 * annotated `Color` properties from bytecode and synthesises catalog sheets — one per `group`, plus
 * a module-wide "All colours" sheet — that the renderer draws by reflecting each value. `name`
 * defaults to the property name; `Scrim` is deliberately translucent so the sheet's `#AARRGGBB` hex
 * shows the alpha byte surviving the value-class reflection round-trip.
 */
@ColorCatalog(group = "Brand") val BrandCoral: Color = Color(0xFFFF6F61)

@ColorCatalog(group = "Brand") val BrandTeal: Color = Color(0xFF008080)

@ColorCatalog(name = "Brand Gold", group = "Brand") val BrandGold: Color = Color(0xFFFFD700)

@ColorCatalog(group = "Semantic") val Success: Color = Color(0xFF2E7D32)

@ColorCatalog(group = "Semantic") val Warning: Color = Color(0xFFF9A825)

@ColorCatalog(group = "Semantic") val Danger: Color = Color(0xFFC62828)

@ColorCatalog(group = "Semantic") val Scrim: Color = Color(0x80000000)
