package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.preview.CatalogVariant

// --- Internationalisation / accessibility axes ---
//
// The same two representative components — the filled button and the on switch — rendered under the
// i18n/a11y dimensions, declared as named `props` variants (`locale` / `direction` / `fontScale`)
// on their parent sticker in `catalog.spec.json`, mirroring the `content: icon+label` content-axis
// variant. Pure Compose, no renderer change:
//   * **pseudolocale** reuses the repo's existing pseudolocale-in-previews mechanism —
//     `@Preview(locale = "ar-XB")`, the `Pseudolocale.BIDI` tag the desktop renderer recognises and
//     flips to RTL (see `:samples:cmp`'s `CmpPseudoBidi`). Desktop CMP pseudolocalises layout
//     direction, not text (`org.jetbrains.compose.resources` doesn't go through
//     `LocalContext.resources`), so `en-XA` accent-expansion isn't visible here; the `ar-XB` bidi
//     pseudolocale is, so it's the one that carries visible evidence.
//   * **direction** forces `LocalLayoutDirection = Rtl` directly (layout direction is a composition
//     property the renderer captures, so an override is faithful in both the PNG and the SVG
// export).
//   * **fontScale** is set on the `@Preview` itself (`fontScale = 2f`), not via a `LocalDensity`
//     override: the design-artifacts SVG export reads `fontScale` from the render spec (the preview
//     params), so driving it from the annotation keeps the PNG and the exported SVG/text metadata
// in
//     lockstep at 2.0 (large-text / dynamic-type).

// The filled button carries only the fontScale axis: a centred label has nothing to mirror, so its
// pseudolocale and forced-RTL captures were pixel-identical to the plain one (three previews, one
// result). The switch row below keeps both — RTL genuinely mirrors its thumb, so the axis shows
// something there.

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

// A REAL RTL locale, as opposed to the `ar-XB` pseudolocale above. This is the regression guard for
// the bug where the desktop batch renderers keyed their `LayoutDirection` flip off
// `Pseudolocale.isRtl` alone: `ar-XB` mirrored, `ar` did not, so a catalog with real Arabic
// translations rendered correctly shaped Arabic inside a left-to-right container — the leading
// swatch still on the left, the text column still starting at the left edge. It read as "RTL is
// fine" precisely where RTL had never been exercised.
//
// The slotted card is the sticker that shows it: it has BOTH a leading region and a text column, so
// a mirror is unmistakable, and its headline/supporting copy comes from `strings.xml` — so this one
// capture proves the two halves of a locale override together (translated copy AND mirrored
// layout), which no `ar-XB` capture can (desktop CMP pseudolocalises direction, not text).
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
