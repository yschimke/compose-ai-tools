package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@Preview(uiMode = 32)` (`UI_MODE_NIGHT_YES`) must flip the *composition* to dark, not just the
 * system-bar chrome — `DesktopRendererMain` provides `LocalSystemTheme` (which Compose Desktop's
 * `isSystemInDarkTheme()` reads) from the night bit. This pins both the [systemThemeFromUiMode]
 * mapping and that providing it actually flips `isSystemInDarkTheme()` in a render — the regression
 * where a dark `@Preview`'s cover PNG rendered its content in light colours.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class UiModeSystemThemeTest {

  @Test
  fun `uiMode night bits map to the system theme`() {
    assertEquals(androidx.compose.ui.SystemTheme.Dark, systemThemeFromUiMode(0x20))
    assertEquals(androidx.compose.ui.SystemTheme.Light, systemThemeFromUiMode(0x10))
    assertEquals(androidx.compose.ui.SystemTheme.Unknown, systemThemeFromUiMode(0))
    // Real `@Preview(uiMode = 32)` value carries only the night-yes bit.
    assertEquals(androidx.compose.ui.SystemTheme.Dark, systemThemeFromUiMode(32))
    // Unrelated bits (e.g. UI_MODE_TYPE_CAR) don't change the night decision.
    assertEquals(androidx.compose.ui.SystemTheme.Dark, systemThemeFromUiMode(0x20 or 0x03))
  }

  /** The single center pixel after rendering `isSystemInDarkTheme()`-driven fill under [uiMode]. */
  private fun centerColor(uiMode: Int): Triple<Int, Int, Int> {
    val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f))
    try {
      scene.setContent {
        CompositionLocalProvider(
          androidx.compose.ui.LocalSystemTheme provides systemThemeFromUiMode(uiMode)
        ) {
          // Green when the composition reports dark, red when light — a direct read of the flip the
          // renderer's provider drives. A bare Layout keeps the fill exactly the sandbox size.
          val fill = if (isSystemInDarkTheme()) Color.Green else Color.Red
          Layout(modifier = Modifier.fillMaxSize().background(fill)) { _, constraints ->
            layout(constraints.maxWidth, constraints.maxHeight) {}
          }
        }
      }
      scene.render()
      val image = scene.render()
      val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
      val bmp = ByteArrayInputStream(png.bytes).use { ImageIO.read(it) }
      val rgb = bmp.getRGB(8, 8)
      return Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    } finally {
      scene.close()
    }
  }

  @Test
  fun `night-yes uiMode makes isSystemInDarkTheme report dark in the render`() {
    val (r, g, b) = centerColor(0x20)
    assertTrue("expected green (dark branch) but was ($r,$g,$b)", g > 150 && r < 100 && b < 100)
  }

  @Test
  fun `night-no uiMode makes isSystemInDarkTheme report light in the render`() {
    val (r, g, b) = centerColor(0x10)
    assertTrue("expected red (light branch) but was ($r,$g,$b)", r > 150 && g < 100 && b < 100)
  }
}
