package com.example.designcatalogm3.shared

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
