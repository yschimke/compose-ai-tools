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
 * Renders every Material 3 colour role in [colorScheme] as a labelled swatch, so regressions in a
 * custom theme's `ColorScheme` show up as a pixel diff. The colour analogue of
 * [ee.schimke.composeai.preview.typography] `TypographySpecimen` (and of Showkase's
 * `@ShowkaseColor`), but read straight off the `ColorScheme` with no per-token annotations. Roles
 * follow the M3 reference order: accent families, surfaces, utility roles.
 */
@Composable
fun ColorSchemeSpecimen(colorScheme: ColorScheme, modifier: Modifier = Modifier) {
  ColorSpecimen(colors = colorSchemeRoles(colorScheme), modifier = modifier)
}

/**
 * Renders an arbitrary list of named [colors] as labelled swatches (swatch, name, `#AARRGGBB`), for
 * tokens that don't live on a [ColorScheme]. Order is preserved.
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
 * Material 3 role order for [ColorSchemeSpecimen], listed explicitly rather than reflected so it is
 * deterministic and grouped as in the M3 reference.
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
 * One swatch row: an outlined square (visible even when it matches the background), then the role
 * name and hex value beside it rather than on top of it.
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
 * [color] as uppercase `#AARRGGBB`; always eight digits so partial alpha (e.g. `scrim`) is visible.
 * `Locale.ROOT` keeps the digits ASCII.
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
