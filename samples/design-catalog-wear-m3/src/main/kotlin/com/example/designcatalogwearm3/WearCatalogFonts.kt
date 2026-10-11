@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogwearm3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.ColorCatalog
import ee.schimke.composeai.preview.ShapeCatalog
import ee.schimke.composeai.preview.TypographyCatalog

// The Wear catalog's declared design-token catalogs (type / colour / shape), the Wear sibling of
// `:samples:design-catalog-m3`'s `CatalogCatalogs.kt`. Rendered on Robolectric, so these draw real
// specimen sheets.
//
// Every typeface is a `@TypographyCatalog` specimen resolved as a downloadable Google font (no
// vendored TTFs; see [googleFontProvider]): Roboto Flex (default), Google Sans Flex, Lobster Two (a
// deliberately distinctive face), and JetBrains Mono / Inter (Confetti Wear's KotlinConf pairing,
// combined by [wearCatalogTypography]).

/**
 * The GMS Fonts provider. Under Robolectric, `ShadowFontsContractCompat` serves a TTF from the
 * shared `~/.cache/composeai/fonts/` cache instead. The cert array is empty because the shadow
 * skips signature verification and this catalog never ships to a device.
 */
private val googleFontProvider =
  GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
  )

/** Roboto Flex — the catalog's default typeface, resolved as a downloadable Google font. */
val RobotoFlex: FontFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("Roboto Flex"), googleFontProvider, weight = FontWeight.Normal)
  )

/**
 * Google Sans Flex, the Material 3 Expressive brand face. Not in the `google/fonts` corpus, but the
 * CSS2 endpoint serves it, which is all the downloadable-font path needs.
 */
val GoogleSansFlex: FontFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("Google Sans Flex"), googleFontProvider, weight = FontWeight.Normal)
  )

/** Lobster Two — a selectable display face, resolved as a downloadable Google font. */
val LobsterTwo: FontFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("Lobster Two"), googleFontProvider, weight = FontWeight.Normal)
  )

/** JetBrains Mono — the KotlinConf title face (see [wearCatalogTypography]). */
val JetBrainsMono: FontFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("JetBrains Mono"), googleFontProvider, weight = FontWeight.Normal)
  )

/** Inter — the KotlinConf body face (see [wearCatalogTypography]). */
val Inter: FontFamily =
  FontFamily(GoogleFontFont(GoogleFont("Inter"), googleFontProvider, weight = FontWeight.Normal))

// --- Roboto Flex type-scale specimens (the default face)
// ------------------------------------------

@TypographyCatalog(name = "Display", group = "Roboto Flex")
val RobotoFlexDisplay: TextStyle =
  TextStyle(fontFamily = RobotoFlex, fontSize = 40.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Title", group = "Roboto Flex")
val RobotoFlexTitle: TextStyle =
  TextStyle(fontFamily = RobotoFlex, fontSize = 20.sp, fontWeight = FontWeight.Medium)

@TypographyCatalog(name = "Body", group = "Roboto Flex")
val RobotoFlexBody: TextStyle =
  TextStyle(fontFamily = RobotoFlex, fontSize = 15.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Label", group = "Roboto Flex")
val RobotoFlexLabel: TextStyle =
  TextStyle(fontFamily = RobotoFlex, fontSize = 12.sp, fontWeight = FontWeight.Medium)

// --- Google Sans Flex type-scale specimens (the Material 3 Expressive brand face)
// -----------------

@TypographyCatalog(name = "Display", group = "Google Sans Flex")
val GoogleSansFlexDisplay: TextStyle =
  TextStyle(fontFamily = GoogleSansFlex, fontSize = 40.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Title", group = "Google Sans Flex")
val GoogleSansFlexTitle: TextStyle =
  TextStyle(fontFamily = GoogleSansFlex, fontSize = 20.sp, fontWeight = FontWeight.Medium)

@TypographyCatalog(name = "Body", group = "Google Sans Flex")
val GoogleSansFlexBody: TextStyle =
  TextStyle(fontFamily = GoogleSansFlex, fontSize = 15.sp, fontWeight = FontWeight.Normal)

// --- Lobster Two type-scale specimens (a selectable display face) --------------------------------

@TypographyCatalog(name = "Display", group = "Lobster Two")
val LobsterTwoDisplay: TextStyle =
  TextStyle(fontFamily = LobsterTwo, fontSize = 40.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Title", group = "Lobster Two")
val LobsterTwoTitle: TextStyle =
  TextStyle(fontFamily = LobsterTwo, fontSize = 20.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Body", group = "Lobster Two")
val LobsterTwoBody: TextStyle =
  TextStyle(fontFamily = LobsterTwo, fontSize = 15.sp, fontWeight = FontWeight.Normal)

// --- KotlinConf pairing specimens (JetBrains Mono titles + Inter body) ---------------------------

@TypographyCatalog(name = "Display", group = "JetBrains Mono")
val JetBrainsMonoDisplay: TextStyle =
  TextStyle(fontFamily = JetBrainsMono, fontSize = 40.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Title", group = "JetBrains Mono")
val JetBrainsMonoTitle: TextStyle =
  TextStyle(fontFamily = JetBrainsMono, fontSize = 20.sp, fontWeight = FontWeight.Medium)

@TypographyCatalog(name = "Body", group = "JetBrains Mono")
val JetBrainsMonoBody: TextStyle =
  TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Display", group = "Inter")
val InterDisplay: TextStyle =
  TextStyle(fontFamily = Inter, fontSize = 40.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(name = "Title", group = "Inter")
val InterTitle: TextStyle =
  TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.Medium)

@TypographyCatalog(name = "Body", group = "Inter")
val InterBody: TextStyle =
  TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Normal)

// --- Colour-role tokens
// ---------------------------------------------------------------------------

@ColorCatalog(name = "primary", group = "Wear palette") val WearPrimary: Color = Color(0xFFAECBFA)

@ColorCatalog(name = "secondary", group = "Wear palette")
val WearSecondary: Color = Color(0xFFA8DAB5)

@ColorCatalog(name = "surface", group = "Wear palette") val WearSurface: Color = Color(0xFF202124)

@ColorCatalog(name = "error", group = "Wear palette") val WearError: Color = Color(0xFFF28B82)

// --- Shape tokens
// ---------------------------------------------------------------------------------

@ShapeCatalog(name = "small", group = "Wear shapes")
val WearSmallShape: Shape = RoundedCornerShape(8.dp)

@ShapeCatalog(name = "medium", group = "Wear shapes")
val WearMediumShape: Shape = RoundedCornerShape(18.dp)

@ShapeCatalog(name = "full", group = "Wear shapes")
val WearFullShape: Shape = RoundedCornerShape(50)
