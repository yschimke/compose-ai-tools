package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
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
    assertFalse(caps.supportsThemeProvider)
    assertTrue(caps.colorThemeGroups.isEmpty())
  }

  @Test
  fun `colour-typed named state carries a palette override`() {
    // The shape every themeable remote-m3 document has: `USER:WearM3.<role>` colour slots, which is
    // what `ServeThemeReplay` seeds a provider's colours into.
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
    assertTrue(caps.supportsThemeProvider)
  }

  @Test
  fun `non-colour named state does not carry a palette override`() {
    // The shape every homeassistant-remotecompose document has: entity state, no colour slots.
    val caps =
      roundTrip(
        doc(
          RcNamedVariable(1, RcNamedVariable.INT_TYPE, "USER:light.kitchen.is_on"),
          RcNamedVariable(2, RcNamedVariable.STRING_TYPE, "USER:sensor.living_room_temp.state"),
        )
      )
    assertFalse(caps.supportsThemeProvider)
    assertTrue(caps.declaresNamedValue("USER:light.kitchen.is_on"))
    assertEquals(RcNamedVariable.INT_TYPE, caps.namedValueType("USER:light.kitchen.is_on"))
    // Declared but not drivable today — string seeds don't reach the alpha player's StateUpdater.
    assertEquals(
      RcNamedVariable.STRING_TYPE,
      caps.namedValueType("USER:sensor.living_room_temp.state"),
    )
    assertFalse(caps.declaresNamedValue("USER:light.hallway.is_on"))
  }

  @Test
  fun `ColorTheme is detected but grants no palette override`() {
    // No published catalog emits `ColorTheme` yet — this fixture is what keeps detection honest
    // until one does. It must NOT claim palette support: the colours live in the op, so a
    // provider's seeds have no named slot to land on and would return unchanged pixels.
    val caps = roundTrip(doc(colorTheme(group = 7), colorTheme(group = 8)))
    assertEquals(setOf(7, 8), caps.colorThemeGroups)
    assertFalse(caps.supportsThemeProvider)
  }

  @Test
  fun `declarations nested in a container are still found`() {
    val caps =
      roundTrip(
        doc(
          RcBoxLayout(1, 0, 0, 0),
          RcNamedVariable(2, RcNamedVariable.COLOR_TYPE, "USER:WearM3.primary"),
          colorTheme(group = 3),
          RcNoArg(RcOpcodes.CONTAINER_END),
        )
      )
    assertEquals(setOf("USER:WearM3.primary"), caps.colorNamedValues)
    assertEquals(setOf(3), caps.colorThemeGroups)
    assertTrue(caps.supportsThemeProvider)
  }

  @Test
  fun `an unqualified name resolves against the USER domain`() {
    // `rc.shaderColor` parses to the bare key `shaderColor`, is applied via `setUserLocal*`, and
    // the player prefixes `USER:` — so the captured declaration is `USER:shaderColor`. Matching
    // only the exact string would reject every ordinary `rc.` override.
    val caps = roundTrip(doc(RcNamedVariable(1, RcNamedVariable.COLOR_TYPE, "USER:shaderColor")))
    assertTrue(caps.declaresNamedValue("shaderColor"))
    assertEquals(RcNamedVariable.COLOR_TYPE, caps.namedValueType("shaderColor"))
    assertTrue(caps.declaresNamedValue("USER:shaderColor"))
    assertFalse(caps.declaresNamedValue("somethingElse"))
  }

  @Test
  fun `a bare declaration is not reachable by a bare request`() {
    // A request always arrives qualified, so a document declaring the raw key has nothing the seed
    // can reach — and when both exist, the qualified one is the answer.
    val bareOnly = roundTrip(doc(RcNamedVariable(1, RcNamedVariable.FLOAT_TYPE, "shaderColor")))
    assertFalse(bareOnly.declaresNamedValue("shaderColor"))

    val both =
      roundTrip(
        doc(
          RcNamedVariable(1, RcNamedVariable.FLOAT_TYPE, "shaderColor"),
          RcNamedVariable(2, RcNamedVariable.COLOR_TYPE, "USER:shaderColor"),
        )
      )
    assertEquals(RcNamedVariable.COLOR_TYPE, both.namedValueType("shaderColor"))
  }

  @Test
  fun `declaration is not drivability`() {
    // `RcPlayerState.setNamedValue` throws for the image type; the class reports the declaration
    // and its type and leaves that judgement to the caller, so the name says "declares".
    val caps = roundTrip(doc(RcNamedVariable(1, RcNamedVariable.IMAGE_TYPE, "USER:photo")))
    assertTrue(caps.declaresNamedValue("photo"))
    assertEquals(RcNamedVariable.IMAGE_TYPE, caps.namedValueType("photo"))
  }

  @Test
  fun `undecodable bytes report no capability rather than none-needed`() {
    assertNull(RcDocumentCapabilities.of(byteArrayOf(0x7F, 0x7F, 0x7F)))
  }
}
