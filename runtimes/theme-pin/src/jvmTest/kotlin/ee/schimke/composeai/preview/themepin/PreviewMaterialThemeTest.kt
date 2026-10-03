package ee.schimke.composeai.preview.themepin

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PreviewMaterialThemeTest {

  private val selected = darkColorScheme(primary = Color(0xFF1E88E5))
  private val appDefault = lightColorScheme(primary = Color(0xFFE53935))
  private val appTypography = Typography(bodyLarge = TextStyle(fontSize = 31.sp))

  /** The app's own theme, as it reaches `MaterialTheme` once the compiler plugin redirects it. */
  @Composable
  private fun AppTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    PreviewMaterialTheme(colorScheme = colorScheme, typography = appTypography, content = content)
  }

  @Test
  fun withNothingPinnedTheAppsOwnSchemeIsUsed() = runComposeUiTest {
    var seen: ColorScheme? = null
    setContent { AppTheme(appDefault) { seen = MaterialTheme.colorScheme } }
    waitForIdle()
    assertSame(appDefault, seen)
  }

  @Test
  fun aPinnedSchemeWinsOverATheme_installedFurtherIn() = runComposeUiTest {
    var seen: ColorScheme? = null
    setContent {
      // The generated provider: the app's theme with the selected palette, pinned around the
      // preview — which then installs the app's default theme again, as Heron's scaffold does.
      AppTheme(selected) {
        PinMaterialTheme { AppTheme(appDefault) { seen = MaterialTheme.colorScheme } }
      }
    }
    waitForIdle()
    assertSame(selected, seen)
  }

  @Test
  fun onlyTheColourSchemeIsPinned() = runComposeUiTest {
    var seen: Typography? = null
    setContent {
      PreviewMaterialTheme(colorScheme = selected) {
        PinMaterialTheme { AppTheme(appDefault) { seen = MaterialTheme.typography } }
      }
    }
    waitForIdle()
    assertEquals(31.sp, seen?.bodyLarge?.fontSize)
  }
}
