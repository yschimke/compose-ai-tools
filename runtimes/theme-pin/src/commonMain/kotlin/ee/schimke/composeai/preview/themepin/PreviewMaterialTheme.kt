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
 * The colour scheme a preview catalog has pinned over every `MaterialTheme` in the preview, or
 * `null` when no theme is selected.
 *
 * A catalog's theme switcher wraps a preview from the outside, but an app almost always installs
 * its own theme further in — `AppScaffold { AppTheme { … } }` — and the innermost `MaterialTheme`
 * wins, so every theme chip used to render the same pixels. Pinning reverses that precedence: a
 * theme selected outside is provided here, and [PreviewMaterialTheme] prefers it over whatever
 * scheme the app's own theme passes in, however deep that call is.
 */
public val LocalPinnedColorScheme: ProvidableCompositionLocal<ColorScheme?> =
  staticCompositionLocalOf {
    null
  }

/**
 * Drop-in for `androidx.compose.material3.MaterialTheme(colorScheme, shapes, typography, content)`
 * with the same parameters and defaults.
 *
 * Nothing calls this by hand. The compose-preview Kotlin compiler plugin, when a build opts into
 * theme pinning, redirects the app's own calls to `MaterialTheme` here at compile time — because it
 * has the identical signature, the redirect is a change of callee and nothing else. With no pinned
 * scheme (the default, and every render that has not selected a theme) it calls `MaterialTheme`
 * with exactly the arguments it was given.
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
 * Pins the colour scheme in effect here over every `MaterialTheme` [content] installs.
 *
 * A generated theme provider calls the app's own theme with the selected palette and wraps the
 * preview in this: `AppTheme(theme = Agami) { PinMaterialTheme { preview() } }`. The app's theme
 * reaches `MaterialTheme` through [PreviewMaterialTheme] too, so the scheme read here is the
 * selected palette — the generator never has to know how the app builds one — and every theme the
 * preview installs further in renders with it.
 *
 * Only the colour scheme is pinned. Typography and shapes stay whatever each nested theme sets, so
 * a preview keeps the app's own type scale.
 */
@Composable
public fun PinMaterialTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalPinnedColorScheme provides MaterialTheme.colorScheme,
    content = content,
  )
}
