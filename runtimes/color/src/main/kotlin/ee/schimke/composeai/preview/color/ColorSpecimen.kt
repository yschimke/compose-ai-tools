package ee.schimke.composeai.preview.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Renders every Material 3 colour role in [colorScheme] as a labelled swatch — `primary`,
 * `onPrimary`, `primaryContainer`, …, `surfaceContainerHighest` — so visual regressions in a custom
 * theme's `ColorScheme` surface as a pixel diff in the surrounding `@Preview`.
 *
 * This is the colour analogue of [ee.schimke.composeai.preview.typography] `TypographySpecimen`:
 * wrap a `ColorScheme` value (light or dark — flip via the ambient theme, or pass
 * `MaterialTheme.colorScheme` from inside a `@Preview(uiMode = …)`) and get a one-PNG audit of
 * every role at the exact ARGB the theme resolves to. Airbnb Showkase's `@ShowkaseColor` sheet is
 * the equivalent surface; here the roles are pulled straight off the `ColorScheme` type rather than
 * per-token annotations, so a stock `lightColorScheme()` renders with zero extra code.
 *
 * Roles are listed in the Material 3 reference order (accent families first — primary / secondary /
 * tertiary with their containers — then surfaces, then utility roles) so the rendered PNG diffs
 * cleanly across calls.
 */
@Composable
fun ColorSchemeSpecimen(colorScheme: ColorScheme, modifier: Modifier = Modifier) {
  ColorSpecimen(colors = colorSchemeRoles(colorScheme), modifier = modifier)
}

/**
 * Renders an arbitrary list of named [colors] as labelled swatches — one row each, a filled swatch
 * on the left and the role name plus its `#AARRGGBB` hex on the right. Use this directly for a
 * design system's own colour tokens (brand palette, semantic aliases) that don't live on a Material
 * 3 [ColorScheme]; [ColorSchemeSpecimen] is the convenience overload for the M3 roles.
 *
 * The list order is preserved verbatim so the caller controls row sequence and the rendered PNG is
 * deterministic.
 */
@Composable
fun ColorSpecimen(colors: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for ((label, color) in colors) {
      SwatchRow(label = label, color = color)
    }
  }
}

/**
 * Material 3 role order for [ColorSchemeSpecimen]. Returned as a stable `List<Pair<...>>` rather
 * than reflecting the `ColorScheme` properties so the row sequence is deterministic and matches the
 * M3 reference grouping (accent families → surfaces → utility) regardless of the declaration order
 * the underlying library uses.
 */
private fun colorSchemeRoles(scheme: ColorScheme): List<Pair<String, Color>> =
  listOf(
    "primary" to scheme.primary,
    "onPrimary" to scheme.onPrimary,
    "primaryContainer" to scheme.primaryContainer,
    "onPrimaryContainer" to scheme.onPrimaryContainer,
    "inversePrimary" to scheme.inversePrimary,
    "secondary" to scheme.secondary,
    "onSecondary" to scheme.onSecondary,
    "secondaryContainer" to scheme.secondaryContainer,
    "onSecondaryContainer" to scheme.onSecondaryContainer,
    "tertiary" to scheme.tertiary,
    "onTertiary" to scheme.onTertiary,
    "tertiaryContainer" to scheme.tertiaryContainer,
    "onTertiaryContainer" to scheme.onTertiaryContainer,
    "background" to scheme.background,
    "onBackground" to scheme.onBackground,
    "surface" to scheme.surface,
    "onSurface" to scheme.onSurface,
    "surfaceVariant" to scheme.surfaceVariant,
    "onSurfaceVariant" to scheme.onSurfaceVariant,
    "surfaceTint" to scheme.surfaceTint,
    "inverseSurface" to scheme.inverseSurface,
    "inverseOnSurface" to scheme.inverseOnSurface,
    "error" to scheme.error,
    "onError" to scheme.onError,
    "errorContainer" to scheme.errorContainer,
    "onErrorContainer" to scheme.onErrorContainer,
    "outline" to scheme.outline,
    "outlineVariant" to scheme.outlineVariant,
    "scrim" to scheme.scrim,
    "surfaceBright" to scheme.surfaceBright,
    "surfaceDim" to scheme.surfaceDim,
    "surfaceContainerLowest" to scheme.surfaceContainerLowest,
    "surfaceContainerLow" to scheme.surfaceContainerLow,
    "surfaceContainer" to scheme.surfaceContainer,
    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
  )

/**
 * One swatch row: a fixed-size filled square on the left, then the role name and its hex value. The
 * swatch carries a thin outline so a role that resolves to the same colour as the preview
 * background (e.g. `surface` on a `Surface`, or `onPrimary` ≈ white) is still bounded and visible.
 * Text sits outside the swatch — never on top of it — so the label stays legible regardless of the
 * swatch's contrast.
 */
@Composable
internal fun SwatchRow(label: String, color: Color) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      androidx.compose.foundation.layout.Box(
        modifier =
          Modifier.size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .border(1.dp, SwatchBorder, RoundedCornerShape(6.dp))
      )
    }
    Column(modifier = Modifier.padding(start = 12.dp)) {
      Text(text = label, style = LabelStyle)
      Text(text = hex(color), style = HexStyle)
    }
  }
}

/**
 * Formats [color] as an uppercase `#AARRGGBB` string via [Color.toArgb]. Always eight digits (alpha
 * included) so a semi-transparent role — `scrim` is typically black at partial alpha — reads as
 * such instead of looking identical to its opaque sibling. `Locale.ROOT` keeps the hex digits ASCII
 * regardless of the render environment's default locale.
 */
internal fun hex(color: Color): String = String.format(Locale.ROOT, "#%08X", color.toArgb())

/**
 * Label style for swatch rows — small, matches the specimen aesthetic in the typography runtime.
 */
internal val LabelStyle: TextStyle = TextStyle(fontSize = 13.sp)

/** Hex value style — monospace so the fixed-width hex digits align down the column. */
internal val HexStyle: TextStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)

/** Swatch outline colour — a mid grey visible against both light and dark preview backgrounds. */
internal val SwatchBorder: Color = Color(0xFF9E9E9E)
