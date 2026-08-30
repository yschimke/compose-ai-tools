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
  fun `a content axis is reproducible here and selects its native component`() {
    // Not a state: the icon-label variant is the same composable with different content, so this
    // frontend draws exactly what the snapshot does.
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
  fun `a harness-driven variant keeps its server snapshot`() {
    // #4821. `button-filled-pressed` / `-focused` compose a plain Button — since #3672 the state is
    // supplied by `@FocusedPreview`, which walks real focus and dispatches a real pointer press.
    // Nothing here can do that, so composing them natively drew an ordinary unpressed, unfocused
    // button under the label "Pressed" / "Focused": the wrong picture, presented as the right one.
    // The lane must decline and let the snapshot serve it.
    assertNull(nativeCatalogTarget("compose-m3", "button-filled__ideal__pressed__light"))
    assertNull(nativeCatalogTarget("compose-m3", "button-filled__ideal__keyboard-focus__dark"))
    // Same shape one level down: this fell through to `card-slots` without providing
    // `LocalSlotMode`, so the labelled placeholders the variant exists to show were not drawn.
    assertNull(nativeCatalogTarget("compose-m3", "card-slots__ideal__slot-mode__light"))
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
