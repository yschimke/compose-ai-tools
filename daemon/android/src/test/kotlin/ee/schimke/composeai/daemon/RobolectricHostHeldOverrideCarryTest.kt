package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The held (interactive / recording) lane must keep whatever the resolved spec already carried.
 *
 * `renderSpecFromInfo` turns a synthetic `@OverrideVariant` preview's `previews.json` seed into
 * `RenderSpec.overrides.namedOverrides`; `renderNow` layers the live bag over it in
 * [RobolectricHost.reshapeRenderPayload], but the held lane goes through `applyOverrides`, which
 * used to copy only four of the bag's fields into the merge. So the viewer's **Live** toggle
 * composed the variant's *base* state — `switchbutton__ideal__split` drew the un-split switch
 * (yschimke/wear-m3-catalog#33).
 */
class RobolectricHostHeldOverrideCarryTest {

  @Test
  fun `held session keeps the baked variant seed when the viewer sends no knob`() {
    // Browsing Live sends display fields only — no `knob.*` at all.
    val spec = host().applyOverridesForTest(variantSpec(SPLIT_SEED), PreviewOverrides())

    assertNotNull("the baked seed must survive the held-session merge", spec.overrides)
    assertEquals(
      PreviewOverrideValue.BooleanValue(true),
      spec.overrides?.namedOverrides?.get("split"),
    )
  }

  @Test
  fun `held session keeps the baked variant seed under an unrelated live override`() {
    val spec =
      host().applyOverridesForTest(variantSpec(SPLIT_SEED), PreviewOverrides(fontScale = 1.3f))

    assertEquals(
      PreviewOverrideValue.BooleanValue(true),
      spec.overrides?.namedOverrides?.get("split"),
    )
    assertEquals(1.3f, spec.fontScale!!, 0.0f)
  }

  @Test
  fun `an edited knob wins over the seed it shares a key with`() {
    val spec =
      host()
        .applyOverridesForTest(
          variantSpec(
            PreviewOverrides(
              namedOverrides =
                mapOf(
                  "split" to PreviewOverrideValue.BooleanValue(true),
                  "label" to PreviewOverrideValue.StringValue("Primary"),
                )
            )
          ),
          PreviewOverrides(
            namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("edited"))
          ),
        )

    assertEquals(
      PreviewOverrideValue.StringValue("edited"),
      spec.overrides?.namedOverrides?.get("label"),
    )
    assertEquals(
      "an untouched seed must not be cleared by the edit",
      PreviewOverrideValue.BooleanValue(true),
      spec.overrides?.namedOverrides?.get("split"),
    )
  }

  @Test
  fun `held session keeps the other extension fields the spec carried`() {
    // Same drop, different fields: focus / talkBack / permissions / … were hand-picked out too.
    val spec =
      host()
        .applyOverridesForTest(
          variantSpec(PreviewOverrides(focus = FocusOverride(tabIndex = 2), talkBack = true)),
          PreviewOverrides(),
        )

    assertEquals(FocusOverride(tabIndex = 2), spec.overrides?.focus)
    assertEquals(true, spec.overrides?.talkBack)
  }

  private fun host() = RobolectricHost(previewSpecResolver = { variantSpec(SPLIT_SEED) })

  /** What `renderSpecFromInfo` resolves for a `_VARIANT_split` preview. */
  private fun variantSpec(carried: PreviewOverrides) =
    RenderSpec(
      previewId = "ee.example.SelectionButtonsKt.SwitchRow_VARIANT_split",
      className = "ee.example.SelectionButtonsKt",
      functionName = "SwitchRow",
      overrides = carried,
    )

  private companion object {
    val SPLIT_SEED =
      PreviewOverrides(namedOverrides = mapOf("split" to PreviewOverrideValue.BooleanValue(true)))
  }
}
