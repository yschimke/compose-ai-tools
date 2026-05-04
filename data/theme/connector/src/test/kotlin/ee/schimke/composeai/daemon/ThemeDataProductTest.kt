package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDataProductTest {
  @Test
  fun `material theme override extension declares around composable hook`() {
    val extension =
      Material3ThemeOverrideExtension(
        Material3ThemeOverrides(colorScheme = mapOf("primary" to "#FF336699"))
      )
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("compose/material3ThemeOverride"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.UserEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `material font weight override accepts compose range`() {
    assertEquals(FontWeight(1), material3FontWeightOverride(1))
    assertEquals(FontWeight(700), material3FontWeightOverride(700))
    assertEquals(FontWeight(1000), material3FontWeightOverride(1000))
  }

  @Test
  fun `material font weight override ignores invalid values`() {
    assertNull(material3FontWeightOverride(null))
    assertNull(material3FontWeightOverride(0))
    assertNull(material3FontWeightOverride(1001))
  }
}
