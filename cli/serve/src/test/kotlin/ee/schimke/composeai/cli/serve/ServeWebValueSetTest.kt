package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how a knob's **value set** reaches the viewer.
 *
 * A knob declared with `previewOverrideChoice` carries the values it may take, so the control can
 * be a picker instead of a text box the visitor has to already know the vocabulary for — `size`
 * used to render as a field showing `s`, which told you the current value and nothing about
 * `xs`/`m`/`l`/ `xl` being the alternatives.
 *
 * Two forms, one source: an **exhaustive** set becomes a `<select>` (nothing outside it is
 * expressible), a non-exhaustive one stays a free-text `<input list>` over a `<datalist>`. The
 * locale field is the standing instance of the second form and renders through the same helper.
 */
class ServeWebValueSetTest {

  private fun choice(
    key: String,
    current: String,
    options: List<PreviewOverrideOption>,
    exhaustive: Boolean = true,
    default: String = current,
  ) =
    PreviewOverrideDeclaration(
      key = key,
      type = "string",
      label = key,
      default = PreviewOverrideValue.StringValue(default),
      current = PreviewOverrideValue.StringValue(current),
      options = options,
      optionsExhaustive = exhaustive,
    )

  private fun viewer(vararg declarations: PreviewOverrideDeclaration): String {
    val preview =
      ServePreview(id = "button-filled", label = "Filled", overrides = declarations.toList())
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/m3-catalog",
      siblings = listOf(preview),
      canApplyOverrides = true,
    )
  }

  /**
   * Just the Overrides panel. The page carries other `<select>`s (the theme selector, the renderer
   * combo) and always carries the locale `<datalist>`, so an assertion about "the" knob control has
   * to look inside this group or it reads the wrong element.
   */
  private fun knobsPanel(html: String): String =
    html.substringAfter("data-cp-group=\"overrides\"").substringBefore("</details>")

  private val sizes =
    listOf(
      PreviewOverrideOption("xs", "Extra small"),
      PreviewOverrideOption("s", "Small"),
      PreviewOverrideOption("m", "Medium"),
      PreviewOverrideOption("l", "Large"),
      PreviewOverrideOption("xl", "Extra large"),
    )

  @Test
  fun `an exhaustive value set renders a select carrying every value`() {
    val panel = knobsPanel(viewer(choice("size", current = "s", options = sizes)))
    val select = panel.substringAfter("<select").substringBefore("</select>")

    assertTrue(panel.contains("<select"), "a closed value set is a picker, not a text field")
    for (option in sizes) {
      assertTrue(
        select.contains("value=\"${option.value}\""),
        "every declared value is offered — ${option.value} is missing",
      )
    }
    assertTrue(select.contains(">Extra small</option>"), "the option shows its label")
    assertTrue(
      select.contains("value=\"s\" selected>Small</option>"),
      "the control opens on the knob's current value",
    )
  }

  @Test
  fun `a select keeps the knob contract the viewer script reads`() {
    // The JS reads `.value` / `.disabled` and only special-cases `type === "checkbox"`, so a
    // `<select>` needs no new branch there — but it does need the same data attributes, or the
    // knob is skipped (no key), always sent (no initial), or mounted wrong under Wasm (no default).
    val panel = knobsPanel(viewer(choice("size", current = "xl", default = "s", options = sizes)))
    val select = panel.substringAfter("<select").substringBefore("</select>")

    assertTrue(select.contains("class=\"cp-knob\""), "the control is collected as a knob")
    assertTrue(select.contains("data-knob-key=\"size\""), "carries its wire key")
    assertTrue(select.contains("data-knob-kind=\"string\""), "carries its kind")
    assertTrue(select.contains("data-knob-initial=\"xl\""), "carries what it opens on")
    assertTrue(select.contains("data-knob-default=\"s\""), "carries the author default")
  }

  @Test
  fun `a current value outside the set is kept rather than snapped to the first option`() {
    // A hand-written `knob.size=xxl`, or a link from before a value was renamed. Dropping it would
    // show a control that disagrees with the pixels beside it.
    val panel = knobsPanel(viewer(choice("size", current = "xxl", default = "s", options = sizes)))
    val select = panel.substringAfter("<select").substringBefore("</select>")

    assertTrue(
      select.contains("value=\"xxl\" selected>xxl</option>"),
      "an unknown current value stays visible and selected",
    )
    assertEquals(
      sizes.size + 1,
      Regex("<option ").findAll(select).count(),
      "it is added to the declared set, not substituted for a member of it",
    )
  }

  @Test
  fun `a non-exhaustive value set stays free text over a datalist`() {
    val html =
      viewer(
        choice(
          "locale",
          current = "en-US",
          options = listOf(PreviewOverrideOption("en-XA", "Accented (pseudo)")),
          exhaustive = false,
        )
      )

    val panel = knobsPanel(html)
    assertFalse(panel.contains("<select"), "an open set is not a closed picker")
    assertTrue(
      panel.contains("<input type=\"text\" class=\"cp-knob\" data-knob-key=\"locale\""),
      "an open set keeps a typeable field",
    )
    assertTrue(
      panel.contains("value=\"en-XA\" label=\"Accented (pseudo)\""),
      "the datalist option carries its label",
    )
  }

  @Test
  fun `a knob with no value set is unchanged`() {
    val plain =
      PreviewOverrideDeclaration(
        key = "label",
        type = "string",
        label = "label",
        default = PreviewOverrideValue.StringValue("Tap me"),
        current = PreviewOverrideValue.StringValue("Tap me"),
      )
    val panel = knobsPanel(viewer(plain))

    assertTrue(
      panel.contains("<input type=\"text\" class=\"cp-knob\" data-knob-key=\"label\""),
      "a knob declaring no values still renders the plain input it always did",
    )
    assertFalse(panel.contains("<datalist"), "and grows no options list")
    assertFalse(panel.contains("<select"), "nor a picker")
  }

  @Test
  fun `the locale field offers labelled presets without changing how it is plumbed`() {
    // The locale control is server chrome, not a declared knob: the viewer script finds it by
    // `id="cp-localeTag"` and sends its raw value as `localeTag`. Only where its option list comes
    // from changed, so those anchors have to survive verbatim.
    val html =
      ServeWeb.viewerPage(
        ServePreview(id = "button-filled", label = "Filled"),
        token = "t",
        basePath = "/m3-catalog",
        siblings = emptyList(),
      )

    assertTrue(
      html.contains("<input id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\""),
      "still a free-text field bound to its datalist by the id the script looks up",
    )
    val list =
      html.substringAfter("<datalist id=\"cp-localeTag-list\">").substringBefore("</datalist>")
    assertTrue(
      list.contains("<option value=\"en-XA\" label=\"Accented (pseudo)\"></option>"),
      "a preset whose tag is not self-explanatory is labelled",
    )
    assertTrue(
      list.contains("<option value=\"en-GB\"></option>"),
      "a self-labelling tag emits the bare option it always did",
    )
    assertTrue(
      list.contains("ar-XB") && list.contains("zh-Hant-TW"),
      "the preset list is unchanged in content",
    )
  }
}
