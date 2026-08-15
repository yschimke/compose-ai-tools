package ee.schimke.composeai.rcplayer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RcDocumentCapabilitiesTest {

  private fun doc(vararg ops: RcOperation) = RcDocument(RcHeader(RcVersion(1, 0, 0)), ops.toList())

  /**
   * Through the wire, so a fixture can't pass by holding an in-memory shape the codec won't emit.
   */
  private fun roundTrip(document: RcDocument): RcDocumentCapabilities =
    requireNotNull(RcDocumentCapabilities.of(RcDocumentCodec.encode(document)))

  private fun colorTheme(group: Int) =
    RcColorTheme(
      outId = 42,
      colorGroupId = group,
      lightModeIndex = 0,
      darkModeIndex = 1,
      lightModeFallback = 0xFFFFFFFF.toInt(),
      darkModeFallback = 0xFF000000.toInt(),
    )

  @Test
  fun `document with no state supports nothing`() {
    val caps = roundTrip(doc(RcTextData(1, "10:08")))
    assertTrue(caps.namedValues.isEmpty())
    assertFalse(caps.supportsThemeOverride)
    assertFalse(caps.supportsUiMode)
    assertNull(caps.declaredTheme)
  }

  @Test
  fun `colour-typed named state carries a theme override`() {
    // The shape every themeable remote-m3 document has: `USER:WearM3.<role>` colour slots.
    val caps =
      roundTrip(
        doc(
          RcNamedVariable(1, RcNamedVariable.COLOR_TYPE, "USER:WearM3.surfaceContainer"),
          RcNamedVariable(2, RcNamedVariable.COLOR_TYPE, "USER:WearM3.onSurface"),
        )
      )
    assertEquals(
      setOf("USER:WearM3.surfaceContainer", "USER:WearM3.onSurface"),
      caps.colorNamedValues,
    )
    assertTrue(caps.supportsThemeOverride)
    // A palette swap is not a light/dark request; nothing here selects between captured colours.
    assertFalse(caps.supportsUiMode)
  }

  @Test
  fun `non-colour named state does not carry a theme override`() {
    // The shape every homeassistant-remotecompose document has: entity state, no colour slots.
    val caps =
      roundTrip(
        doc(
          RcNamedVariable(1, RcNamedVariable.INT_TYPE, "USER:light.kitchen.is_on"),
          RcNamedVariable(2, RcNamedVariable.STRING_TYPE, "USER:sensor.living_room_temp.state"),
        )
      )
    assertFalse(caps.supportsThemeOverride)
    assertTrue(caps.supportsNamedValue("USER:light.kitchen.is_on"))
    assertEquals(RcNamedVariable.INT_TYPE, caps.namedValueType("USER:light.kitchen.is_on"))
    // Declared but not drivable today — string seeds don't reach the alpha player's StateUpdater.
    assertEquals(
      RcNamedVariable.STRING_TYPE,
      caps.namedValueType("USER:sensor.living_room_temp.state"),
    )
    assertFalse(caps.supportsNamedValue("USER:light.hallway.is_on"))
  }

  @Test
  fun `ColorTheme operations carry both a theme override and uiMode`() {
    // The second colour-theming mechanism. No published catalog emits this yet — the fixture is
    // what keeps the branch honest until one does, so the check can't silently regress to
    // named-state-only and go unnoticed.
    val caps = roundTrip(doc(colorTheme(group = 7), colorTheme(group = 8)))
    assertEquals(setOf(7, 8), caps.colorThemeGroups)
    assertTrue(caps.supportsThemeOverride)
    assertTrue(caps.supportsUiMode)
  }

  @Test
  fun `a document pinning its own theme does not respond to uiMode`() {
    val caps = roundTrip(doc(colorTheme(group = 7), RcTheme(RcTheme.DARK)))
    assertEquals(RcTheme.DARK, caps.declaredTheme)
    // It still has colour state to override outright; it just won't follow a light/dark request.
    assertTrue(caps.supportsThemeOverride)
    assertFalse(caps.supportsUiMode)
  }

  @Test
  fun `an unspecified theme leaves the choice to the player`() {
    for (theme in listOf(RcTheme.SYSTEM, RcTheme.UNSPECIFIED)) {
      val caps = roundTrip(doc(colorTheme(group = 7), RcTheme(theme)))
      assertTrue(caps.supportsUiMode, "theme=$theme should defer to the player")
    }
  }

  @Test
  fun `ColorTheme without named state still reports theme support`() {
    // The regression this guards: a check written only against remote-m3 would look at
    // `colorNamedValues`, find it empty, and report a themeable document as un-themeable.
    val caps = roundTrip(doc(colorTheme(group = 1)))
    assertTrue(caps.colorNamedValues.isEmpty())
    assertTrue(caps.supportsThemeOverride)
  }

  @Test
  fun `undecodable bytes report no capability rather than none-needed`() {
    assertNull(RcDocumentCapabilities.of(byteArrayOf(0x7F, 0x7F, 0x7F)))
  }
}
