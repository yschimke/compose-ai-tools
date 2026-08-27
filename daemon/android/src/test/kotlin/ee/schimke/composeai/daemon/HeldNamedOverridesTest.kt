package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The string encoding [HeldNamedOverrides] uses to carry `previewOverride*` seeds across the
 * Robolectric sandbox boundary for a held (live) session.
 *
 * The two halves run on opposite sides of that boundary and never see each other's types, so the
 * only thing keeping them in agreement is this round trip. A kind that encodes to a spelling
 * [HeldNamedOverrides.decode] doesn't know drops silently — and a dropped seed is invisible: the
 * knob simply renders its author default, which is the very shape of yschimke/wear-m3-catalog#83.
 */
class HeldNamedOverridesTest {

  @Test
  fun `every value kind survives the round trip`() {
    val seeds =
      mapOf(
        "label" to PreviewOverrideValue.StringValue("Tap me"),
        "count" to PreviewOverrideValue.IntValue(3),
        "weight" to PreviewOverrideValue.FloatValue(1.5f),
        "split" to PreviewOverrideValue.BooleanValue(true),
        "fill" to PreviewOverrideValue.ColorValue("#FF2196F3"),
      )

    assertEquals(seeds, HeldNamedOverrides.decode(HeldNamedOverrides.encode(seeds)))
  }

  @Test
  fun `an indexed seed key crosses verbatim`() {
    val seeds = mapOf("rowLabel[2]" to PreviewOverrideValue.StringValue("third"))

    val encoded = HeldNamedOverrides.encode(seeds)
    assertEquals(setOf("rowLabel[2]"), encoded.keys)
    assertEquals(seeds, HeldNamedOverrides.decode(encoded))
  }

  @Test
  fun `an empty string seed is a value, not a missing one`() {
    // Clearing a label is an edit a viewer must be able to express, and an `@OverrideVariant` can
    // seed one deliberately (`strings = ["label="]`).
    val seeds = mapOf("label" to PreviewOverrideValue.StringValue(""))

    assertEquals(seeds, HeldNamedOverrides.decode(HeldNamedOverrides.encode(seeds)))
  }

  @Test
  fun `a string seed that looks like a typed one keeps its text`() {
    val seeds = mapOf("label" to PreviewOverrideValue.StringValue("int:3"))

    assertEquals(seeds, HeldNamedOverrides.decode(HeldNamedOverrides.encode(seeds)))
  }

  @Test
  fun `no seeds is no bag, so the planner is not handed an empty map`() {
    assertEquals(emptyMap<String, String>(), HeldNamedOverrides.encode(null))
    assertEquals(emptyMap<String, String>(), HeldNamedOverrides.encode(emptyMap()))
    assertNull(HeldNamedOverrides.decode(emptyMap()))
    assertNull(HeldNamedOverrides.decode(null))
  }

  @Test
  fun `a malformed wire value is dropped rather than guessed`() {
    // The author default is the right answer for a seed that doesn't parse to its kind — the same
    // one the type-strict host gives. Anything else invents pixels.
    assertNull(HeldNamedOverrides.decode(mapOf("count" to "int:three")))
    assertNull(HeldNamedOverrides.decode(mapOf("count" to "3")))
    assertNull(HeldNamedOverrides.decode(mapOf("count" to "unknown:3")))
  }
}
