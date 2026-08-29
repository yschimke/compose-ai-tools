package ee.schimke.composeai.servewasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeCatalogTest {
  @Test
  fun `compose m3 route resolves to the in-process component`() {
    assertEquals(
      NativeCatalogTarget(
        componentId = "button-filled",
        dark = true,
        fontScale = 2f,
        rtl = true,
        knobSeeds = mapOf("enabled" to "false"),
      ),
      nativeCatalogTarget(
        system = "compose-m3",
        previewId = "button-filled__ideal__disabled__dark__fontscale-2.0__direction-rtl",
        knobSeeds = mapOf("enabled" to "false"),
      ),
    )
  }

  @Test
  fun `interaction captures select their native component`() {
    assertEquals(
      "button-filled-pressed",
      nativeCatalogTarget("compose-m3", "button-filled__ideal__pressed__light")?.componentId,
    )
    assertEquals(
      "button-filled-focused",
      nativeCatalogTarget("compose-m3", "button-filled__ideal__keyboard-focus__dark")?.componentId,
    )
    assertEquals(
      "button-filled-icon-label",
      nativeCatalogTarget(
          "compose-m3",
          "button-filled__ideal__default__light__content-icon-label",
        )
        ?.componentId,
    )
  }

  @Test
  fun `default mixed feed recognizes an injected catalog preview by its compiled route`() {
    assertEquals(
      "checkbox-checked",
      nativeCatalogTarget(null, "checkbox-checked__ideal__default__light")?.componentId,
    )
  }

  @Test
  fun `explicit unsupported catalogs and unknown components keep their server fallback`() {
    assertNull(nativeCatalogTarget("wear-m3", "button-filled__ideal__default__light"))
    assertNull(nativeCatalogTarget("compose-m3", "template-appscaffold__compact__light"))
    assertNull(nativeCatalogTarget(null, "com.example.ProfileScreenPreview"))
  }
}
