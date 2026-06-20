package ee.schimke.composeai.data.deviceframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelCatalogTest {

  @Test
  fun lookupById() {
    assertSame(DeviceModelCatalog.IPHONE, DeviceModelCatalog.byId("iphone"))
    assertNull(DeviceModelCatalog.byId("nope"))
  }

  @Test
  fun idsAreUnique() {
    val ids = DeviceModelCatalog.ALL.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun everyModelIsAnHttpsGlbUrl() {
    for (spec in DeviceModelCatalog.ALL) {
      assertTrue(spec.url, spec.url.startsWith("https://"))
      assertTrue(spec.url, spec.url.endsWith(".glb"))
    }
  }

  @Test
  fun everyModelCarriesAttributionAndLicense() {
    for (spec in DeviceModelCatalog.ALL) {
      assertTrue(spec.id, spec.attribution.isNotBlank())
      assertTrue(spec.id, spec.license.isNotBlank())
    }
  }

  @Test
  fun iphoneAttributionCreditsAuthorAndLicense() {
    // CC BY-NC-SA requires crediting the author and naming the licence on any rendered output.
    assertEquals("CC-BY-NC-SA-4.0", DeviceModelCatalog.IPHONE.license)
    assertTrue(DeviceModelCatalog.IPHONE.attribution.contains("OneSteven"))
    assertTrue(DeviceModelCatalog.IPHONE.attribution.contains("CC BY-NC-SA 4.0"))
  }
}
