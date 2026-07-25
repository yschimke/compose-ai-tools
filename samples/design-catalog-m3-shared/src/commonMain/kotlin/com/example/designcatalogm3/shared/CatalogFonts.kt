package com.example.designcatalogm3.shared

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Generic-family substitutes for the running catalog: family name (`serif`, `monospace`, …) → the
 * [FontFamily] holding the same files the platform's system font table resolves that name to (Noto
 * Serif / Droid Sans Mono). Empty (the default) ⇒ [genericFontFamily] falls back to the platform's
 * own generic constant.
 *
 * A composition local rather than a wrapped `FontFamily.Resolver` because CMP's
 * `FontFamily.Resolver` is a **sealed** interface — it can't be implemented outside compose-ui, so
 * true resolver-level interception isn't available to apps. Catalog components therefore say
 * `genericFontFamily("serif")` where the original Android catalog said `FontFamily.Serif`; the
 * lookup is the only permitted divergence, and it is shared so the desktop render and the wasm tier
 * resolve identically.
 */
val LocalGenericFonts = staticCompositionLocalOf<Map<String, FontFamily>> { emptyMap() }

/**
 * The [FontFamily] for a generic family [name], preferring the catalog's supplied substitute
 * ([LocalGenericFonts]) and falling back to the platform's generic constant.
 */
@Composable
fun genericFontFamily(name: String): FontFamily =
  LocalGenericFonts.current[name]
    ?: when (name) {
      "serif" -> FontFamily.Serif
      "monospace" -> FontFamily.Monospace
      "cursive" -> FontFamily.Cursive
      else -> FontFamily.SansSerif
    }

/**
 * Named downloadable-GoogleFont substitutes for the running catalog: the font's display name
 * (`Orbitron`, `Space Grotesk`, …) → the [FontFamily] holding the faces vendored for it (the same
 * ones Android's downloadable-font provider fetches at that name). Empty (the default) ⇒
 * [namedFontFamily] falls back to the M3 default sans, exactly as if the manifest had never listed
 * the family.
 *
 * Same rationale as [LocalGenericFonts]: CMP's `FontFamily.Resolver` is **sealed**, so a
 * `Font(GoogleFont("Orbitron"))` request can't be intercepted at the resolver on desktop/wasm.
 * Catalog components therefore say `namedFontFamily("Orbitron")` where an Android-only component
 * would say `FontFamily(Font(GoogleFont("Orbitron"), provider))`; the lookup is shared so the
 * desktop render and the wasm tier resolve the branded face identically.
 */
val LocalNamedFonts = staticCompositionLocalOf<Map<String, FontFamily>> { emptyMap() }

/**
 * The [FontFamily] for a named GoogleFont [name], preferring the catalog's supplied vendored faces
 * ([LocalNamedFonts]). Falls back to [fallback] (default: the platform sans) when the tier didn't
 * vendor that family — the same graceful degradation the manifest generator assumes when it drops a
 * face the dist doesn't carry.
 */
@Composable
fun namedFontFamily(name: String, fallback: FontFamily = FontFamily.SansSerif): FontFamily =
  LocalNamedFonts.current[name] ?: fallback

/**
 * The stock M3 [Typography] with every style re-pointed at [fontFamily] — the type scale keeps its
 * real Material sizes/weights/line-heights, only the typeface changes (to the Roboto both the
 * desktop render and the baked snapshots use). Null ⇒ the untouched default scale (the platform
 * default).
 */
fun catalogTypography(fontFamily: FontFamily?): Typography {
  val base = Typography()
  if (fontFamily == null) return base
  return Typography(
    displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
    displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
    displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
    titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
    titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
    labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
    labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
    labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
  )
}

/**
 * [base] with each M3 role group's typeface swapped to the [families] map's GoogleFont family — the
 * `theme.fonts` counterpart to [catalogTypography]'s single face. [families] is keyed by role group
 * (`display`/`headline`/`title`/`body`/`label`, see `parseCatalogFontFamilies`); each name resolves
 * against [named] — the tier's vendored faces (the desktop [Map] `CatalogNamedFonts`, the wasm
 * tier's equivalent) — so `display=Orbitron,body=Space Grotesk` brands the whole scale identically
 * everywhere. A group [families] omits keeps [base]'s face (no copy); a specified-but-unvendored
 * family degrades to [fallback]. Overlay the `theme.typography` **metrics** on the result. Empty
 * [families] ⇒ [base] unchanged. Pure (the [named] lookup is passed in, not read from a composition
 * local) so it applies before the theme's `LocalNamedFonts` provider and is unit-testable.
 */
fun catalogApplyFontFamilies(
  base: Typography,
  families: Map<String, String>,
  named: Map<String, FontFamily>,
  fallback: FontFamily = FontFamily.SansSerif,
): Typography {
  if (families.isEmpty()) return base
  fun apply(style: TextStyle, group: String): TextStyle {
    val family = families[group]?.let { named[it] ?: fallback } ?: return style
    return style.copy(fontFamily = family)
  }
  return base.copy(
    displayLarge = apply(base.displayLarge, "display"),
    displayMedium = apply(base.displayMedium, "display"),
    displaySmall = apply(base.displaySmall, "display"),
    headlineLarge = apply(base.headlineLarge, "headline"),
    headlineMedium = apply(base.headlineMedium, "headline"),
    headlineSmall = apply(base.headlineSmall, "headline"),
    titleLarge = apply(base.titleLarge, "title"),
    titleMedium = apply(base.titleMedium, "title"),
    titleSmall = apply(base.titleSmall, "title"),
    bodyLarge = apply(base.bodyLarge, "body"),
    bodyMedium = apply(base.bodyMedium, "body"),
    bodySmall = apply(base.bodySmall, "body"),
    labelLarge = apply(base.labelLarge, "label"),
    labelMedium = apply(base.labelMedium, "label"),
    labelSmall = apply(base.labelSmall, "label"),
  )
}
