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
  fun specStringPortraitOrientationRotatesALandscapeSpec() {
    // #3547, one layer earlier than the override lane: `orientation=portrait` used to be dropped
    // here (only `landscape` was handled), so `@PreviewScreenSizes`' own "Tablet" entry —
    // `spec:width=1280dp,height=800dp,dpi=240,orientation=portrait` — rendered landscape, pixel for
    // pixel identical to its "Tablet - Landscape" sibling.
    assertEquals(
      DeviceDimensions.DeviceSpec(800, 1280, 1.5f),
      DeviceDimensions.resolve("spec:width=1280dp,height=800dp,dpi=240,orientation=portrait"),
    )
  }

  @Test
  fun specStringOrientationIsIdempotent() {
    // "Make it look like this", not "flip it": a request the spec already satisfies is a no-op, in
    // both directions, so re-resolving the same string can never oscillate.
    assertEquals(
      DeviceDimensions.DeviceSpec(400, 800, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=400dp,height=800dp,orientation=portrait"),
    )
    assertEquals(
      DeviceDimensions.DeviceSpec(800, 400, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=800dp,height=400dp,orientation=landscape"),
    )
  }

  @Test
  fun specStringSquareFrameAndUnknownOrientationNeverSwap() {
    assertEquals(
      DeviceDimensions.DeviceSpec(227, 227, 2.0f, isRound = true),
      DeviceDimensions.resolve(
        "spec:width=227dp,height=227dp,dpi=320,isRound=true,orientation=portrait"
      ),
    )
    assertEquals(
      DeviceDimensions.DeviceSpec(400, 800, DeviceDimensions.DEFAULT_DENSITY),
      DeviceDimensions.resolve("spec:width=400dp,height=800dp,orientation=sideways"),
    )
  }

  @Test
  fun specStringParentInheritsCatalogGeometry() {
    // Studio's device picker writes `spec:parent=…` the moment you pick a device and change
    // anything about it. Unresolved, the picked device vanished into the 400×800 default.
    assertEquals(
      DeviceDimensions.resolve("id:pixel_tablet"),
      DeviceDimensions.resolve("spec:parent=pixel_tablet"),
    )
    assertEquals(
      DeviceDimensions.resolve("id:wearos_small_round"),
      DeviceDimensions.resolve("spec:parent=id:wearos_small_round"),
    )
  }

  @Test
  fun specStringParentRotatesAndAcceptsOverrides() {
    // The reported gesture — pick a tablet, then pick Portrait — as Studio spells it in an
    // annotation. Pixel Tablet is 1280×800dp @2.0×.
    assertEquals(
      DeviceDimensions.DeviceSpec(800, 1280, 2.0f),
      DeviceDimensions.resolve("spec:parent=pixel_tablet,orientation=portrait"),
    )
    // Restated terms outrank the parent; unstated ones still come from it.
    assertEquals(
      DeviceDimensions.DeviceSpec(1280, 600, 4.0f),
      DeviceDimensions.resolve("spec:parent=pixel_tablet,height=600dp,dpi=640"),
    )
  }

  @Test
  fun specStringUnknownParentFallsBackLikeADeviceId() {
    assertEquals(DeviceDimensions.DEFAULT, DeviceDimensions.resolve("spec:parent=typo_phone"))
    assertEquals(
      DeviceDimensions.DEFAULT_WEAR,
      DeviceDimensions.resolve("spec:parent=some_wear_thing"),
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
