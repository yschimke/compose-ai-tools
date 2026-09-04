package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a bake now publishes for a **parameter knob** — the seam that lets a preview migrated
 * off `previewOverride*` still fill `renders/<stem>.overrides.json`, which is where
 * `compose-preview serve` reads its control list from even against a live daemon.
 */
class PreviewKnobBakeTest {

  private val knobs =
    listOf(
      RenderPreviewKnob("title", 0, "STRING", "Shopping list"),
      RenderPreviewKnob("count", 1, "INT", "3"),
      RenderPreviewKnob("enabled", 2, "BOOLEAN", "true"),
    )

  @Test
  fun `a preview nobody seeded still declares every knob at its author default`() {
    // The case that unblocks a migrated sample: no seed at all, and the sidecar must still carry
    // the controls. Before this the drain came back empty and `serve` showed nothing.
    val declarations = PreviewKnobBake.declarations(knobs, seeds = null)

    assertEquals(listOf("title", "count", "enabled"), declarations.map { it.key })
    assertEquals(
      listOf(PreviewOverrideType.STRING, PreviewOverrideType.INT, PreviewOverrideType.BOOL),
      declarations.map { it.type },
    )
    assertEquals(
      listOf(
        PreviewOverrideValue.StringValue("Shopping list"),
        PreviewOverrideValue.IntValue(3),
        PreviewOverrideValue.BooleanValue(true),
      ),
      declarations.map { it.default },
    )
    // Nothing seeded, so what is in force is what the author wrote.
    assertEquals(declarations.map { it.default }, declarations.map { it.current })
    // A parameter has a name and nothing else to label it with, and no per-row indexed form.
    assertEquals(listOf("title", "count", "enabled"), declarations.map { it.label })
    assertEquals(listOf(null, null, null), declarations.map { it.index })
  }

  @Test
  fun `current reports the seeded value so the control cannot disagree with the pixels`() {
    val declarations =
      PreviewKnobBake.declarations(knobs, mapOf("count" to PreviewOverrideValue.IntValue(9)))

    assertEquals(
      listOf(
        PreviewOverrideValue.StringValue("Shopping list"),
        PreviewOverrideValue.IntValue(9),
        PreviewOverrideValue.BooleanValue(true),
      ),
      declarations.map { it.current },
    )
    // …and `default` still reports what the author wrote, so a viewer can offer "reset".
    assertEquals(PreviewOverrideValue.IntValue(3), declarations[1].default)
  }

  @Test
  fun `a knob whose default discovery could not recover is left undeclared`() {
    // `PreviewOverrideDeclaration.default` is not nullable, so declaring one would mean inventing a
    // value and presenting it as the author's — a wrong default and a "reset" to something the
    // preview never said. The knob still seeds; it just gets no control.
    val declarations =
      PreviewKnobBake.declarations(
        listOf(
          RenderPreviewKnob("title", 0, "STRING", null), // `stringResource(...)`
          RenderPreviewKnob("count", 1, "INT", "3"),
        ),
        seeds = null,
      )

    assertEquals(listOf("count"), declarations.map { it.key })
  }

  @Test
  fun `Long and Double are declared as text because the type set has no wider numerics`() {
    val declarations =
      PreviewKnobBake.declarations(
        listOf(
          RenderPreviewKnob("accentArgb", 0, "LONG", "4281558783"),
          RenderPreviewKnob("precise", 1, "DOUBLE", "1.5"),
        ),
        seeds = null,
      )

    assertEquals(
      listOf(PreviewOverrideType.STRING, PreviewOverrideType.STRING),
      declarations.map { it.type },
    )
  }

  @Test
  fun `a kind this renderer does not know is dropped rather than guessed at`() {
    val newer = listOf(RenderPreviewKnob("accent", 0, "SOMETHING_NEWER", "#FF42A5F5"))
    assertEquals(emptyList<String>(), PreviewKnobBake.declarations(newer, null).map { it.key })
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobBake.seedArgs(newer, mapOf("accent" to PreviewOverrideValue.StringValue("x"))),
    )
  }

  @Test
  fun `a partial seed leaves every unnamed position null for the defaults mask to fill`() {
    val args =
      PreviewKnobBake.seedArgs(knobs, mapOf("enabled" to PreviewOverrideValue.BooleanValue(false)))
    assertEquals(listOf(null, null, false), args)
  }

  @Test
  fun `the array is sized by the highest index so a non-knob parameter keeps its slot`() {
    // `index` is a position in the FULL value-parameter list: a `modifier: Modifier = Modifier`
    // ahead of the knob is defaulted but not seedable, and its slot must stay null.
    val args =
      PreviewKnobBake.seedArgs(
        listOf(RenderPreviewKnob("label", 2, "STRING", "hi")),
        mapOf("label" to PreviewOverrideValue.StringValue("seeded")),
      )
    assertEquals(listOf(null, null, "seeded"), args)
  }

  @Test
  fun `a seed that is not a valid value of its knob kind falls back to the author default`() {
    // Coercing `"yes"` to `true` would publish a capture that silently disagrees with the request.
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobBake.seedArgs(knobs, mapOf("enabled" to PreviewOverrideValue.StringValue("yes"))),
    )
    // A colour has no parameter-knob equivalent, so it binds nothing rather than half-parsing.
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobBake.seedArgs(
        knobs,
        mapOf("title" to PreviewOverrideValue.ColorValue("#FF42A5F5")),
      ),
    )
  }

  @Test
  fun `nothing to bind keeps the zero-argument invoke a plain preview has always used`() {
    assertEquals(emptyList<Any?>(), PreviewKnobBake.seedArgs(emptyList(), null))
    assertEquals(emptyList<Any?>(), PreviewKnobBake.seedArgs(knobs, emptyMap()))
    // A seed naming no declared parameter is the other format's key — the controller takes it.
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobBake.seedArgs(knobs, mapOf("label" to PreviewOverrideValue.StringValue("x"))),
    )
    assertEquals(emptyList<Any?>(), PreviewKnobBake.declarations(emptyList(), null))
  }

  @Test
  fun `a manifest entry carries its knobs, and one without them still reads`() {
    val json = Json { ignoreUnknownKeys = true }
    val withKnobs =
      json.decodeFromString(
        RenderPreviewEntry.serializer(),
        """
        {"id":"a","functionName":"Card","className":"C",
         "knobs":[{"name":"title","index":0,"type":"STRING","default":"Shopping list"}]}
        """
          .trimIndent(),
      )
    assertEquals(listOf(RenderPreviewKnob("title", 0, "STRING", "Shopping list")), withKnobs.knobs)

    // Additive: every manifest written before this field, and every unmigrated module, is
    // unchanged.
    val without =
      json.decodeFromString(
        RenderPreviewEntry.serializer(),
        """{"id":"a","functionName":"Card","className":"C"}""",
      )
    assertEquals(emptyList<RenderPreviewKnob>(), without.knobs)
  }
}
