package ee.schimke.composeai.daemon

import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.ComposableExtractorHook
import ee.schimke.composeai.data.render.extensions.compose.hasComposableExtractorHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialThemeTokenCaptureTest {
  @Test
  fun themeCaptureExtensionDeclaresComposableExtractorHook() {
    val extension = ThemeCaptureExtension()
    val hook: ComposableExtractorHook = extension

    assertEquals(DataExtensionId(ThemeDataProductRegistry.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ComposableExtractor), extension.hooks)
    assertEquals(DataExtensionPhase.Capture, extension.constraints.phase)
    assertTrue(extension.hasComposableExtractorHook)
    assertEquals(extension, hook)
  }

  @Test
  fun capturesColorSchemeFromInspectionTokenObject() {
    val source = ReflectedThemeTokens()

    val capture =
      MaterialThemeTokenCapture.fromInspectionSources(
        colorSource = source,
        typographySource = null,
        shapesSource = null,
        fallbackTypography = null,
        fallbackShapes = null,
      )
    val tokens = requireNotNull(capture).colorScheme

    assertEquals(Color.Red.hexArgb(), tokens["primary"])
    assertEquals(Color(0xFF00FF00u).hexArgb(), tokens["secondary"])
    assertFalse(tokens.containsKey("ignored"))
  }

  @Test
  fun capturesTypographyFromInspectionTokenObject() {
    val source = ReflectedThemeTokens()

    val capture =
      MaterialThemeTokenCapture.fromInspectionSources(
        colorSource = source,
        typographySource = source,
        shapesSource = null,
        fallbackTypography = null,
        fallbackShapes = null,
      )
    val tokens = requireNotNull(capture).typography

    assertEquals(16f, tokens.getValue("bodyLarge").fontSize)
    assertFalse(tokens.containsKey("primary"))
  }

  @Test
  fun capturesShapesFromInspectionTokenObject() {
    val source = ReflectedThemeTokens()

    val capture =
      MaterialThemeTokenCapture.fromInspectionSources(
        colorSource = source,
        typographySource = null,
        shapesSource = source,
        fallbackTypography = null,
        fallbackShapes = null,
      )
    val tokens = requireNotNull(capture).shapes

    assertEquals(ShapeDefaults.Small.toString(), tokens["small"])
    assertFalse(tokens.containsKey("bodyLarge"))
  }

  @Test
  fun returnsNullWhenInspectionTokenObjectHasNoColorScheme() {
    val capture =
      MaterialThemeTokenCapture.fromInspectionSources(
        colorSource = Any(),
        typographySource = ReflectedThemeTokens(),
        shapesSource = ReflectedThemeTokens(),
        fallbackTypography = null,
        fallbackShapes = null,
      )

    assertEquals(null, capture)
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
