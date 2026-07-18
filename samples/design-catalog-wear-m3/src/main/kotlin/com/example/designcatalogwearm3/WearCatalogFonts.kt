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

/**
 * The Wear catalog's **declared design-token catalogs** — the type / colour / shape choices the
 * theme-override surface autodetects, the Wear sibling of `:samples:design-catalog-m3`'s
 * `CatalogCatalogs.kt`. Wear renders on the Android (Robolectric) backend, so these token-level
 * catalogs draw real specimen sheets (unlike the desktop M3 module, whose sheets await #2135).
 *
 * The two typefaces are declared as `@TypographyCatalog` specimens so the sheet shows each face on
 * the same type scale, and resolve as **downloadable Google fonts** rather than vendored TTFs — so
 * the module ships no `res/font` faces and every packed bundle stays ~2 MB smaller while remaining
 * self-contained (the renderer fetches + caches the face; see [googleFontProvider]):
 * * **Roboto Flex** — the default, `GoogleFont("Roboto Flex")` (the variable Roboto Flex from
 *   fonts.google.com's `ofl/robotoflex`).
 * * **Google Sans Flex** — a named face that isn't distributed on fonts.google.com (Google Sans is a
 *   Google-brand font), so it degrades to the platform sans until a face is supplied; the choice is
 *   declared regardless.
 */

/**
 * The GMS Fonts provider the catalog's typefaces resolve through. On a device it reaches Google Play
 * Services; under the renderer's Robolectric harness `ShadowFontsContractCompat` intercepts the
 * request and hands back a TTF from the shared `~/.cache/composeai/fonts/` cache (downloaded once
 * from `fonts.googleapis.com`), so no font bytes are vendored or packed into the bundle. The cert
 * array is empty: the shadow short-circuits before signature verification, and this catalog is only
 * ever rendered, never shipped to a device (mirrors `:samples:android`'s `FontPreviewWrapper`).
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

/** Google Sans Flex — declared choice; graceful sans fallback until a brand face is supplied. */
val GoogleSansFlex: FontFamily = FontFamily.SansSerif

/** Lobster Two — a selectable display face, resolved as a downloadable Google font. */
val LobsterTwo: FontFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("Lobster Two"), googleFontProvider, weight = FontWeight.Normal)
  )

// --- Roboto Flex type-scale specimens (the default face) ------------------------------------------

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

// --- Google Sans Flex type-scale specimens (declared choice, graceful fallback) -------------------

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

// --- Colour-role tokens ---------------------------------------------------------------------------

@ColorCatalog(name = "primary", group = "Wear palette") val WearPrimary: Color = Color(0xFFAECBFA)

@ColorCatalog(name = "secondary", group = "Wear palette") val WearSecondary: Color = Color(0xFFA8DAB5)

@ColorCatalog(name = "surface", group = "Wear palette") val WearSurface: Color = Color(0xFF202124)

@ColorCatalog(name = "error", group = "Wear palette") val WearError: Color = Color(0xFFF28B82)

// --- Shape tokens ---------------------------------------------------------------------------------

@ShapeCatalog(name = "small", group = "Wear shapes") val WearSmallShape: Shape = RoundedCornerShape(8.dp)

@ShapeCatalog(name = "medium", group = "Wear shapes")
val WearMediumShape: Shape = RoundedCornerShape(18.dp)

@ShapeCatalog(name = "full", group = "Wear shapes") val WearFullShape: Shape = RoundedCornerShape(50)
