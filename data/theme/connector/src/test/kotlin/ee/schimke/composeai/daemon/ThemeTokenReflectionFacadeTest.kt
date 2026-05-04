package ee.schimke.composeai.daemon

import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeTokenReflectionFacadeTest {
  @Test
  fun readsColorPropertiesThroughFacade() {
    val source = ReflectedThemeTokens()

    val tokens = ThemeTokenReflectionFacade.readColorProperties(source)

    assertEquals(Color.Red.value.toLong(), tokens["primary"])
    assertEquals(0xFF00FF00L, tokens["secondary"])
    assertFalse(tokens.containsKey("ignored"))
  }

  @Test
  fun readsTextStylePropertiesThroughFacade() {
    val source = ReflectedThemeTokens()

    val tokens = ThemeTokenReflectionFacade.readTextStyleProperties(source)

    assertEquals(16.sp, tokens.getValue("bodyLarge").fontSize)
    assertFalse(tokens.containsKey("primary"))
  }

  @Test
  fun readsShapeLikePropertiesThroughFacade() {
    val source = ReflectedThemeTokens()

    val tokens = ThemeTokenReflectionFacade.readShapeLikeProperties(source)

    assertEquals(ShapeDefaults.Small, tokens["small"])
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
