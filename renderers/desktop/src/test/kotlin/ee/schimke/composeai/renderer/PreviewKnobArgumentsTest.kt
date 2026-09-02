package ee.schimke.composeai.renderer

import ee.schimke.composeai.renderer.PreviewKnobArguments.Knob
import ee.schimke.composeai.renderer.PreviewKnobArguments.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewKnobArgumentsTest {

  private val knobs =
    listOf(
      Knob("label", 0, Type.STRING),
      Knob("enabled", 1, Type.BOOLEAN),
      Knob("count", 2, Type.INT),
    )

  @Test
  fun `an unseeded position is null so the parameter takes its author default`() {
    // The whole mechanism: null is what makes `ComposableMethod` set that parameter's $default
    // mask bit, so the compiled default expression runs. Seeding one knob must not disturb others.
    assertEquals(
      listOf<Any?>(null, false, null),
      PreviewKnobArguments.bind(knobs, mapOf("enabled" to "false")),
    )
  }

  @Test
  fun `each declared type parses to its own Kotlin value`() {
    val all =
      PreviewKnobArguments.bind(
        knobs +
          listOf(
            Knob("big", 3, Type.LONG),
            Knob("ratio", 4, Type.FLOAT),
            Knob("precise", 5, Type.DOUBLE),
          ),
        mapOf(
          "label" to "Hi",
          "enabled" to "true",
          "count" to "7",
          "big" to "8",
          "ratio" to "0.25",
          "precise" to "1.5",
        ),
      )
    assertEquals(listOf<Any?>("Hi", true, 7, 8L, 0.25f, 1.5), all)
  }

  @Test
  fun `an unparseable seed is dropped rather than coerced`() {
    // Publishing a capture that silently disagrees with the requested value is worse than visibly
    // ignoring it, so `count` falls back to its author default instead of becoming 0. With nothing
    // left to bind the result is empty rather than an all-null array — the two mean the same thing
    // to the renderer, and empty lets the caller keep its plain zero-argument invoke.
    assertTrue(PreviewKnobArguments.bind(knobs, mapOf("count" to "lots")).isEmpty())
  }

  @Test
  fun `a dropped seed does not disturb a sibling that parsed`() {
    // The array is still built when at least one seed binds; the unparseable one just stays null.
    assertEquals(
      listOf<Any?>("Hi", null, null),
      PreviewKnobArguments.bind(knobs, mapOf("label" to "Hi", "count" to "lots")),
    )
  }

  @Test
  fun `a lenient boolean is not accepted`() {
    // `toBoolean` would map "yes" to false and render the opposite of a `true` default.
    assertTrue(PreviewKnobArguments.bind(knobs, mapOf("enabled" to "yes")).isEmpty())
  }

  @Test
  fun `a seed naming no knob is ignored, leaving it to the previewOverride controller`() {
    // One seed map serves both override formats; each side binds only what it recognises.
    assertTrue(PreviewKnobArguments.bind(knobs, mapOf("someOverrideKey" to "x")).isEmpty())
  }

  @Test
  fun `no knobs or no seeds binds nothing, so the caller keeps its zero-argument invoke`() {
    assertTrue(PreviewKnobArguments.bind(emptyList(), mapOf("label" to "Hi")).isEmpty())
    assertTrue(PreviewKnobArguments.bind(knobs, emptyMap()).isEmpty())
  }

  @Test
  fun `the array spans the full parameter list, not just the knobs`() {
    // `count` sits at index 4 because positions 1..3 are defaulted-but-unseedable parameters.
    // Sizing by knobs.size would place the argument on the wrong parameter.
    val sparse = listOf(Knob("label", 0, Type.STRING), Knob("count", 4, Type.INT))
    assertEquals(
      listOf<Any?>("Hi", null, null, null, 9),
      PreviewKnobArguments.bind(sparse, mapOf("label" to "Hi", "count" to "9")),
    )
  }

  @Test
  fun `the payload token round trips`() {
    assertEquals(
      knobs,
      PreviewKnobArguments.parseToken("label:0:STRING,enabled:1:BOOLEAN,count:2:INT"),
    )
  }

  @Test
  fun `a malformed or unknown token entry is skipped, not fatal`() {
    // A newer plugin may name a knob kind this renderer cannot bind; dropping just that knob
    // degrades it to the author default while the rest of the preview still seeds.
    assertEquals(
      listOf(Knob("label", 0, Type.STRING)),
      PreviewKnobArguments.parseToken("label:0:STRING,broken,bad:x:INT,future:2:COLOR,:3:INT"),
    )
    assertTrue(PreviewKnobArguments.parseToken(null).isEmpty())
    assertTrue(PreviewKnobArguments.parseToken("  ").isEmpty())
  }
}
