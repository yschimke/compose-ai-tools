package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewKnobSeedsTest {

  @Test
  fun everyNumericAndTextKindFlattensToItsVerbatimText() {
    assertEquals("hi", PreviewKnobSeeds.text(PreviewOverrideValue.StringValue("hi")))
    assertEquals("3", PreviewKnobSeeds.text(PreviewOverrideValue.IntValue(3)))
    assertEquals("true", PreviewKnobSeeds.text(PreviewOverrideValue.BooleanValue(true)))
    assertEquals("1.5", PreviewKnobSeeds.text(PreviewOverrideValue.FloatValue(1.5f)))
  }

  /**
   * `Color` is not a seedable parameter-knob kind. Flattening `#FF42A5F5` would hand the binder a
   * string that can only fail to parse as one of the numeric kinds — reporting an unsupported kind
   * as a malformed number. Dropping it here keeps the distinction.
   */
  @Test
  fun aColourSeedHasNoParameterKnobEquivalent() {
    assertNull(PreviewKnobSeeds.text(PreviewOverrideValue.ColorValue("#FF42A5F5")))
  }

  @Test
  fun aBagKeepsWhatItCanAndDropsWhatItCannot() {
    val bag =
      mapOf(
        "label" to PreviewOverrideValue.StringValue("hi"),
        "count" to PreviewOverrideValue.IntValue(4),
        "accent" to PreviewOverrideValue.ColorValue("#FF42A5F5"),
      )
    assertEquals(mapOf("label" to "hi", "count" to "4"), PreviewKnobSeeds.texts(bag))
  }

  @Test
  fun anAbsentOrEmptyBagIsEmpty() {
    assertEquals(emptyMap<String, String>(), PreviewKnobSeeds.texts(null))
    assertEquals(emptyMap<String, String>(), PreviewKnobSeeds.texts(emptyMap()))
  }
}
