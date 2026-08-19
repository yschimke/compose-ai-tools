package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Desktop twin of `RobolectricHostHeldOverrideCarryTest` — the held (interactive / recording) lane
 * must keep whatever the resolved spec already carried.
 *
 * The desktop adapter dropped even more than the Android one: it named only `material3Theme` and
 * `wallpaper`, so a `@OverrideVariant` seed, `ambient`, `gestures`, `focus`, `talkBack`,
 * `touchOverlay`, `permissions`, `remoteCompose` and `launcherWidget` all fell out of a live
 * session's spec (yschimke/wear-m3-catalog#33).
 */
class DesktopHeldOverrideCarryTest {

  @Test
  fun `held session keeps the baked variant seed when the viewer sends no knob`() {
    val spec = host().applyOverridesForTest(variantSpec(SPLIT_SEED), PreviewOverrides())

    assertNotNull("the baked seed must survive the held-session merge", spec.overrides)
    assertEquals(
      PreviewOverrideValue.BooleanValue(true),
      spec.overrides?.namedOverrides?.get("split"),
    )
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
    val spec =
      host()
        .applyOverridesForTest(
          variantSpec(
            PreviewOverrides(
              ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT),
              touchOverlay = true,
            )
          ),
          PreviewOverrides(),
        )

    assertEquals(AmbientOverride(state = AmbientStateOverride.AMBIENT), spec.overrides?.ambient)
    assertEquals(true, spec.overrides?.touchOverlay)
  }

  private fun host() = DesktopHost(previewSpecResolver = { variantSpec(SPLIT_SEED) })

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
