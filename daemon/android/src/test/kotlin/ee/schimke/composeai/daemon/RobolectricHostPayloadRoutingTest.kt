package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
  fun `reshape rotates the discovery-time frame for a device-less orientation request`() {
    // The production Android bundle daemon never mounts a `PreviewManifestRouter` — it reshapes
    // here. A device-less `orientation` arrives with no dimensions for
    // `JsonRpcServer.encodeRenderPayload` to rotate, so this is the only lane that can turn the
    // preview's own frame. Missing it captured a landscape bitmap while `applyPreviewQualifiers`
    // derived `port` from the same spec (#3552 review).
    val host = host(widthPx = 800, heightPx = 400)

    val spec =
      RenderSpec.parseFromPayloadOrNull(
        host.reshapeRenderPayload("previewId=p;orientation=portrait")
      )

    assertNotNull(spec)
    assertEquals(400, spec!!.widthPx)
    assertEquals(800, spec.heightPx)
    assertEquals(RenderSpec.SpecOrientation.PORTRAIT, spec.orientation)
  }

  @Test
  fun `reshape leaves a frame already in the requested orientation alone`() {
    val host = host(widthPx = 400, heightPx = 800)

    val spec =
      RenderSpec.parseFromPayloadOrNull(
        host.reshapeRenderPayload("previewId=p;orientation=portrait")
      )

    assertEquals(400, spec!!.widthPx)
    assertEquals(800, spec.heightPx)
  }

  @Test
  fun `reshape lets explicit pixels outrank the orientation request`() {
    val host = host(widthPx = 800, heightPx = 400)

    val spec =
      RenderSpec.parseFromPayloadOrNull(
        host.reshapeRenderPayload("previewId=p;widthPx=1000;heightPx=200;orientation=portrait")
      )

    assertEquals(1000, spec!!.widthPx)
    assertEquals(200, spec.heightPx)
  }

  @Test
  fun `reshape trades the wrap axis with a rotated frame`() {
    // wrapWidth/wrapHeight name an axis, so rotating the frame without trading them measures and
    // crops the axis that is no longer the free one.
    val host = host(widthPx = 800, heightPx = 400, wrapHeight = true)

    val routed = host.reshapeRenderPayload("previewId=p;orientation=portrait")

    assertTrue("rotated frame should now wrap width: $routed", routed.contains("wrapWidth=true"))
    assertFalse("...and no longer wrap height: $routed", routed.contains("wrapHeight=true"))
  }

  @Test
  fun `held-session overrides trade the wrap axis with a rotated frame`() {
    // `applyOverrides` is the interactive / recording lane. It copies the merged dimensions onto
    // the base spec, so without consuming `merged.rotated` the wrap flags stayed on the old axis
    // and the held-session measure-and-crop pass sized the wrong one (#3552 review).
    val host = host(widthPx = 800, heightPx = 400, wrapHeight = true)

    val spec =
      host.applyOverridesForTest(
        RenderSpec(
          previewId = "p",
          className = "com.example.PlainPreviewKt",
          functionName = "PlainPreview",
          widthPx = 800,
          heightPx = 400,
          wrapHeight = true,
        ),
        PreviewOverrides(orientation = Orientation.PORTRAIT),
      )

    assertEquals(400, spec.widthPx)
    assertEquals(800, spec.heightPx)
    assertTrue("rotated frame should now wrap width", spec.wrapWidth)
    assertFalse("...and no longer wrap height", spec.wrapHeight)
  }

  private fun host(
    widthPx: Int,
    heightPx: Int,
    wrapWidth: Boolean = false,
    wrapHeight: Boolean = false,
  ) =
    RobolectricHost(
      previewSpecResolver = {
        RenderSpec(
          previewId = it,
          className = "com.example.PlainPreviewKt",
          functionName = "PlainPreview",
          widthPx = widthPx,
          heightPx = heightPx,
          wrapWidth = wrapWidth,
          wrapHeight = wrapHeight,
        )
      }
    )

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
