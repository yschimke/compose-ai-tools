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

  /**
   * A deep link may spell the value with its legacy `<kind>:` wire tag. The control holds the BARE
   * value, so the tag is stripped exactly where `ServeOverrides.parse` strips it.
   *
   * Seeded verbatim, `?knob.enabled=bool:true` reads as unchecked (the checkbox tests the whole
   * string) and `?knob.count=int:3` puts `int:3` in a number input, which the browser sanitizes to
   * empty — either way the control disagrees with the render the same URL produced, and the next
   * query built from that control drops or inverts the value.
   */
  @Test
  fun `a legacy kind prefix is stripped before the control is seeded`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = false, current = false),
          requestOverrides = mapOf("knob.enabled" to "bool:true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /**
   * …but only when it matches the DECLARED kind. A string knob may legitimately hold text beginning
   * `int:` — the type-free viewer submits it verbatim — and eating that prefix would silently
   * rewrite the value, which is the same rule `ServeOverrides.parse` applies on the way in.
   */
  @Test
  fun `a mismatched kind prefix is left in a string knob's value`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "label",
              type = "string",
              label = "label",
              default = PreviewOverrideValue.StringValue(""),
            )
          ),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        requestOverrides = mapOf("knob.label" to "int:3"),
      )
    assertTrue(knobRow(html, "label").contains("""value="int:3""""), knobRow(html, "label"))
  }

  private fun rcViewer(
    declaration: ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration,
    requestOverrides: Map<String, String>,
  ): String {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        remoteComposeKnobs = listOf(declaration),
      )
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      canApplyOverrides = true,
      requestOverrides = requestOverrides,
    )
  }

  private fun rcRow(html: String, name: String): String =
    html.lineSequence().first { it.contains("""data-rc-name="$name"""") }

  /**
   * A Remote Compose bool reads `1` as true, like every other consumer of the same seed.
   *
   * `?rc.enabled=bool:1` parses to `BooleanValue(true)` and `hydrateFromUrl` ticks the box for it.
   * Testing only for the literal `true` was safe while the control always showed the declaration
   * (whose text is `true` / `false`); once a deep link can seed it, that spelling would have drawn
   * an unticked box beside a render that obeyed the value.
   */
  @Test
  fun `an rc bool seeded as 1 is checked`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "enabled",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue(false),
          ),
          mapOf("rc.enabled" to "bool:1"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /**
   * An RC seed whose kind won't parse as the declared one leaves the control alone.
   *
   * RC params type themselves from their own `<kind>:` tag and default to `string` with no
   * declaration lookup — unlike a plain knob, which takes its type from the declaration. So
   * `?rc.count=3` on a declared int parses as `StringValue("3")` and the renderer keeps the
   * authored int: showing `3` would contradict the pixels, and the next query built from that
   * control would serialise `rc.count=int:3`, turning a request the renderer ignored into one it
   * obeys.
   */
  @Test
  fun `an rc seed that would not parse as the declared kind is ignored`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "count",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue(5),
          ),
          mapOf("rc.count" to "3"),
        ),
        "count",
      )
    assertTrue(row.contains("""value="5""""), row)
  }

  /** …and one that agrees with the declared kind is taken, with its wire tag stripped. */
  @Test
  fun `an rc seed tagged with the declared kind seeds the control`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "count",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue(5),
          ),
          mapOf("rc.count" to "int:3"),
        ),
        "count",
      )
    assertTrue(row.contains("""value="3""""), row)
  }

  /**
   * `bool:TRUE` ticks the box, because `parse` reads it with `ignoreCase = true` and the parser is
   * what decides the pixels.
   */
  @Test
  fun `a mixed-case bool seed is checked`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = false, current = false),
          requestOverrides = mapOf("knob.enabled" to "bool:TRUE"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /**
   * An EMPTY non-string seed leaves the control on the declaration, because `parse` skips it.
   *
   * `?knob.count=` (or `int:`) has nothing to parse, so the render keeps the authored count.
   * Assigning `""` to the number field would blank it beside pixels that used `5` — the same
   * disagreement, produced by seeding rather than by not seeding.
   */
  @Test
  fun `an empty non-string seed keeps the declaration`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "count",
              type = "int",
              label = "count",
              default = PreviewOverrideValue.IntValue(5),
            )
          ),
      )
    fun rowFor(seed: String): String {
      val html =
        ServeWeb.viewerPage(
          preview,
          token = "t",
          basePath = "/compose-m3",
          siblings = listOf(preview),
          requestOverrides = mapOf("knob.count" to seed),
        )
      return knobRow(html, "count")
    }
    assertTrue(rowFor("").contains("""value="5""""), rowFor(""))
    assertTrue(rowFor("int:").contains("""value="5""""), rowFor("int:"))
    // …while a real value still seeds, so this is a skip rather than a blanket refusal.
    assertTrue(rowFor("3").contains("""value="3""""), rowFor("3"))
  }

  /** An empty STRING seed is a real value — a cleared label — and reaches the control. */
  @Test
  fun `an empty string seed clears the control`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "label",
              type = "string",
              label = "label",
              default = PreviewOverrideValue.StringValue("Tap me"),
            )
          ),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        requestOverrides = mapOf("knob.label" to ""),
      )
    assertTrue(knobRow(html, "label").contains("""value="""""), knobRow(html, "label"))
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
