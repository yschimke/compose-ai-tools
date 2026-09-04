package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the mapping from a discovery knob to the declaration a viewer draws its control from — the
 * seam that puts both override formats on one channel.
 */
class PreviewKnobDeclarationsTest {

  @Test
  fun `each seedable kind maps to the declaration type a viewer can draw`() {
    val declarations =
      PreviewKnobDeclarations.of(
        listOf(
          PreviewKnobDto("label", 0, "STRING", "Filled"),
          PreviewKnobDto("enabled", 1, "BOOLEAN", "true"),
          PreviewKnobDto("count", 2, "INT", "3"),
          PreviewKnobDto("ratio", 3, "FLOAT", "0.5"),
        ),
        seeds = null,
      )

    assertEquals(
      listOf(
        PreviewOverrideType.STRING,
        PreviewOverrideType.BOOL,
        PreviewOverrideType.INT,
        PreviewOverrideType.FLOAT,
      ),
      declarations.map { it.type },
    )
    assertEquals(
      listOf(
        PreviewOverrideValue.StringValue("Filled"),
        PreviewOverrideValue.BooleanValue(true),
        PreviewOverrideValue.IntValue(3),
        PreviewOverrideValue.FloatValue(0.5f),
      ),
      declarations.map { it.default },
    )
    // A parameter has a name and nothing else to label it with, and no indexed form — a parameter
    // list is fixed-arity, so there is no per-row value to address.
    assertEquals(listOf("label", "enabled", "count", "ratio"), declarations.map { it.label })
    assertEquals(listOf(null, null, null, null), declarations.map { it.index })
  }

  @Test
  fun `Long and Double are declared as text because the type set has no wider numerics`() {
    // Not a downgrade in reach: a text seed reaches the parameter through the same path and parses
    // against the knob's own kind. The viewer draws a text field instead of a number field, which
    // is the whole of the difference — and why an ARGB Long gets a text box, not a colour picker.
    val declarations =
      PreviewKnobDeclarations.of(
        listOf(
          PreviewKnobDto("accentArgb", 0, "LONG", "4281558783"),
          PreviewKnobDto("precise", 1, "DOUBLE", "1.5"),
        ),
        seeds = null,
      )

    assertEquals(
      listOf(PreviewOverrideType.STRING, PreviewOverrideType.STRING),
      declarations.map { it.type },
    )
    assertEquals(
      listOf(
        PreviewOverrideValue.StringValue("4281558783"),
        PreviewOverrideValue.StringValue("1.5"),
      ),
      declarations.map { it.default },
    )
  }

  @Test
  fun `current is the seeded value where one bound, and the default everywhere else`() {
    // A `current` that disagreed with the pixels beside it would be worse than showing nothing.
    val knobs =
      listOf(
        PreviewKnobDto("label", 0, "STRING", "Filled"),
        PreviewKnobDto("count", 1, "INT", "3"),
      )
    val declarations =
      PreviewKnobDeclarations.of(knobs, mapOf("count" to PreviewOverrideValue.IntValue(9)))

    assertEquals(
      listOf(PreviewOverrideValue.StringValue("Filled"), PreviewOverrideValue.IntValue(9)),
      declarations.map { it.current },
    )
    // …and `default` still reports what the author wrote, so a viewer can offer "reset".
    assertEquals(
      listOf(PreviewOverrideValue.StringValue("Filled"), PreviewOverrideValue.IntValue(3)),
      declarations.map { it.default },
    )
  }

  @Test
  fun `a knob whose default could not be recovered is left undeclared`() {
    // `PreviewOverrideDeclaration.default` is not nullable, so declaring one means inventing a
    // value and presenting it as what the author wrote — a wrong default and a "reset" that resets
    // to something the preview never said. The knob still seeds; it just has no control.
    val declarations =
      PreviewKnobDeclarations.of(
        listOf(
          PreviewKnobDto("label", 0, "STRING", null), // `stringResource(...)`
          PreviewKnobDto("count", 1, "INT", "3"),
        ),
        seeds = null,
      )

    assertEquals(listOf("count"), declarations.map { it.key })
  }

  @Test
  fun `a kind this daemon does not know is left undeclared rather than guessed at`() {
    val declarations =
      PreviewKnobDeclarations.of(
        listOf(PreviewKnobDto("accent", 0, "SOMETHING_NEWER", "#FF42A5F5")),
        seeds = null,
      )
    assertEquals(emptyList<String>(), declarations.map { it.key })
  }

  @Test
  fun `a preview with no knobs declares nothing`() {
    assertEquals(emptyList<Any>(), PreviewKnobDeclarations.of(emptyList(), null))
  }
}
