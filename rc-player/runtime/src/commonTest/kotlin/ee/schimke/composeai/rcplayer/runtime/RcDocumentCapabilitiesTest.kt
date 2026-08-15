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
import ee.schimke.composeai.rcplayer.protocol.RcTheme
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
    assertFalse(caps.supportsUiMode)
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
    // A palette swap is not a light/dark request; nothing here selects between captured colours.
    assertFalse(caps.supportsUiMode)
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
  fun `ColorTheme carries uiMode but not a palette override`() {
    // No published catalog emits `ColorTheme` yet — this fixture is what keeps the branch honest
    // until one does. It must NOT claim palette support: the colours live in the op, so a
    // provider's seeds have no named slot to land on and would return unchanged pixels.
    val caps = roundTrip(doc(colorTheme(group = 7), colorTheme(group = 8)))
    assertEquals(setOf(7, 8), caps.colorThemeGroups)
    assertTrue(caps.supportsUiMode)
    assertFalse(caps.supportsThemeProvider)
  }

  @Test
  fun `theme-gated sections carry uiMode with no ColorTheme at all`() {
    // `Theme` is a running section marker, not a document-wide pin: the player skips operations
    // whose section disagrees with the requested theme. A document bracketing a light run and a
    // dark run therefore answers `uiMode` with no `ColorTheme` anywhere.
    val caps =
      roundTrip(
        doc(
          RcTheme(RcTheme.LIGHT),
          RcTextData(1, "light copy"),
          RcTheme(RcTheme.DARK),
          RcTextData(2, "dark copy"),
        )
      )
    assertEquals(setOf(RcTheme.LIGHT, RcTheme.DARK), caps.themeGatedSections)
    assertTrue(caps.supportsUiMode)
    assertTrue(caps.colorThemeGroups.isEmpty())
  }

  @Test
  fun `a single specific section still gates content`() {
    val caps = roundTrip(doc(RcTextData(1, "always"), RcTheme(RcTheme.DARK), RcTextData(2, "dark")))
    assertEquals(setOf(RcTheme.DARK), caps.themeGatedSections)
    assertTrue(caps.supportsUiMode)
  }

  @Test
  fun `only unspecified is a wildcard - a system section gates content`() {
    // `isThemeVisible` treats ONLY UNSPECIFIED as the wildcard on either side, so a SYSTEM-tagged
    // operation is filtered out under a requested LIGHT or DARK — it gates just like they do.
    val unspecified = roundTrip(doc(RcTheme(RcTheme.UNSPECIFIED), RcTextData(1, "copy")))
    assertTrue(unspecified.themeGatedSections.isEmpty())
    assertFalse(unspecified.supportsUiMode)

    val system = roundTrip(doc(RcTheme(RcTheme.SYSTEM), RcTextData(1, "copy")))
    assertEquals(setOf(RcTheme.SYSTEM), system.themeGatedSections)
    assertTrue(system.supportsUiMode)
  }

  @Test
  fun `a marker does not leak past the end of its container`() {
    // `drawOperations` declares `currentTheme` per invocation and recurses per container, so a
    // trailing marker inside a nested container cannot gate a later sibling of that container.
    val caps =
      roundTrip(
        doc(
          RcBoxLayout(1, 0, 0, 0),
          RcTheme(RcTheme.DARK),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcTextData(2, "sibling after the container"),
        )
      )
    assertTrue(caps.themeGatedSections.isEmpty(), "a trailing nested marker gates nothing")
    assertFalse(caps.supportsUiMode)
  }

  @Test
  fun `a marker is not inherited into a nested container`() {
    // Every container starts over at UNSPECIFIED, so content inside is ungated by the parent's
    // section. The parent's own following content is still gated.
    val caps =
      roundTrip(
        doc(
          RcTheme(RcTheme.DARK),
          RcBoxLayout(1, 0, 0, 0),
          RcTextData(2, "nested, ungated"),
          RcNoArg(RcOpcodes.CONTAINER_END),
        )
      )
    assertTrue(caps.themeGatedSections.isEmpty())
  }

  @Test
  fun `a gated section inside a container is still found`() {
    val caps =
      roundTrip(
        doc(
          RcBoxLayout(1, 0, 0, 0),
          RcTheme(RcTheme.LIGHT),
          RcTextData(2, "light, inside"),
          RcNoArg(RcOpcodes.CONTAINER_END),
        )
      )
    assertEquals(setOf(RcTheme.LIGHT), caps.themeGatedSections)
    assertTrue(caps.supportsUiMode)
  }

  @Test
  fun `an unqualified name resolves against the USER domain`() {
    // `rc.shaderColor` parses to the bare key `shaderColor`, is applied via `setUserLocal*`, and
    // the player prefixes `USER:` — so the captured declaration is `USER:shaderColor`. Matching
    // only the exact string would reject every ordinary `rc.` override.
    val caps = roundTrip(doc(RcNamedVariable(1, RcNamedVariable.COLOR_TYPE, "USER:shaderColor")))
    assertTrue(caps.supportsNamedValue("shaderColor"))
    assertEquals(RcNamedVariable.COLOR_TYPE, caps.namedValueType("shaderColor"))
    assertTrue(caps.supportsNamedValue("USER:shaderColor"))
    assertFalse(caps.supportsNamedValue("somethingElse"))
  }

  @Test
  fun `a trailing top-level marker with no content after it gates nothing`() {
    val caps = roundTrip(doc(RcTextData(1, "copy"), RcTheme(RcTheme.DARK)))
    assertTrue(caps.themeGatedSections.isEmpty())
    assertFalse(caps.supportsUiMode)
  }

  @Test
  fun `a document can carry both axes independently`() {
    val caps =
      roundTrip(
        doc(
          RcNamedVariable(1, RcNamedVariable.COLOR_TYPE, "USER:WearM3.onSurface"),
          RcTheme(RcTheme.DARK),
          RcTextData(2, "dark copy"),
        )
      )
    assertTrue(caps.supportsThemeProvider)
    assertTrue(caps.supportsUiMode)
  }

  @Test
  fun `undecodable bytes report no capability rather than none-needed`() {
    assertNull(RcDocumentCapabilities.of(byteArrayOf(0x7F, 0x7F, 0x7F)))
  }
}
