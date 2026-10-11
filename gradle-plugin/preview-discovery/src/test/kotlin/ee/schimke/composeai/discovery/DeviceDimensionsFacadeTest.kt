@file:Suppress("DEPRECATION")

package ee.schimke.composeai.discovery

import ee.schimke.composeai.daemon.devices.DeviceDimensions as SharedDeviceDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

/** The deprecated facade must keep answering exactly what its replacements answer. */
class DeviceDimensionsFacadeTest {
  @Test
  fun `resolve forwards to the shared catalog`() {
    for (device in
      listOf(
        null,
        "id:pixel_5",
        "id:wearos_small_round",
        "spec:width=300dp,height=200dp,dpi=320",
      )) {
      val shared = SharedDeviceDimensions.resolve(device)
      assertEquals(
        DeviceDimensions.DeviceSpec(
          shared.widthDp,
          shared.heightDp,
          shared.density,
          shared.isRound,
        ),
        DeviceDimensions.resolve(device),
      )
    }
  }

  @Test
  fun `resolveForRender forwards to PreviewSizing`() {
    val sizing = PreviewSizing.resolveForRender(null, 120, null, showSystemUi = false)
    assertEquals(
      DeviceDimensions.SizeSpec(
        sizing.widthDp,
        sizing.heightDp,
        sizing.wrapWidth,
        sizing.wrapHeight,
        sizing.density,
      ),
      DeviceDimensions.resolveForRender(null, 120, null, showSystemUi = false),
    )
  }

  @Test
  fun `constants keep their values`() {
    assertEquals(PreviewSizing.SANDBOX_WIDTH_DP, DeviceDimensions.SANDBOX_DP)
    assertEquals(SharedDeviceDimensions.DEFAULT_DENSITY, DeviceDimensions.DEFAULT_DENSITY)
    assertEquals(SharedDeviceDimensions.DEFAULT_WEAR.widthDp, DeviceDimensions.DEFAULT_WEAR.widthDp)
  }
}
