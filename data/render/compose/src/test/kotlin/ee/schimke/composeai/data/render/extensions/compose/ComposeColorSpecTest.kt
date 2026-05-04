package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeColorSpecTest {
  @Test
  fun resolvesRgbHexAsOpaqueColor() {
    assertEquals(Color(0xFF112233), ComposeColorSpec.resolve("#112233"))
  }

  @Test
  fun resolvesArgbHex() {
    assertEquals(Color(0x80112233), ComposeColorSpec.resolve("#80112233"))
  }

  @Test
  fun resolvesKnownComposeColorConstants() {
    assertEquals(Color.Black, ComposeColorSpec.resolve("Black"))
    assertEquals(Color.White, ComposeColorSpec.resolve("Color.White"))
    assertEquals(Color.Transparent, ComposeColorSpec.resolve("transparent"))
  }
}
