package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Regression guards for manifest-backed `previewId=…` payload reshaping. */
class RobolectricHostPayloadRoutingTest {

  @Test
  fun `reshape forwards preview parameter provider from resolved manifest spec`() {
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.TimePlayButtonKt",
            functionName = "TimePlayButtonPreview",
            previewParameterProviderClassName = "com.example.ThemePreviewParameterProvider",
            previewParameterLimit = 3,
          )
        }
      )

    val routed = host.reshapeRenderPayload("previewId=time-play-button;uiMode=dark")
    val spec = RenderSpec.parseFromPayloadOrNull(routed)

    assertNotNull("reshaped payload must remain a parseable RenderSpec: $routed", spec)
    assertEquals(
      "com.example.ThemePreviewParameterProvider",
      spec!!.previewParameterProviderClassName,
    )
    assertEquals(3, spec.previewParameterLimit)
    assertEquals(RenderSpec.SpecUiMode.DARK, spec.uiMode)
  }

  @Test
  fun `reshape omits preview parameter tokens for an ordinary preview`() {
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.PlainPreviewKt",
            functionName = "PlainPreview",
          )
        }
      )

    val routed = host.reshapeRenderPayload("previewId=plain")

    assertFalse(routed.contains("previewParameterProvider="))
    assertFalse(routed.contains("previewParameterLimit="))
  }
}
