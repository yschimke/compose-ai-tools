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

  private fun viewer(vararg declarations: PreviewOverrideDeclaration): String {
    val preview =
      ServePreview(id = "button-filled", label = "Filled", overrides = declarations.toList())
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      wasmSrc = "/wasm/compose-m3/?id=button-filled",
    )
  }

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
}
