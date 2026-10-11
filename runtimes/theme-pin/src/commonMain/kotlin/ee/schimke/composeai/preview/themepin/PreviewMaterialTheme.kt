package ee.schimke.composeai.preview.themepin

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The colour scheme a preview catalog pinned over every `MaterialTheme` in the preview, or `null`.
 * Apps install their own theme further in, and the innermost `MaterialTheme` normally wins;
 * [PreviewMaterialTheme] prefers this pinned scheme instead, however deep the app's theme is.
 */
public val LocalPinnedColorScheme: ProvidableCompositionLocal<ColorScheme?> =
  staticCompositionLocalOf {
    null
  }

/**
 * Drop-in for `androidx.compose.material3.MaterialTheme(colorScheme, shapes, typography, content)`.
 * Not called by hand: with theme pinning enabled, the compose-preview compiler plugin redirects the
 * app's `MaterialTheme` calls here. With no pinned scheme it passes its arguments through
 * unchanged.
 */
@Composable
public fun PreviewMaterialTheme(
  colorScheme: ColorScheme = MaterialTheme.colorScheme,
  shapes: Shapes = MaterialTheme.shapes,
  typography: Typography = MaterialTheme.typography,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = LocalPinnedColorScheme.current ?: colorScheme,
    shapes = shapes,
    typography = typography,
    content = content,
  )
}

/**
 * Drop-in for the expressive `androidx.compose.material3.MaterialTheme(colorScheme, motionScheme,
 * shapes, typography, content)` overload; see the four-parameter [PreviewMaterialTheme].
 */
@ExperimentalMaterial3ExpressiveApi
@Composable
public fun PreviewMaterialTheme(
  colorScheme: ColorScheme = MaterialTheme.colorScheme,
  motionScheme: MotionScheme = MaterialTheme.motionScheme,
  shapes: Shapes = MaterialTheme.shapes,
  typography: Typography = MaterialTheme.typography,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = LocalPinnedColorScheme.current ?: colorScheme,
    motionScheme = motionScheme,
    shapes = shapes,
    typography = typography,
    content = content,
  )
}

/**
 * Pins the colour scheme in effect here over every `MaterialTheme` [content] installs. A generated
 * theme provider calls the app's theme with the selected palette and wraps the preview in this:
 * `AppTheme(theme = Agami) { PinMaterialTheme { preview() } }`. Only colours are pinned; typography
 * and shapes stay as each nested theme sets them.
 */
@Composable
public fun PinMaterialTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalPinnedColorScheme provides MaterialTheme.colorScheme,
    content = content,
  )
}
