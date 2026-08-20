package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.data.theme.ResolvedThemeTokens
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.data.theme.TypographyToken

/**
 * Theme-token reading that never mentions Material 3.
 *
 * Everything here reads a theme object *structurally* — "the no-arg getters that return a
 * `TextStyle`", not "`Typography.labelMedium`" — which is what lets one implementation serve two
 * design systems. [ThemeDataProduct]'s typed reader delegates its non-Material-3 branches here
 * rather than carrying a second copy of the same reflection.
 *
 * The separation is a **runtime** requirement, not tidiness. Material 3 is `compileOnly` in the
 * daemon on purpose: a Wear app themes with `androidx.wear.compose.material3` and does not have
 * `androidx.compose.material3` on its classpath at all, so merely *executing* an `is
 * androidx.compose.material3.ColorScheme` check against such a render throws
 * `NoClassDefFoundError`. That is why the Wear lane calls [themePayloadFromDuckTypedTheme] instead
 * of the typed capture: same readers, no Material 3 instruction anywhere on the path.
 */
internal object DuckTypedThemeTokens {

  /**
   * Colour roles read off any scheme-shaped object. Compose's `Color` is a `@JvmInline value
   * class`, so a Kotlin scheme's getters return a raw `long` under a name-mangled getter — both
   * shapes are accepted, and [TokenObjectAccess] strips the mangling from the role name.
   */
  fun colorScheme(source: Any): Map<String, String> =
    TokenObjectAccess.colorProperties(source).mapValues { (_, value) ->
      when (value) {
        is Color -> value.hexArgb()
        is Long -> Color(value.toULong()).hexArgb()
        else -> error("Unsupported color token value ${value::class.java.name}")
      }
    }

  /** Type-scale roles read off any typography-shaped object. */
  fun typography(source: Any): Map<String, TypographyToken> =
    TokenObjectAccess.textStyleProperties(source).mapValues { (_, value) -> value.token() }

  /** Shape-scale roles read off any shapes-shaped object. */
  fun shapes(source: Any): Map<String, String> =
    TokenObjectAccess.shapeProperties(source).mapValues { (_, value) -> value.toString() }
}

/**
 * The `compose/theme` payload for a render whose theme is **not** Material 3 — today that means
 * Wear Compose Material 3, whose `MaterialTheme` provides a `ColorScheme` / `Typography` / `Shapes`
 * of its own types, read here as opaque handles.
 *
 * Without this the Wear lane reported no theme at all: the daemon probes
 * `androidx.compose.material3` on the consumer's classloader and skips the whole capture when it
 * isn't there (which for a Wear app it never is), so `resolvedTokens.typography` came back empty
 * and every Wear text node's typography annotation was drawn with **no Material role named** —
 * `15.0sp/18.0sp · 500` where the Material 3 catalog beside it reads
 * `MaterialTheme.typography.labelSmall · 11.0sp/16.0sp · …` (issue #4327). The tokens are then
 * attributed to nodes by the same resolved-value matching Material 3 uses; nothing downstream needs
 * to know which design system produced them.
 *
 * Null when [colorSource] names no colour roles — a scheme this can't read attributes every node to
 * nothing, and reporting an empty theme would only claim the render has none.
 */
fun themePayloadFromDuckTypedTheme(
  colorSource: Any?,
  typographySource: Any?,
  shapesSource: Any?,
): ThemePayload? {
  val colorScheme = colorSource?.let(DuckTypedThemeTokens::colorScheme).orEmpty()
  if (colorScheme.isEmpty()) return null
  return ThemePayload(
    resolvedTokens =
      ResolvedThemeTokens(
        colorScheme = colorScheme,
        typography = typographySource?.let(DuckTypedThemeTokens::typography).orEmpty(),
        shapes = shapesSource?.let(DuckTypedThemeTokens::shapes).orEmpty(),
      ),
    consumers = emptyList(),
  )
}
