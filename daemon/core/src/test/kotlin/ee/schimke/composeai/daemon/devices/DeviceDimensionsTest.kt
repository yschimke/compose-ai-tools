package ee.schimke.composeai.daemon.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDimensionsTest {

  @Test
  fun knownDeviceIdsResolveRepresentativeCatalogEntries() {
    assertEquals(
      DeviceDimensions.DeviceSpec(393, 851, 2.75f),
      DeviceDimensions.resolve("id:pixel_5"),
    )
    assertEquals(
      DeviceDimensions.DeviceSpec(841, 701, 2.625f),
      DeviceDimensions.resolve("id:pixel_fold"),
    )
    assertEquals(
      DeviceDimensions.DeviceSpec(192, 192, 2.0f, isRound = true),
      DeviceDimensions.resolve("id:wearos_small_round"),
    )
    assertEquals(
      DeviceDimensions.DeviceSpec(960, 540, 2.0f),
      DeviceDimensions.resolve("id:tv_1080p"),
    )
  }

  @Test
  fun unknownDeviceIdFallsThroughToDefault() {
    assertEquals(DeviceDimensions.DEFAULT, DeviceDimensions.resolve("id:typo_phone"))
  }

  @Test
  fun nullDeviceReturnsDefault() {
    assertEquals(DeviceDimensions.DEFAULT, DeviceDimensions.resolve(null))
  }

  @Test
  fun specStringParsesDimensionsAndDpi() {
    assertEquals(
      DeviceDimensions.DeviceSpec(400, 800, 2.0f),
      DeviceDimensions.resolve("spec:width=400dp,height=800dp,dpi=320"),
    )
  }

  @Test
  fun specStringLandscapeOrientationResolvesLandscapeGeometry() {
    assertEquals(
      DeviceDimensions.DeviceSpec(800, 400, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=400dp,height=800dp,orientation=landscape"),
    )
  }

  @Test
  fun specStringPreservesIsRoundParameter() {
    assertEquals(
      DeviceDimensions.DeviceSpec(227, 227, 2.0f, isRound = true),
      DeviceDimensions.resolve("spec:width=227dp,height=227dp,dpi=320,isRound=true"),
    )
  }

  @Test
  fun specStringToleratesIgnoredCutoutParameter() {
    assertEquals(
      DeviceDimensions.DeviceSpec(411, 914, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=411dp,height=914dp,dpi=420,cutout=corner"),
    )
  }

  @Test
  fun specStringPreservesShapeRoundParameter() {
    assertEquals(
      DeviceDimensions.DeviceSpec(227, 227, 2.0f, isRound = true),
      DeviceDimensions.resolve("spec:width=227dp,height=227dp,dpi=320,Shape=ROUND"),
    )
  }

  @Test
  fun specStringWithoutDpiUsesDefaultDensity() {
    assertEquals(
      DeviceDimensions.DeviceSpec(400, 800, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=400,height=800"),
    )
  }

  @Test
  fun unknownWearDeviceUsesWearDefault() {
    assertEquals(DeviceDimensions.DEFAULT_WEAR, DeviceDimensions.resolve("foo_wear_bar"))
  }

  @Test
  fun explicitDimensionsShortCircuitDeviceString() {
    assertEquals(
      DeviceDimensions.DeviceSpec(200, 400, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("id:pixel_5", widthDp = 200, heightDp = 400),
    )
  }

  @Test
  fun knownDeviceIdsContainRepresentativeCategories() {
    val ids = DeviceDimensions.KNOWN_DEVICE_IDS

    assertFalse(ids.isEmpty())
    assertTrue(ids.contains("id:pixel_5"))
    assertTrue(ids.contains("id:wearos_small_round"))
    assertTrue(ids.contains("id:tv_1080p"))
    assertTrue(ids.contains("id:automotive_portrait"))
    assertTrue(ids.contains("id:xr_headset_device"))
  }
}
