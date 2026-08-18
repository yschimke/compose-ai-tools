package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `setNamedValue` had no inverse, which is why a host holding named values in a map had no way to
 * express "stop overriding this". These pin the pair that fixes it — and pin that "the document's
 * recorded value" means the value the *document* carried, captured before any override, not
 * whatever happens to be there when the removal arrives.
 */
class RcNamedValueLifecycleTest {

  private fun document() =
    RcDocument(
      RcHeader(RcVersion(0, 1, 0)),
      listOf(
        RcFloatConstant(9, RcFloatWord.literal(21.5f)),
        RcNamedVariable(9, RcNamedVariable.FLOAT_TYPE, "USER:temperature"),
        RcTextData(10, "Tuesday"),
        RcNamedVariable(10, RcNamedVariable.STRING_TYPE, "USER:day"),
        RcColorConstant(11, 0xFF102040.toInt()),
        RcNamedVariable(11, RcNamedVariable.COLOR_TYPE, "USER:accent"),
      ),
    )

  @Test
  fun clearingRestoresWhatTheDocumentRecorded() {
    val state = RcPlayerState(document())
    state.setNamedValue("USER:temperature", RcNamedValue.FloatValue(-4f))
    assertEquals(RcNamedValue.FloatValue(-4f), state.namedValue("USER:temperature"))

    state.clearNamedValue("USER:temperature")

    assertEquals(RcNamedValue.FloatValue(21.5f), state.namedValue("USER:temperature"))
  }

  @Test
  fun clearingRestoresTheDocumentsValueEvenWhenTheHostSeededADifferentOneAtConstruction() {
    // The seeded value is the *host's*, not the document's — so removing it has to fall back past
    // it. Capturing the recorded values after seeding would make this return the seed forever.
    val state =
      RcPlayerState(document(), mapOf("USER:temperature" to RcNamedValue.FloatValue(100f)))
    assertEquals(RcNamedValue.FloatValue(100f), state.namedValue("USER:temperature"))

    state.clearNamedValue("USER:temperature")

    assertEquals(RcNamedValue.FloatValue(21.5f), state.namedValue("USER:temperature"))
  }

  @Test
  fun clearingATextRemovesTheOverrideRatherThanWritingTheOldStringBack() {
    val state = RcPlayerState(document())
    state.setNamedValue("USER:day", RcNamedValue.Text("Wednesday"))
    state.beginFrame()
    assertEquals("Wednesday", state.text(10))

    state.clearNamedValue("USER:day")
    // The next frame is where it would come back: `beginFrame` rebuilds `texts` from the document
    // and re-applies every override on top, so an override written back as a "restore" would
    // reinstate itself here and the document could never change the string again.
    state.beginFrame()

    assertEquals("Tuesday", state.text(10))
  }

  @Test
  fun clearingAColourRestoresTheRecordedArgb() {
    val state = RcPlayerState(document())
    state.setNamedValue("USER:accent", RcNamedValue.Color(0xFF00FF00.toInt()))
    assertEquals(0xFF00FF00.toInt(), state.color(11))

    state.clearNamedValue("USER:accent")

    assertEquals(0xFF102040.toInt(), state.color(11))
  }

  @Test
  fun clearingIsIdempotentAndClearingAnUnknownNameStillFailsLoudly() {
    val state = RcPlayerState(document())
    state.clearNamedValue("USER:temperature")
    state.clearNamedValue("USER:temperature")
    assertEquals(RcNamedValue.FloatValue(21.5f), state.namedValue("USER:temperature"))

    assertFailsWith<IllegalArgumentException> { state.clearNamedValue("USER:nothing") }
    assertFailsWith<IllegalArgumentException> { state.namedValue("USER:nothing") }
  }
}
