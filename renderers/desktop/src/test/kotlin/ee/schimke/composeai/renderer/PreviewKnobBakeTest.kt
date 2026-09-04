package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a desktop bake now publishes for a **parameter knob** — the seam that lets a CMP
 * preview migrated off `previewOverride*` still fill `<stem>.overrides.json`, which is where
 * `compose-preview serve` reads its control list from even against a live daemon.
 */
class PreviewKnobBakeTest {

  private val knobs =
    listOf(
      PreviewKnobSpec("title", 0, "STRING", "Shopping list"),
      PreviewKnobSpec("count", 1, "INT", "3"),
      PreviewKnobSpec("enabled", 2, "BOOLEAN", "true"),
    )

  @Test
  fun `the payload the plugin sends is previews-json's own knobs array`() {
    // Serialized plugin-side straight off `PreviewKnob`, so this is the shape discovery writes.
    val parsed =
      PreviewKnobBake.parse(
        """
        [{"name":"title","index":0,"type":"STRING","default":"Shopping list"},
         {"name":"count","index":1,"type":"INT","default":"3"},
         {"name":"enabled","index":2,"type":"BOOLEAN","default":"true"}]
        """
          .trimIndent()
      )
    assertEquals(knobs, parsed)
  }

  @Test
  fun `a knob with no recoverable default still arrives, it just cannot be declared`() {
    val parsed = PreviewKnobBake.parse("""[{"name":"title","index":0,"type":"STRING"}]""")
    assertEquals(listOf(PreviewKnobSpec("title", 0, "STRING", null)), parsed)
    // It still binds a seed…
    assertEquals(
      listOf("seeded"),
      PreviewKnobBake.seedArgs(
        parsed,
        mapOf("title" to PreviewOverrideValue.StringValue("seeded")),
      ),
    )
    // …but declaring it would mean inventing an author default and offering a "reset" to a value
    // the preview never said.
    assertEquals(emptyList<String>(), PreviewKnobBake.declarations(parsed, null).map { it.key })
  }

  @Test
  fun `an entry missing a load-bearing field is dropped rather than defaulted`() {
    // A knob with an invented name or position would bind a seed to the WRONG parameter, which is
    // worse than the knob not existing.
    assertEquals(
      listOf(PreviewKnobSpec("ok", 1, "INT", null)),
      PreviewKnobBake.parse(
        """[{"index":0,"type":"STRING"},{"name":"noIndex","type":"INT"},
            {"name":"noType","index":9},{"name":"ok","index":1,"type":"INT"}]"""
      ),
    )
  }

  @Test
  fun `a malformed payload costs this preview its controls, not the capture`() {
    // Best-effort by design: failing the render would turn a wiring bug into a broken build, and
    // every parameter still renders at its compiled default.
    assertEquals(emptyList<PreviewKnobSpec>(), PreviewKnobBake.parse("not json"))
    assertEquals(emptyList<PreviewKnobSpec>(), PreviewKnobBake.parse("""{"not":"an array"}"""))
    assertEquals(emptyList<PreviewKnobSpec>(), PreviewKnobBake.parse(""))
  }

  @Test
  fun `a preview nobody seeded still declares every knob at its author default`() {
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
    assertEquals(declarations.map { it.default }, declarations.map { it.current })
    assertEquals(listOf(null, null, null), declarations.map { it.index })
  }

  @Test
  fun `current reports the seeded value so the control cannot disagree with the pixels`() {
    val declarations =
      PreviewKnobBake.declarations(knobs, mapOf("count" to PreviewOverrideValue.IntValue(9)))

    assertEquals(PreviewOverrideValue.IntValue(9), declarations[1].current)
    assertEquals(PreviewOverrideValue.IntValue(3), declarations[1].default)
  }

  @Test
  fun `Long and Double are declared as text because the type set has no wider numerics`() {
    val declarations =
      PreviewKnobBake.declarations(
        listOf(
          PreviewKnobSpec("accentArgb", 0, "LONG", "4281558783"),
          PreviewKnobSpec("precise", 1, "DOUBLE", "1.5"),
        ),
        seeds = null,
      )
    assertEquals(
      listOf(PreviewOverrideType.STRING, PreviewOverrideType.STRING),
      declarations.map { it.type },
    )
    // The reach is unchanged: a text seed parses against the knob's own kind on the way in.
    assertEquals(
      listOf(4281558783L, null),
      PreviewKnobBake.seedArgs(
        listOf(
          PreviewKnobSpec("accentArgb", 0, "LONG", "4281558783"),
          PreviewKnobSpec("precise", 1, "DOUBLE", "1.5"),
        ),
        mapOf("accentArgb" to PreviewOverrideValue.StringValue("4281558783")),
      ),
    )
  }

  @Test
  fun `a kind this renderer does not know is dropped rather than guessed at`() {
    val newer = listOf(PreviewKnobSpec("accent", 0, "SOMETHING_NEWER", "#FF42A5F5"))
    assertEquals(emptyList<String>(), PreviewKnobBake.declarations(newer, null).map { it.key })
    assertEquals(
      emptyList<Any?>(),
      PreviewKnobBake.seedArgs(newer, mapOf("accent" to PreviewOverrideValue.StringValue("x"))),
    )
  }

  @Test
  fun `a partial seed leaves every unnamed position null for the defaults mask to fill`() {
    assertEquals(
      listOf(null, null, false),
      PreviewKnobBake.seedArgs(knobs, mapOf("enabled" to PreviewOverrideValue.BooleanValue(false))),
    )
  }

  @Test
  fun `the array is sized by the highest index so a non-knob parameter keeps its slot`() {
    // `index` is a position in the FULL value-parameter list: a `modifier: Modifier = Modifier`
    // ahead of the knob is defaulted but not seedable, and its slot must stay null.
    assertEquals(
      listOf(null, null, "seeded"),
      PreviewKnobBake.seedArgs(
        listOf(PreviewKnobSpec("label", 2, "STRING", "hi")),
        mapOf("label" to PreviewOverrideValue.StringValue("seeded")),
      ),
    )
  }

  @Test
  fun `a seed that is not a valid value of its knob kind falls back to the author default`() {
    // Coercing `"yes"` to `true` would publish a capture that disagrees with what was asked for.
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
  fun `an absent property means a preview with no knobs`() {
    System.clearProperty(PreviewKnobBake.KNOBS_PROPERTY)
    assertEquals(emptyList<PreviewKnobSpec>(), PreviewKnobBake.fromSystemProperty())
    try {
      System.setProperty(
        PreviewKnobBake.KNOBS_PROPERTY,
        """[{"name":"title","index":0,"type":"STRING","default":"Shopping list"}]""",
      )
      assertEquals(
        listOf(PreviewKnobSpec("title", 0, "STRING", "Shopping list")),
        PreviewKnobBake.fromSystemProperty(),
      )
    } finally {
      System.clearProperty(PreviewKnobBake.KNOBS_PROPERTY)
    }
  }
}
