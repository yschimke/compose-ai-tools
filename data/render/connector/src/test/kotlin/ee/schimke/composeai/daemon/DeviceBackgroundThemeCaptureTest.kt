package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.PreviewContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceBackgroundThemeCaptureTest {
  @Test
  fun readsBackgroundFromPreviewContextThemePayload() {
    val colors = mapOf("background" to "#FFFFFFFF")
    val context = themeContext(colors)

    val result = DeviceBackgroundThemeCapture.from(context)?.background()

    assertEquals("#FFFFFFFF", result?.color)
    assertEquals("material3.background", result?.source)
  }

  @Test
  fun returnsNullForUnknownPayloadShape() {
    val context =
      PreviewContext.Builder(
          previewId = "preview",
          backend = null,
          renderMode = null,
          outputBaseName = null,
        )
        .putInspectionValue("compose.material3.themePayload", Any())
        .build()

    assertNull(DeviceBackgroundThemeCapture.from(context))
  }

  private fun themeContext(colors: Map<String, String>): PreviewContext =
    PreviewContext.Builder(
        previewId = "preview",
        backend = null,
        renderMode = null,
        outputBaseName = null,
      )
      .putInspectionValue(
        "compose.material3.themePayload",
        ThemePayloadLike(ResolvedTokensLike(colors)),
      )
      .build()

  @Suppress("unused")
  private class ThemePayloadLike(private val resolvedTokens: ResolvedTokensLike) {
    fun getResolvedTokens(): ResolvedTokensLike = resolvedTokens
  }

  @Suppress("unused")
  private class ResolvedTokensLike(private val colorScheme: Map<String, String>) {
    fun getColorScheme(): Map<String, String> = colorScheme
  }
}
