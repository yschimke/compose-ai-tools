package com.example.designcatalogm3.shared

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Generic-family substitutes: `serif`, `monospace`, … → the [FontFamily] with the files the
 * platform maps that name to. Empty ⇒ [genericFontFamily] uses the platform constant.
 *
 * A composition local because CMP's `FontFamily.Resolver` is sealed and can't be intercepted, so
 * catalog components call `genericFontFamily("serif")` instead of `FontFamily.Serif`.
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
 * Named GoogleFont substitutes: display name (`Orbitron`, …) → the vendored [FontFamily]. Empty ⇒
 * [namedFontFamily] falls back to the default sans. Same sealed-resolver reason as
 * [LocalGenericFonts]: components call `namedFontFamily("Orbitron")` instead of
 * `FontFamily(Font(GoogleFont("Orbitron"), provider))`.
 */
val LocalNamedFonts = staticCompositionLocalOf<Map<String, FontFamily>> { emptyMap() }

/**
 * The [FontFamily] for a named GoogleFont [name] from [LocalNamedFonts], or [fallback] (default:
 * the platform sans) when not vendored.
 */
@Composable
fun namedFontFamily(name: String, fallback: FontFamily = FontFamily.SansSerif): FontFamily =
  LocalNamedFonts.current[name] ?: fallback

/**
 * The stock M3 [Typography] with every style re-pointed at [fontFamily] (sizes and weights kept).
 * Null ⇒ the untouched default scale.
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
 * [base] with each M3 role group's typeface (`display`/`headline`/`title`/`body`/`label`) swapped
 * to the [families] entry resolved against [named] — the `theme.fonts` counterpart to
 * [catalogTypography]. Omitted groups keep [base]'s face; unvendored ones use [fallback]. Pure, so
 * it applies before the theme's `LocalNamedFonts` provider and is unit-testable.
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
