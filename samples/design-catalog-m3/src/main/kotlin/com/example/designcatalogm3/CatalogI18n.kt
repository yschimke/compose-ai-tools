package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.preview.CatalogVariant

// Internationalisation / accessibility axes: representative components rendered as `props` variants
// (`locale` / `direction` / `fontScale`):
//   * pseudolocale: `@Preview(locale = "ar-XB")`, which the desktop renderer flips to RTL. Desktop
//     CMP pseudolocalises direction, not text, so `en-XA` would show nothing.
//   * direction: forces `LocalLayoutDirection = Rtl`, captured faithfully in PNG and SVG.
//   * fontScale: set on the `@Preview` (not `LocalDensity`) because the SVG export reads it from the
//     preview params, keeping PNG and SVG in step at 2.0.

// The filled button carries only fontScale: a centred label has nothing to mirror. The switch keeps
// both, since RTL mirrors its thumb.

@CatalogVariant(
  of = "Button/Filled",
  props = ["fontScale=2.0"],
  caption =
    "Accessibility axis: 2× font scale (LocalDensity fontScale = 2.0) — large-text / dynamic-type " +
      "stress.",
)
@Preview(name = "Light", fontScale = 2f, group = "modes")
@Preview(name = "Dark", fontScale = 2f, uiMode = 32, group = "modes")
@Composable
fun FilledButtonLargeFont() = Sticker("button-filled")

// List row — the on switch (a settings-style selection row).
@CatalogVariant(
  of = "Switch/On",
  props = ["locale=ar-XB"],
  caption =
    "i18n axis: the ar-XB bidi pseudolocale — flips the row to RTL layout (desktop CMP " +
      "pseudolocalises layout direction, not text).",
)
@Preview(name = "Light", locale = "ar-XB", group = "modes")
@Preview(name = "Dark", locale = "ar-XB", uiMode = 32, group = "modes")
@Composable
fun SwitchOnPseudo() = Sticker("switch-on")

@CatalogVariant(
  of = "Switch/On",
  props = ["direction=rtl"],
  caption = "i18n axis: forced RTL layout direction (LocalLayoutDirection = Rtl).",
)
@CatalogModes
@Composable
fun SwitchOnRtl() =
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Sticker("switch-on")
  }

@CatalogVariant(
  of = "Switch/On",
  props = ["fontScale=2.0"],
  caption =
    "Accessibility axis: 2× font scale (LocalDensity fontScale = 2.0) — large-text / dynamic-type " +
      "stress.",
)
@Preview(name = "Light", fontScale = 2f, group = "modes")
@Preview(name = "Dark", fontScale = 2f, uiMode = 32, group = "modes")
@Composable
fun SwitchOnLargeFont() = Sticker("switch-on")

// A real RTL locale (`ar`), guarding that layout direction follows real locales and not just the
// `ar-XB` pseudolocale. The slotted card has a leading region and translated `strings.xml` copy, so
// one capture shows both translated text and mirrored layout.
@CatalogVariant(
  of = "Card/Slots",
  props = ["locale=ar"],
  caption =
    "i18n axis: a real RTL locale (ar) — Arabic copy from values-ar AND mirrored layout, the two " +
      "halves a locale override applies together.",
)
@Preview(name = "Light", locale = "ar", group = "modes")
@Preview(name = "Dark", locale = "ar", uiMode = 32, group = "modes")
@Composable
fun SlottedCardArabic() = Sticker("card-slots")
