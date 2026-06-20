package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFrameConfigTest {

  @Test
  fun blankOrNullDeviceDisablesFeature() {
    assertNull(DeviceFrameConfig.parse(null))
    assertNull(DeviceFrameConfig.parse(""))
    assertNull(DeviceFrameConfig.parse("   "))
  }

  @Test
  fun autoSelection() {
    val settings = DeviceFrameConfig.parse("auto")!!
    assertEquals(DeviceFrameConfig.Selection.Auto, settings.selection)
    assertEquals(DeviceFrameConfig.DEFAULT_BASE_URL, settings.baseUrl)
    assertTrue(settings.includeShadow)
    assertTrue(settings.includeGlare)
  }

  @Test
  fun autoIsCaseInsensitive() {
    assertEquals(DeviceFrameConfig.Selection.Auto, DeviceFrameConfig.parse("AUTO")!!.selection)
  }

  @Test
  fun forcedArtId() {
    assertEquals(
      DeviceFrameConfig.Selection.Forced("wear_round"),
      DeviceFrameConfig.parse("wear_round")!!.selection,
    )
  }

  @Test
  fun baseUrlAndCacheDirOverrides() {
    val settings =
      DeviceFrameConfig.parse("auto", baseUrl = "https://mirror/art", cacheDir = "/tmp/c")
    assertEquals("https://mirror/art", settings!!.baseUrl)
    assertEquals("/tmp/c", settings.cacheDir)
  }

  @Test
  fun blankOverridesFallBackToDefaults() {
    val settings = DeviceFrameConfig.parse("auto", baseUrl = "  ", cacheDir = "  ")
    assertEquals(DeviceFrameConfig.DEFAULT_BASE_URL, settings!!.baseUrl)
    assertNull(settings.cacheDir)
  }

  @Test
  fun shadowAndGlareToggles() {
    val settings = DeviceFrameConfig.parse("pixel_5", shadow = "false", glare = "false")!!
    assertFalse(settings.includeShadow)
    assertFalse(settings.includeGlare)
  }
}
