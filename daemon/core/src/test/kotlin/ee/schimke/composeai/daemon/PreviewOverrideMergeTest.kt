package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOverrideMergeTest {
  @Test
  fun `null overrides preserve base spec`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 100,
        heightPx = 200,
        density = 2.0f,
        device = "id:pixel_5",
        localeTag = "en-US",
        fontScale = 1.2f,
        uiMode = UiMode.DARK,
        orientation = Orientation.PORTRAIT,
        inspectionMode = false,
      )

    val merged = mergePreviewOverrides(base, null)

    assertEquals(100, merged.widthPx)
    assertEquals(200, merged.heightPx)
    assertEquals(2.0f, merged.density)
    assertEquals("id:pixel_5", merged.device)
    assertEquals("en-US", merged.localeTag)
    assertEquals(1.2f, merged.fontScale)
    assertEquals(UiMode.DARK, merged.uiMode)
    assertEquals(Orientation.PORTRAIT, merged.orientation)
    assertEquals(false, merged.inspectionMode)
  }

  @Test
  fun `device override resolves dimensions and explicit fields win`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 100,
        heightPx = 200,
        density = 1.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val merged =
      mergePreviewOverrides(
        base,
        PreviewOverrides(
          device = "spec:width=50dp,height=80dp,dpi=320",
          widthPx = 123,
          localeTag = "de",
          fontScale = 1.5f,
          uiMode = UiMode.LIGHT,
          orientation = Orientation.LANDSCAPE,
          inspectionMode = true,
        ),
      )

    assertEquals(123, merged.widthPx)
    assertEquals(160, merged.heightPx)
    assertEquals(2.0f, merged.density)
    assertEquals("spec:width=50dp,height=80dp,dpi=320", merged.device)
    assertEquals("de", merged.localeTag)
    assertEquals(1.5f, merged.fontScale)
    assertEquals(UiMode.LIGHT, merged.uiMode)
    assertEquals(Orientation.LANDSCAPE, merged.orientation)
    assertEquals(true, merged.inspectionMode)
  }
}
