package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the knob attributes the viewer's Wasm patch reads.
 *
 * A `@OverrideVariant` sticker (the unchecked checkbox, the disabled button) opens with its knob
 * already seeded away from the author default. The PNG lane can ignore that — the baked capture
 * carries the seed — but the Wasm tier mounts the live component from `?id=<slug>` with the variant
 * axis stripped off the id, so it has to be *told*. That means the page must publish both numbers:
 * what the control opens on, and what the author declared.
 */
class ServeWebKnobSeedTest {

  private fun declaration(key: String, default: Boolean, current: Boolean) =
    PreviewOverrideDeclaration(
      key = key,
      type = "bool",
      label = key,
      default = PreviewOverrideValue.BooleanValue(default),
      current = PreviewOverrideValue.BooleanValue(current),
    )

  private fun viewer(
    vararg declarations: PreviewOverrideDeclaration,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    val preview =
      ServePreview(id = "button-filled", label = "Filled", overrides = declarations.toList())
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      wasmSrc = "/wasm/compose-m3/?id=button-filled",
      requestOverrides = requestOverrides,
    )
  }

  /** The checkbox row for [key], so an assertion reads one control rather than the whole page. */
  private fun knobRow(html: String, key: String): String =
    html.lineSequence().first { it.contains("""data-knob-key="$key"""") }

  @Test
  fun `a seeded variant publishes both the opening value and the author default`() {
    val html = viewer(declaration("enabled", default = true, current = false))
    // The control opens on the seed…
    assertTrue(html.contains("""data-knob-initial="false""""), html.substringAfter("cp-knob"))
    // …and still says what the author declared, which is the only way the Wasm patch can tell that
    // this sticker is a variant rather than an untouched primary.
    assertTrue(html.contains("""data-knob-default="true""""))
  }

  @Test
  fun `an ordinary sticker opens on its author default, and says so`() {
    val html = viewer(declaration("enabled", default = true, current = true))
    assertTrue(html.contains("""data-knob-initial="true""""))
    assertTrue(html.contains("""data-knob-default="true""""))
  }

  /**
   * A deep link's knob value reaches the CONTROL, not only the snapshot `<img>`.
   *
   * The page's thumbnail has always carried the request's query, so `?knob.secondary=true` showed
   * the override immediately. Everything that reads the controls instead — the live socket's
   * `setOverrides`, the export links, the next `/render` — read the preview's declaration and sent
   * the un-overridden value, so the page disagreed with its own address the moment the live lane
   * was opened (yschimke/wear-m3-catalog#66).
   */
  @Test
  fun `a request override seeds the control`() {
    val html =
      viewer(
        declaration("secondary", default = false, current = false),
        requestOverrides = mapOf("knob.secondary" to "true"),
      )
    assertTrue(knobRow(html, "secondary").contains(" checked"), knobRow(html, "secondary"))
  }

  /**
   * …and `data-knob-initial` keeps naming the DECLARATION while it does.
   *
   * That gap is the mechanism, not an oversight: the viewer omits a knob still equal to `initial`,
   * so a plain visit sends no `knob.*` and a published catalog replays its instant baked PNG.
   * Pointing `initial` at the request instead would make the seeded control look untouched and
   * swallow the very override the visitor followed the link for.
   */
  @Test
  fun `a request override leaves the declared initial alone, so it still rides into the render`() {
    val row =
      knobRow(
        viewer(
          declaration("secondary", default = false, current = false),
          requestOverrides = mapOf("knob.secondary" to "true"),
        ),
        "secondary",
      )
    assertTrue(row.contains("""data-knob-initial="false""""), row)
    assertTrue(row.contains("""data-knob-default="false""""), row)
  }

  /** A request override displaces a variant's seed too — the link is the more specific answer. */
  @Test
  fun `a request override wins over an OverrideVariant seed`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = true, current = false),
          requestOverrides = mapOf("knob.enabled" to "true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
    // Still the seed, so the control's value differs from it and the render carries `knob.enabled`.
    assertTrue(row.contains("""data-knob-initial="false""""), row)
  }

  /** A knob the request doesn't name is untouched — a plain visit renders exactly as before. */
  @Test
  fun `an unnamed knob keeps its declared value`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = true, current = true),
          declaration("secondary", default = false, current = false),
          requestOverrides = mapOf("knob.secondary" to "true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
    assertTrue(row.contains("""data-knob-initial="true""""), row)
  }
}
