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
  fun aSeedBindsToItsParameterPositionAndLeavesTheRestAlone() {
    val knobs =
      listOf(
        PreviewKnobDto("topArgb", 0, "LONG"),
        PreviewKnobDto("bottomArgb", 1, "LONG"),
        PreviewKnobDto("label", 2, "STRING"),
      )
    // Only the middle knob is seeded, so the array carries a null on either side of it — which is
    // how a partial seed says "leave this parameter alone" and lets the compiled default run.
    assertEquals(
      listOf(null, 7L, null),
      PreviewKnobSeeds.bind(
        knobs,
        mapOf("bottomArgb" to PreviewOverrideValue.IntValue(7)),
      ),
    )
  }

  @Test
  fun theArrayIsSizedByTheHighestIndexNotTheKnobCount() {
    // A knob's index is its position in the FULL value-parameter list, which may skip positions
    // that are defaulted but not seedable (`modifier: Modifier = Modifier`). Sizing by the count
    // would place the argument at the wrong parameter.
    val sparse = listOf(PreviewKnobDto("label", 0, "STRING"), PreviewKnobDto("count", 2, "INT"))
    assertEquals(
      listOf("Hi", null, 9),
      PreviewKnobSeeds.bind(
        sparse,
        mapOf(
          "label" to PreviewOverrideValue.StringValue("Hi"),
          "count" to PreviewOverrideValue.StringValue("9"),
        ),
      ),
    )
  }

  @Test
  fun anUnparseableSeedIsDroppedRatherThanCoerced() {
    // Coercing "yes" to true, or truncating "1.5" to an Int, would publish a capture that silently
    // disagrees with the value the client asked for — worse than visibly ignoring it.
    val knobs = listOf(PreviewKnobDto("enabled", 0, "BOOLEAN"), PreviewKnobDto("count", 1, "INT"))
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobSeeds.bind(knobs, mapOf("enabled" to PreviewOverrideValue.StringValue("yes"))),
    )
    assertEquals(
      listOf(true, null),
      PreviewKnobSeeds.bind(
        knobs,
        mapOf(
          "enabled" to PreviewOverrideValue.BooleanValue(true),
          "count" to PreviewOverrideValue.StringValue("1.5"),
        ),
      ),
    )
  }

  @Test
  fun aKindThisDaemonCannotBuildIsDropped() {
    // A newer plugin may name a knob kind an older daemon has never heard of. That parameter takes
    // its author default; the rest of the preview still seeds.
    val knobs = listOf(PreviewKnobDto("accent", 0, "COLOR"), PreviewKnobDto("label", 1, "STRING"))
    assertEquals(
      listOf(null, "Hi"),
      PreviewKnobSeeds.bind(
        knobs,
        mapOf(
          "accent" to PreviewOverrideValue.StringValue("#FF42A5F5"),
          "label" to PreviewOverrideValue.StringValue("Hi"),
        ),
      ),
    )
  }

  @Test
  fun nothingToBindIsAnEmptyListNotAnAllNullArray() {
    // So a caller keeps the zero-argument invoke a plain preview has always used.
    val knobs = listOf(PreviewKnobDto("label", 0, "STRING"))
    assertEquals(emptyList<Any?>(), PreviewKnobSeeds.bind(emptyList(), null))
    assertEquals(emptyList<Any?>(), PreviewKnobSeeds.bind(knobs, null))
    assertEquals(
      emptyList<Any?>(),
      // A seed naming no declared knob — it may be a `previewOverride*` key, which the controller
      // seeds separately. One seed map serves both formats and each side takes what it recognises.
      PreviewKnobSeeds.bind(knobs, mapOf("someOverrideKey" to PreviewOverrideValue.IntValue(1))),
    )
  }

  @Test
  fun anAbsentOrEmptyBagIsEmpty() {
    assertEquals(emptyMap<String, String>(), PreviewKnobSeeds.texts(null))
    assertEquals(emptyMap<String, String>(), PreviewKnobSeeds.texts(emptyMap()))
  }
}
