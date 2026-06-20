package ee.schimke.composeai.data.deviceframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceArtCatalogTest {

  @Test
  fun roundWearDevicesResolveToRoundFrame() {
    for (device in
      listOf(
        "id:wearos_small_round",
        "id:wearos_large_round",
        "spec:width=227dp,height=227dp,isround=true",
        "wear",
      )) {
      assertSame(device, DeviceArtCatalog.WEAR_ROUND, DeviceArtCatalog.forDeviceString(device))
    }
  }

  @Test
  fun squareWearResolvesToSquareFrame() {
    assertSame(DeviceArtCatalog.WEAR_SQUARE, DeviceArtCatalog.forDeviceString("id:wearos_square"))
  }

  @Test
  fun phoneAndSpecDevicesResolveToPhoneFrame() {
    for (device in
      listOf("id:pixel_5", "id:medium_phone", "spec:width=411dp,height=914dp", "id:pixel_9")) {
      assertSame(device, DeviceArtCatalog.PHONE, DeviceArtCatalog.forDeviceString(device))
    }
  }

  @Test
  fun unframedDeviceClassesReturnNull() {
    for (device in
      listOf(
        "id:pixel_tablet",
        "id:tv_1080p",
        "id:automotive_portrait",
        "id:xr_headset_device",
        "id:desktop_large",
      )) {
      assertNull(device, DeviceArtCatalog.forDeviceString(device))
    }
  }

  @Test
  fun nullDeviceReturnsNull() {
    assertNull(DeviceArtCatalog.forDeviceString(null))
  }

  @Test
  fun explicitArtIdLookup() {
    assertSame(DeviceArtCatalog.WEAR_ROUND, DeviceArtCatalog.byArtId("wear_round"))
    assertSame(DeviceArtCatalog.PHONE, DeviceArtCatalog.byArtId("pixel_5"))
    assertNull(DeviceArtCatalog.byArtId("nope"))
  }

  @Test
  fun roundWatchClipIsAFullCircle() {
    // cornerRadius * 2 == screenWidth is the signal the compositor treats as a circular screen.
    assertEquals(
      DeviceArtCatalog.WEAR_ROUND.screenWidth,
      DeviceArtCatalog.WEAR_ROUND.cornerRadius * 2,
    )
  }

  @Test
  fun everyFrameDeclaresBackLayer() {
    assertTrue(DeviceArtCatalog.ALL.all { DeviceArtCatalog.BACK in it.resources })
  }
}
