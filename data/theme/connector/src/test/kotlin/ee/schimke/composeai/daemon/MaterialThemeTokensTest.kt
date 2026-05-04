package ee.schimke.composeai.daemon

import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MaterialThemeTokensTest {
  @Test
  fun readsColorSchemeFromTokenObject() {
    val source = ReflectedThemeTokens()

    val tokens = MaterialThemeTokens.colorScheme(source)

    assertEquals(Color.Red.hexArgb(), tokens["primary"])
    assertEquals(Color(0xFF00FF00u).hexArgb(), tokens["secondary"])
    assertFalse(tokens.containsKey("ignored"))
  }

  @Test
  fun readsTypographyFromTokenObject() {
    val source = ReflectedThemeTokens()

    val tokens = MaterialThemeTokens.typography(source)

    assertEquals(16f, tokens.getValue("bodyLarge").fontSize)
    assertFalse(tokens.containsKey("primary"))
  }

  @Test
  fun readsShapesFromTokenObject() {
    val source = ReflectedThemeTokens()

    val tokens = MaterialThemeTokens.shapes(source)

    assertEquals(ShapeDefaults.Small.toString(), tokens["small"])
    assertFalse(tokens.containsKey("bodyLarge"))
  }

  @Suppress("unused")
  private class ReflectedThemeTokens {
    fun getPrimary(): Color = Color.Red

    fun getSecondary(): Long = 0xFF00FF00L

    fun getBodyLarge(): TextStyle = TextStyle(fontSize = 16.sp)

    fun getSmall(): Any = ShapeDefaults.Small

    fun getIgnored(): String = "ignored"
  }
}
